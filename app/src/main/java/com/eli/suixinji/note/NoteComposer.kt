package com.eli.suixinji.note

import com.eli.suixinji.asr.TranscriptSegment
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 规则版笔记组装器（M1）：
 * 按用户笔记范例的骨架（学科主题标题 → 内容纲要 → 分段实录 → 关键术语 → 自测题）
 * 从转写片段拼出初版 Markdown。后续由本地 LLM（任务：笔记归纳）替换/增强
 * 「内容纲要 / 要点提炼 / 自测题」三个部分。
 */
object NoteComposer {

  /** 口头分节线索：教师常说的分节词。 */
  private val sectionCues = listOf(
    "第一", "第二", "第三", "第四", "第五", "第六", "第七", "第八", "第九", "第十",
    "一、", "二、", "三、", "四、", "五、", "六、", "七、", "八、",
    "首先", "其次", "然后", "接下来", "最后", "我们来看", "我们再看", "下面看", "总结一下", "综上",
  )

  /** 高频实词黑名单（不做关键术语）。 */
  private val stopWords = setOf(
    "我们", "他们", "大家", "这个", "那个", "就是", "所以", "但是", "然后", "因为",
    "如果", "可以", "一个", "一些", "这种", "那种", "什么", "怎么", "那么", "的话",
  )

  data class Options(
    val subject: String = "",
    val topic: String = "",
    val sourceName: String = "",
  )

  fun compose(segments: List<TranscriptSegment>, opts: Options): String {
    val now = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
    val totalMs = segments.lastOrNull()?.endMs ?: 0L
    val title = buildTitle(opts)

    val sb = StringBuilder()
    sb.appendLine("# $title")
    sb.appendLine()
    sb.appendLine("> 来源：${opts.sourceName.ifBlank { "录音" }} · 时长 ${formatDur(totalMs)} · 转写于 $now")
    sb.appendLine()

    sb.appendLine("## 内容纲要")
    val outline = detectOutline(segments)
    if (outline.isEmpty()) {
      sb.appendLine("- （待生成：可由本地大模型根据全文归纳）")
    } else {
      outline.forEach { sb.appendLine("- ${it.label}（${formatDur(it.startMs)}）") }
    }
    sb.appendLine()

    sb.append(transcriptSection(segments))

    sb.appendLine("## 关键术语")
    val terms = topTerms(segments)
    if (terms.isEmpty()) {
      sb.appendLine("- （待生成）")
    } else {
      sb.appendLine(terms.joinToString("、"))
    }
    sb.appendLine()

    sb.appendLine("## 自测与思考")
    sb.appendLine("- [ ] Q1：（待生成：可由本地大模型依据本堂内容出题）")
    sb.appendLine("- [ ] 思考：（本堂内容与前后章节的联系？）")
    sb.appendLine()
    return sb.toString()
  }

  /** 课堂实录小节（规则版与 LLM 版笔记共用，保留原始转写）。 */
  fun transcriptSection(segments: List<TranscriptSegment>): String {
    val sb = StringBuilder()
    sb.appendLine("## 课堂实录")
    val blocks = blockify(segments)
    for (block in blocks) {
      val label = block.label ?: "片段"
      sb.appendLine()
      sb.appendLine("### ${label}（${formatDur(block.startMs)}–${formatDur(block.endMs)}）")
      sb.appendLine()
      sb.appendLine(block.text)
    }
    sb.appendLine()
    return sb.toString()
  }

  private fun buildTitle(opts: Options): String {
    val subject = opts.subject.ifBlank { "课堂笔记" }
    val topic = opts.topic.ifBlank {
      SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
    }
    return "$subject · $topic"
  }

  data class OutlineItem(val label: String, val startMs: Long)

  /** 扫描口头分节线索，输出纲要。 */
  private fun detectOutline(segments: List<TranscriptSegment>): List<OutlineItem> {
    val items = ArrayList<OutlineItem>()
    for (seg in segments) {
      val sentence = seg.text
      val cue = sectionCues.firstOrNull { sentence.contains(it) } ?: continue
      // 取线索后的短语作为小节名（最多 18 字）
      val idx = sentence.indexOf(cue)
      val label = sentence.substring(idx).take(18).trim()
      if (label.length >= 2 && items.none { it.label == label }) {
        items.add(OutlineItem(label, seg.startMs))
      }
      if (items.size >= 12) break
    }
    return items
  }

  /** 按纲要切块；没有纲要时每 ~10 段合一块。 */
  private data class Block(
    val label: String?,
    val startMs: Long,
    val endMs: Long,
    val text: String,
  )

  private fun blockify(segments: List<TranscriptSegment>): List<Block> {
    if (segments.isEmpty()) return emptyList()
    val outline = detectOutline(segments)
    val blocks = ArrayList<Block>()
    if (outline.isEmpty()) {
      val groupSize = 10
      segments.chunked(groupSize).forEach { chunk ->
        blocks.add(
          Block(
            label = null,
            startMs = chunk.first().startMs,
            endMs = chunk.last().endMs,
            text = chunk.joinToString(" ") { it.text },
          ),
        )
      }
      return blocks
    }
    // 依据线索位置切块
    val boundaries = ArrayList<Pair<Long, String>>() // startMs, label
    for (seg in segments) {
      val cue = sectionCues.firstOrNull { seg.text.contains(it) }
      if (cue != null && boundaries.none { it.first == seg.startMs }) {
        val idx = seg.text.indexOf(cue)
        boundaries.add(seg.startMs to seg.text.substring(idx).take(18).trim())
      }
    }
    var bi = 0
    var buf = StringBuilder()
    var bStart = segments.first().startMs
    var bLabel: String? = null
    for (seg in segments) {
      if (bi < boundaries.size && seg.startMs == boundaries[bi].first && buf.isNotBlank()) {
        blocks.add(Block(bLabel, bStart, seg.startMs, buf.toString().trim()))
        buf = StringBuilder()
        bStart = seg.startMs
        bLabel = boundaries[bi].second
        bi++
      }
      if (buf.isBlank()) {
        bStart = seg.startMs
        if (bLabel == null && bi < boundaries.size && boundaries[bi].first == seg.startMs) {
          bLabel = boundaries[bi].second
          bi++
        }
      }
      buf.append(seg.text).append(' ')
    }
    blocks.add(Block(bLabel, bStart, segments.last().endMs, buf.toString().trim()))
    return blocks
  }

  /** 高频术语：2–6 字中文词频 Top12（无分词的朴素 bigram/trigram 统计）。 */
  private fun topTerms(segments: List<TranscriptSegment>): List<String> {
    val text = segments.joinToString("") { it.text }
    if (text.length < 20) return emptyList()
    val freq = HashMap<String, Int>()
    for (n in 2..4) {
      for (i in 0..text.length - n) {
        val w = text.substring(i, i + n)
        if (w.any { it !in '一'..'鿿' }) continue // 只统计汉字
        if (w in stopWords) continue
        freq[w] = (freq[w] ?: 0) + 1
      }
    }
    // 过滤子串重复：保留出现次数最高的
    return freq.entries
      .filter { it.value >= 4 }
      .sortedByDescending { it.value * it.key.length }
      .take(24)
      .map { it.key }
      .distinctBy { w -> freq.entries.firstOrNull { it.key != w && it.key.contains(w) && it.value >= freq[w]!! }?.key ?: w }
      .take(12)
  }

  fun formatDur(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(Locale.US, h, m, s) else "%02d:%02d".format(Locale.US, m, s)
  }
}
