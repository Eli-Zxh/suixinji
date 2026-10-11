package com.eli.suixinji.workspace

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.eli.suixinji.util.AppLog
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * SAF 模型批量导入器。设计要点：
 * - ASR 模型（3 个小文件）用「选文件夹」方式：一个文件夹里放什么就导入什么，
 *   避免 MagicOS 文件选择器「最近」列表不显示未知类型文件的坑。
 * - LLM 模型（单个 2.5GB 文件）用「选文件」方式：[importFromFile] 直接拷贝所选文件，
 *   用户选中什么就导入什么（不要求文件名）。
 * - 全程写日志：treeUri/文件清单、逐文件命中/缺失、拷贝字节数、异常堆栈。
 * - 拷贝走临时文件 + rename，失败不会留下半截目标文件。
 */
object ModelImporter {

  data class Report(
    val imported: List<String>,
    val missing: List<String>,
    val failed: Map<String, String>,
    val scannedCount: Int,
    val sampleNames: List<String>,
  ) {
    val ok: Boolean get() = missing.isEmpty() && failed.isEmpty()

    fun describe(): String {
      val parts = ArrayList<String>()
      if (imported.isNotEmpty()) parts.add("已导入 ${imported.joinToString("、")}")
      if (missing.isNotEmpty()) parts.add("未找到 ${missing.joinToString("、")}")
      if (failed.isNotEmpty()) parts.add("失败 ${failed.entries.joinToString("、") { "${it.key}（${it.value}）" }}")
      if (!ok && scannedCount > 0) {
        parts.add("所选目录共 ${scannedCount} 项：${sampleNames.joinToString("、")}")
      }
      return parts.joinToString("；").ifBlank { "未做任何导入" }
    }
  }

  /**
   * 从 SAF 目录导入 [wanted]（文件名 → 目标文件）。
   * [onProgress] 汇报总体进度 0f..1f（按已拷贝字节/总字节）。
   */
  suspend fun importFromTree(
    context: Context,
    treeUri: Uri,
    wanted: Map<String, File>,
    onProgress: (Float) -> Unit = {},
  ): Report = withContext(Dispatchers.IO) {
    AppLog.i("importFromTree 开始 treeUri=$treeUri 期望=${wanted.keys.joinToString("、")}")
    val report: Report = try {
      val root = DocumentFile.fromTreeUri(context, treeUri)
      if (root == null) {
        AppLog.e("fromTreeUri 返回 null（provider 异常？）")
        Report(emptyList(), wanted.keys.toList(), emptyMap(), 0, emptyList())
      } else {
        AppLog.i("目录=${root.name} uri=${root.uri}")
        val listResult: Result<List<DocumentFile>> = runCatching { root.listFiles().toList() }
        listResult.exceptionOrNull()?.let { AppLog.e("listFiles 失败", it) }
        val list: List<DocumentFile> = listResult.getOrDefault(emptyList())
        val allNames: List<String> = list.mapNotNull { it.name }
        AppLog.i("目录共 ${list.size} 项：${allNames.take(20).joinToString("、")}")

        val imported = ArrayList<String>()
        val missing = ArrayList<String>()
        val failed = LinkedHashMap<String, String>()

        // 总字节（用于整体进度）
        var totalBytes = 0L
        for (name in wanted.keys) {
          val doc: DocumentFile? = list.firstOrNull { it.name == name && it.isFile }
          if (doc != null) totalBytes += doc.length()
        }
        if (totalBytes <= 0L) totalBytes = 1L
        var copiedBytes = 0L
        var lastP = -1f

        for ((name, target) in wanted) {
          val doc: DocumentFile? = list.firstOrNull { it.name == name && it.isFile }
          if (doc == null) {
            AppLog.w("未找到 $name（目录内无同名文件）")
            missing.add(name)
            continue
          }
          val size: Long = doc.length()
          AppLog.i("开始拷贝 $name（$size 字节）→ ${target.absolutePath}")
          try {
            val input = context.contentResolver.openInputStream(doc.uri)
              ?: error("openInputStream 返回 null")
            input.use { ins ->
              target.parentFile?.mkdirs()
              val tmp = File(target.parentFile, target.name + ".importing")
              tmp.outputStream().use { os ->
                val buf = ByteArray(256 * 1024)
                while (true) {
                  val n = ins.read(buf)
                  if (n <= 0) break
                  os.write(buf, 0, n)
                  copiedBytes += n
                  val p = (copiedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
                  if (p - lastP >= 0.01f) {
                    lastP = p
                    onProgress(p)
                  }
                }
                os.flush()
              }
              if (target.exists()) target.delete()
              if (!tmp.renameTo(target)) error("rename 失败：${tmp.absolutePath} → ${target.name}")
            }
            AppLog.i("拷贝完成 $name → 目标 ${target.length()} 字节")
            imported.add(name)
          } catch (t: Throwable) {
            AppLog.e("拷贝失败 $name", t)
            failed[name] = t.message ?: t.javaClass.simpleName
          }
        }
        Report(imported, missing, failed, list.size, allNames.take(10))
      }
    } catch (t: Throwable) {
      AppLog.e("导入流程异常", t)
      Report(
        emptyList(),
        wanted.keys.toList(),
        wanted.keys.associateWith { t.message ?: t.javaClass.simpleName },
        0,
        emptyList(),
      )
    }
    AppLog.i(
      "importFromTree 结束 imported=${report.imported} missing=${report.missing} " +
        "failed=${report.failed.keys} 目录项数=${report.scannedCount}",
    )
    report
  }

  /**
   * 从 SAF 单文件导入到 [target]（LLM 模型等大文件）。
   * 选中什么就导入什么（不要求文件名，一律落成 target 名）。
   * 返回 null=成功，否则为错误信息。进度按 已拷贝/总字节 汇报（总字节未知时不汇报）。
   */
  suspend fun importFromFile(
    context: Context,
    fileUri: Uri,
    target: File,
    onProgress: (Float) -> Unit = {},
  ): String? = withContext(Dispatchers.IO) {
    try {
      AppLog.i("importFromFile 开始 uri=$fileUri → ${target.absolutePath}")
      val total = runCatching {
        DocumentFile.fromSingleUri(context, fileUri)?.length() ?: 0L
      }.getOrDefault(0L)
      AppLog.i("importFromFile 源大小=${total}B")
      val input = context.contentResolver.openInputStream(fileUri)
        ?: error("openInputStream 返回 null")
      input.use { ins ->
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".importing")
        var copied = 0L
        var lastP = -1f
        tmp.outputStream().use { os ->
          val buf = ByteArray(256 * 1024)
          while (true) {
            val n = ins.read(buf)
            if (n <= 0) break
            os.write(buf, 0, n)
            copied += n
            if (total > 0) {
              val p = (copied.toFloat() / total).coerceIn(0f, 1f)
              if (p - lastP >= 0.01f) {
                lastP = p
                onProgress(p)
              }
            }
          }
          os.flush()
        }
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) error("rename 失败：${tmp.absolutePath} → ${target.name}")
      }
      AppLog.i("importFromFile 完成 → ${target.length()} 字节")
      null
    } catch (t: Throwable) {
      AppLog.e("importFromFile 失败", t)
      t.message ?: t.javaClass.simpleName
    }
  }
}
