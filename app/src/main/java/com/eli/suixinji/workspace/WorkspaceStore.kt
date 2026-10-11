package com.eli.suixinji.workspace

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.eli.suixinji.data.SettingsStore
import com.eli.suixinji.util.AppLog
import kotlinx.coroutines.flow.firstOrNull

/**
 * workplace 工作目录（SAF 树）读写封装。目录规范（对齐 Obsidian 等主流笔记软件，
 * 便于 WebDAV 按库同步、插件按相对路径回链音频/图片）：
 * ```
 * <workplace>/
 * ├── *.md        笔记（直接放根目录，不建 notes 子目录）
 * ├── audio/      录音 .wav + 同名字幕 .srt（录音与字幕配对存放）
 * └── picture/    笔记插图
 * ```
 */
class WorkspaceStore(private val context: Context, private val settings: SettingsStore) {

  suspend fun treeUri(): Uri? = settings.workplaceTreeUri.firstOrNull()?.let(Uri::parse)

  private suspend fun root(): DocumentFile? {
    val uri = treeUri()
    if (uri == null) {
      AppLog.d("workspace：未设置 workplace 目录")
      return null
    }
    if (!DocumentsContract.isTreeUri(uri)) {
      AppLog.w("workspace：已存 uri 不是树 URI：$uri")
      return null
    }
    val dir = DocumentFile.fromTreeUri(context, uri)
    if (dir == null) {
      AppLog.w("workspace：无法从树 URI 构建 DocumentFile（授权可能已失效）：$uri")
      return null
    }
    if (!dir.canWrite()) {
      AppLog.w("workspace：目录不可写（授权失效或存储被卸载）：$uri")
      return null
    }
    return dir
  }

  suspend fun isReady(): Boolean = root() != null

  suspend fun rootDir(): DocumentFile? = root()

  suspend fun subdir(name: String): DocumentFile? {
    val r = root() ?: return null
    var d = r.findFile(name)
    if (d == null) d = r.createDirectory(name) ?: return null
    return d
  }

  /** audio/：录音 .wav + 字幕 .srt。 */
  suspend fun audioDir(): DocumentFile? = subdir(DIR_AUDIO)

  /** picture/：笔记插图。 */
  suspend fun picturesDir(): DocumentFile? = subdir(DIR_PICTURE)

  /** 在目录下创建/覆盖文本文件，返回其 Uri。 */
  suspend fun writeText(dir: DocumentFile, name: String, mime: String, text: String): Uri? {
    var doc = dir.findFile(name)
    if (doc == null) {
      doc = dir.createFile(mime, name) ?: return null
    }
    context.contentResolver.openOutputStream(doc.uri, "wt")?.use { os ->
      os.write(text.toByteArray(Charsets.UTF_8))
      os.flush()
    } ?: return null
    return doc.uri
  }

  /** 笔记直接写入 workplace 根目录。 */
  suspend fun writeNoteText(name: String, mime: String, text: String): Uri? =
    root()?.let { writeText(it, name, mime, text) }

  /** 列出根目录 .md 笔记，按名字倒序（新在前）。 */
  suspend fun listNotes(): List<DocumentFile> =
    root()?.listFiles()?.filter { it.name?.endsWith(".md") == true }
      ?.sortedByDescending { it.name ?: "" } ?: emptyList()

  /** 列出 audio/ 里的 .srt 字幕。 */
  suspend fun listSrts(): List<DocumentFile> =
    audioDir()?.listFiles()?.filter { it.name?.endsWith(".srt") == true } ?: emptyList()

  /** 列出 audio/ 里的录音（.wav / 崩溃残留 .pcm）。 */
  suspend fun listRecordings(): List<DocumentFile> =
    audioDir()?.listFiles()?.filter {
      it.name?.endsWith(".wav") == true || it.name?.endsWith(".pcm") == true
    } ?: emptyList()

  /** 列出 audio/ 里的 .pcm 孤儿文件（崩溃残留）。 */
  suspend fun listOrphanPcm(): List<DocumentFile> =
    audioDir()?.listFiles()?.filter { it.name?.endsWith(".pcm") == true } ?: emptyList()

  /** 重命名文件（SAF）。 */
  suspend fun rename(file: DocumentFile, newName: String): DocumentFile? {
    return runCatching {
      val newUri = DocumentsContract.renameDocument(context.contentResolver, file.uri, newName)
        ?: return null
      DocumentFile.fromTreeUri(context, newUri) ?: DocumentFile.fromSingleUri(context, newUri)
    }.getOrNull()
  }

  /**
   * 一次性把旧版目录结构迁移到新规范（幂等，启动时调用）：
   * notes 目录下的笔记移到根目录；subtitles、recordings 下的文件移到 audio/；创建 picture/。
   */
  suspend fun migrateLayout() {
    val r = root() ?: return
    r.findFile("notes")?.takeIf { it.isDirectory }?.let { legacy ->
      val mds = legacy.listFiles().filter { it.name?.endsWith(".md") == true }
      if (mds.isNotEmpty()) AppLog.i("目录迁移：notes/ 下 ${mds.size} 个笔记 → 根目录")
      for (f in mds) moveInto(f, r, "根目录")
    }
    val audio = audioDir()
    for (legacyName in listOf("subtitles", "recordings")) {
      if (audio == null) break
      r.findFile(legacyName)?.takeIf { it.isDirectory }?.let { legacy ->
        val files = legacy.listFiles()
        if (files.isNotEmpty()) AppLog.i("目录迁移：$legacyName/ 下 ${files.size} 个文件 → audio/")
        for (f in files) moveInto(f, audio, "audio/")
      }
    }
    val pic = picturesDir()
    AppLog.i("目录迁移完成 audio=${audio != null} picture=${pic != null}")
  }

  private suspend fun moveInto(file: DocumentFile, targetDir: DocumentFile, targetLabel: String) {
    val name = file.name ?: return
    if (targetDir.findFile(name) != null) {
      AppLog.w("目录迁移：目标已存在同名 $name，跳过")
      return
    }
    // 优先 SAF 原子移动
    val moved = runCatching {
      val parentUri = file.parentFile?.uri ?: return@runCatching null
      DocumentsContract.moveDocument(context.contentResolver, file.uri, parentUri, targetDir.uri)
    }.getOrNull()
    if (moved != null) {
      AppLog.i("目录迁移：已移动 $name → $targetLabel")
      return
    }
    // 兜底：流拷贝 + 删除源
    val ok = runCatching {
      val ins = context.contentResolver.openInputStream(file.uri) ?: return@runCatching false
      val target = targetDir.createFile(file.type ?: "application/octet-stream", name)
        ?: return@runCatching false
      val os = context.contentResolver.openOutputStream(target.uri, "w")
        ?: return@runCatching false
      ins.use { i -> os.use { o -> i.copyTo(o, 64 * 1024) } }
      file.delete()
      true
    }.getOrDefault(false)
    if (ok) AppLog.i("目录迁移：已复制 $name → $targetLabel")
    else AppLog.e("目录迁移：移动失败 $name")
  }

  companion object {
    const val DIR_AUDIO = "audio"
    const val DIR_PICTURE = "picture"
  }
}
