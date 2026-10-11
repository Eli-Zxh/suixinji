package com.eli.suixinji.workspace

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.documentfile.provider.DocumentFile
import androidx.exifinterface.media.ExifInterface
import com.eli.suixinji.srt.SrtParser
import com.eli.suixinji.srt.SrtWriter
import com.eli.suixinji.util.AppLog
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 照片插图（独立步骤，不在归纳管线内）：
 * 1. [findCandidates] 按录音时段扫相册候选——时段取 srt 尾部的录音时间标记
 *    （ASR 阶段从源音频读出、随 srt 传递；老字幕无标记时退回 audio/ 下同名录音
 *    文件的 mtime），窗口 = [录音结束 - 字幕时长, 录音结束]。不依赖音频文件位置。
 * 2. UI 弹窗让用户过目勾选确认；也可手动从相册选照片（[insertManualPhotos]）。
 * 3. [importAndInsert] 把照片复制进 picture/，按 [placePhotos] 定位：
 *    拍摄时刻 → 录音内偏移 → 时间锚命中/最近的小节标题前；时间对不上 → 追加文末。
 * 图片内容始终不传给 AI，仅在本地文件层操作。
 */
object PhotoMatcher {

  private val IMG_EXTS = setOf("jpg", "jpeg", "png", "webp", "heic")

  data class MatchedPhoto(val file: File, val takenAt: Long)

  /** 候选扫描结果：照片 + 录音时段（epoch ms；窗口未知时为 0，插图定位会全部退到文末）。 */
  data class ScanResult(
    val photos: List<MatchedPhoto> = emptyList(),
    val windowStart: Long = 0L,
    val windowEnd: Long = 0L,
  )

  /** 是否已授予「所有文件访问」。 */
  fun hasAllFilesAccess(): Boolean =
    Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()

  /**
   * 扫描录音时段内的候选照片（按拍摄时间升序）。
   * 录音时段 = [recordingEnd - 字幕末段endMs, recordingEnd]；recordingEnd 优先取
   * srt 标记（[SrtWriter.extractRecordingEndMs]），无标记退回 audio/ 同名录音文件 mtime。
   * 即使没有候选，只要能确定时段也返回窗口（手动选照片定位要用）。
   */
  suspend fun findCandidates(context: Context, ws: WorkspaceStore, stem: String): ScanResult =
    withContext(Dispatchers.IO) {
      if (!hasAllFilesAccess()) {
        AppLog.w("照片插图：未授予所有文件访问权限，无法扫描相册")
        return@withContext ScanResult()
      }
      val audio = ws.audioDir() ?: return@withContext ScanResult()
      val docs = audio.listFiles() ?: return@withContext ScanResult()

      val srtText = docs.firstOrNull { it.name == "$stem.srt" }?.let {
        context.contentResolver.openInputStream(it.uri)?.use { s ->
          s.readBytes().toString(Charsets.UTF_8)
        }
      }
      if (srtText == null) {
        AppLog.w("照片插图：audio/ 下找不到 $stem.srt")
        return@withContext ScanResult()
      }
      val durationMs = SrtParser.parse(srtText).lastOrNull()?.endMs ?: 0L
      if (durationMs <= 0) {
        AppLog.w("照片插图：无法从 srt 确定录音时长（缺失或为空）")
        return@withContext ScanResult()
      }

      // 录音结束时刻：优先 srt 标记；老字幕无标记退回同名录音文件 mtime（wav/m4a/…）
      var recEnd = SrtWriter.extractRecordingEndMs(srtText)
      if (recEnd <= 0) {
        recEnd = docs.firstOrNull {
          it.name != "$stem.srt" && it.name?.substringBeforeLast('.') == stem
        }?.lastModified() ?: 0L
        if (recEnd > 0) {
          AppLog.w("照片插图：老字幕无录音时间标记，退回录音文件 mtime（重新转写一次可写入标记）")
        }
      }
      if (recEnd <= 0) {
        AppLog.w("照片插图：srt 无录音时间标记且 audio/ 下无同名录音文件，无法确定时段")
        return@withContext ScanResult()
      }

      val recStart = recEnd - durationMs
      AppLog.i(
        "照片插图：扫描候选 stem=$stem 时段=${recStart / 1000}~${recEnd / 1000}" +
          "（${durationMs / 60000} 分钟）",
      )
      val matched = scanPhotosInWindow(recStart, recEnd)
      AppLog.i("照片插图：候选 ${matched.size} 张")
      ScanResult(matched, recStart, recEnd)
    }

  /**
   * 把用户确认的候选照片插入笔记（自动匹配路径；窗口来自 [findCandidates]）。
   * 返回实际新插入张数。
   */
  suspend fun insertPhotos(
    context: Context,
    ws: WorkspaceStore,
    mdName: String,
    photos: List<MatchedPhoto>,
    windowStart: Long,
    windowEnd: Long,
  ): Int = importAndInsert(
    context, ws, mdName, windowStart, windowEnd,
    photos.map { p ->
      ImportItem(p.takenAt, extOf(p.file.name), p.file.name) { p.file.inputStream() }
    },
  )

  /**
   * 手动选择的照片（SAF picker 返回的 uri）插入笔记：EXIF 取拍摄时刻
   * （取不到退当前时刻）。窗口未知（无 srt/无标记）时图片统一退到文末。
   */
  suspend fun insertManualPhotos(
    context: Context,
    ws: WorkspaceStore,
    mdName: String,
    uris: List<Uri>,
    windowStart: Long = 0L,
    windowEnd: Long = 0L,
  ): Int = withContext(Dispatchers.IO) {
    val items = uris.mapNotNull { uri ->
      val name = DocumentFile.fromSingleUri(context, uri)?.name
        ?: uri.lastPathSegment ?: "photo"
      val ext = extOf(name)
      if (ext !in IMG_EXTS) {
        AppLog.w("照片插图：跳过非图片文件 $name")
        return@mapNotNull null
      }
      val takenAt = runCatching {
        context.contentResolver.openInputStream(uri)?.use { ins ->
          takenAtOfStream(BufferedInputStream(ins))
        }
      }.getOrNull() ?: System.currentTimeMillis()
      ImportItem(takenAt, ext, name) { context.contentResolver.openInputStream(uri) }
    }
    importAndInsert(context, ws, mdName, windowStart, windowEnd, items)
  }

  /** 待导入照片：拍摄时刻 + 扩展名 + 显示名 + 打开流的方式（自动/手动共用导入核心）。 */
  private class ImportItem(
    val takenAt: Long,
    val ext: String,
    val displayName: String,
    val open: (Context) -> InputStream?,
  )

  /**
   * 导入核心：复制进 picture/（名字 `<stem>-<拍摄时刻>-<序号>.<ext>`，天然幂等），
   * 统一按拍摄时刻升序编号，再按 [placePhotos] 定位插入。
   */
  private suspend fun importAndInsert(
    context: Context,
    ws: WorkspaceStore,
    mdName: String,
    windowStart: Long,
    windowEnd: Long,
    items: List<ImportItem>,
  ): Int = withContext(Dispatchers.IO) {
    if (items.isEmpty()) return@withContext 0
    val stem = mdName.substringBeforeLast('.')
    val root = ws.rootDir() ?: return@withContext 0
    val doc = root.listFiles().firstOrNull { it.name == mdName } ?: return@withContext 0
    val old = context.contentResolver.openInputStream(doc.uri)?.use {
      it.readBytes().toString(Charsets.UTF_8)
    } ?: return@withContext 0
    val dir = ws.picturesDir() ?: return@withContext 0

    val tsFmt = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
    val imported = mutableListOf<Pair<Long, String>>() // 拍摄时刻 → picture/ 相对路径
    items.sortedBy { it.takenAt }.forEachIndexed { idx, item ->
      try {
        val name = "$stem-${tsFmt.format(Date(item.takenAt))}-${idx + 1}.${item.ext}"
        if (old.contains("picture/$name")) return@forEachIndexed
        val target = dir.createFile(mimeOf(item.ext), name) ?: return@forEachIndexed
        item.open(context)?.use { ins ->
          context.contentResolver.openOutputStream(target.uri, "w")?.use { o ->
            ins.copyTo(o, 64 * 1024)
          }
        }
        imported.add(item.takenAt to "${WorkspaceStore.DIR_PICTURE}/$name")
        AppLog.i("照片插图：导入 $name（来自 ${item.displayName}）")
      } catch (t: Throwable) {
        AppLog.e("照片插图：导入失败 ${item.displayName}", t)
      }
    }
    if (imported.isEmpty()) return@withContext 0

    val newText = placePhotos(old, imported, windowStart, windowEnd)
    ws.writeText(root, mdName, "text/markdown", newText)
    AppLog.i("照片插图：完成，插入 ${imported.size} 张到 $mdName")
    imported.size
  }

  /** 扫描 DCIM 与 Pictures，返回拍摄时间在窗口内的照片（按时间升序）。 */
  fun scanPhotosInWindow(windowStart: Long, windowEnd: Long): List<MatchedPhoto> {
    val roots = listOf(
      Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
      Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
    )
    val out = mutableListOf<MatchedPhoto>()
    for (root in roots) {
      if (!root.isDirectory) continue
      walk(root, depth = 0, out = out)
    }
    return out
      .filter { it.takenAt in windowStart..windowEnd }
      .sortedBy { it.takenAt }
  }

  /**
   * 按录音时间把图片定位到笔记小节（M2.5 用户确认规则）：
   * - 拍摄时刻换算成录音内偏移 offset = takenAt - windowStart；
   *   时间不一致（窗口未知 / 拍摄时刻落在 [录音起, 录音止] 之外）→ 追加文末；
   * - 小节带时间锚 `<!-- t=起-止 -->`：包含 offset 的小节优先，否则取时间距离最近的小节，
   *   插在该节 `## ` 标题行之前；
   * - 小节无时间锚：按时间比例（offset/录音时长）对应正文文字比例位置，取最近的 `## ` 标题前插入；
   * - 无 `## ` 标题：全部追加文末。
   * photos = (拍摄时刻, picture/相对路径)，调用前已按拍摄时刻升序。
   */
  fun placePhotos(
    md: String,
    photos: List<Pair<Long, String>>,
    windowStart: Long,
    windowEnd: Long,
  ): String {
    if (photos.isEmpty()) return md
    val lines = md.lines().toMutableList()
    // 小节标题：`## ` 与 `### ` 都算——归纳稿的正文小节是 ### 级，`## ` 只有摘要/自测
    // （M2.6 修正：只认 `## ` 时全部正文小节落在「摘要」节内，锚定位全错）
    val headings = lines.indices.filter {
      val t = lines[it].trimStart()
      t.startsWith("## ") || t.startsWith("### ")
    }
    if (headings.isEmpty()) return appendAtEnd(md, photos.map { it.second })

    // 时间锚归属：<!-- t=起-止 --> 写在其小节正文之前，归属其后最近的小节标题；每节取首个锚
    // （M2.6 修正：旧逻辑在标题之后找锚，而锚在标题之前 → 永远找不到/错位）
    val anchorRegex = Regex("<!--\\s*t=(\\d+)-(\\d+)\\s*-->")
    data class Section(val heading: Int, val startMs: Long, val endMs: Long)
    val anchorSections = ArrayList<Section>()
    for (k in lines.indices) {
      val m = anchorRegex.find(lines[k]) ?: continue
      val h = headings.firstOrNull { it > k } ?: continue
      if (anchorSections.any { it.heading == h }) continue
      anchorSections.add(Section(h, m.groupValues[1].toLong(), m.groupValues[2].toLong()))
    }
    anchorSections.sortBy { it.heading }

    // 无锚小节时用文字比例：各标题行前累计字符 / 全文字符
    val totalChars = lines.sumOf { it.length.toLong() }.coerceAtLeast(1L)
    fun headingFraction(h: Int): Double {
      var cum = 0L
      for (i in 0 until h) cum += lines[i].length
      return cum.toDouble() / totalChars
    }

    val atHeading = mutableMapOf<Int, MutableList<String>>()
    val bottom = mutableListOf<String>()
    val duration = (windowEnd - windowStart).coerceAtLeast(1L)

    for ((takenAt, path) in photos) {
      val offset = takenAt - windowStart
      if (windowStart <= 0 || offset < 0 || takenAt > windowEnd) {
        bottom.add(path)
        continue
      }
      if (anchorSections.isNotEmpty()) {
        val target = anchorSections.firstOrNull { offset >= it.startMs && offset <= it.endMs }
          ?: anchorSections.minByOrNull { s -> distTo(offset, s.startMs, s.endMs) }!!
        atHeading.getOrPut(target.heading) { mutableListOf() }.add(path)
      } else {
        val f = offset.toDouble() / duration
        val best = headings.minByOrNull { h -> abs(headingFraction(h) - f) }!!
        atHeading.getOrPut(best) { mutableListOf() }.add(path)
      }
    }

    // 行号降序插入避免位移；时间对不上的统一追加文末
    for (pos in atHeading.keys.sortedDescending()) {
      lines.addAll(pos, atHeading.getValue(pos).map { "![photo]($it)" })
    }
    if (bottom.isEmpty()) return lines.joinToString("\n")
    val sb = StringBuilder(lines.joinToString("\n").trimEnd())
    sb.append("\n\n")
    sb.append(bottom.joinToString("\n") { "![photo]($it)" })
    sb.append('\n')
    return sb.toString()
  }

  // ---- 内部 ----

  private fun appendAtEnd(md: String, paths: List<String>): String =
    md.trimEnd() + "\n\n" + paths.joinToString("\n") { "![photo]($it)" } + "\n"

  /** x 到区间 [a, b] 的距离（区间内为 0）。 */
  private fun distTo(x: Long, a: Long, b: Long): Long = when {
    x < a -> a - x
    x > b -> x - b
    else -> 0L
  }

  private fun walk(dir: File, depth: Int, out: MutableList<MatchedPhoto>) {
    if (depth > 3) return
    val files = dir.listFiles() ?: return
    for (f in files) {
      if (f.isDirectory) walk(f, depth + 1, out)
      else if (f.isFile && f.extension.lowercase() in IMG_EXTS) {
        out.add(MatchedPhoto(f, photoTakenAt(f)))
      }
    }
  }

  /** 拍摄时间：优先 EXIF DateTimeOriginal，退回文件修改时间。 */
  private fun photoTakenAt(f: File): Long {
    try {
      val exif = ExifInterface(f.absolutePath)
      val raw = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
        ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
      if (!raw.isNullOrBlank()) {
        val parsed = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(raw)
        if (parsed != null) return parsed.time
      }
    } catch (_: Exception) {
      // EXIF 读取失败退回 mtime
    }
    return f.lastModified()
  }

  /** 流式照片的拍摄时间：EXIF DateTimeOriginal / DateTime，读不到返回 null。 */
  private fun takenAtOfStream(ins: InputStream): Long? = try {
    val exif = ExifInterface(ins)
    val raw = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
      ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
    raw?.takeIf { it.isNotBlank() }?.let {
      SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(it)?.time
    }
  } catch (_: Exception) {
    null
  }

  private fun extOf(name: String): String =
    name.substringAfterLast('.', "").lowercase().let { if (it == "jpeg") "jpg" else it }

  private fun mimeOf(ext: String): String = when (ext) {
    "png" -> "image/png"
    "webp" -> "image/webp"
    "heic" -> "image/heic"
    else -> "image/jpeg"
  }
}
