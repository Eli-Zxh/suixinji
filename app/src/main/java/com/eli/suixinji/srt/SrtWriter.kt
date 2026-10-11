package com.eli.suixinji.srt

import com.eli.suixinji.asr.SubtitleCue
import com.eli.suixinji.asr.TranscriptSegment
import java.util.Locale

/** TranscriptSegment 列表 → 标准 SRT 字幕文本。 */
object SrtWriter {

  /** 单行字幕最大字符数（中文按 1 计）。 */
  private const val MAX_LINE_CHARS = 22

  /**
   * 录音结束时刻标记（srt 尾部独立一行）。SRT 无标准注释语法，`; ` 起始行
   * 各解析器普遍忽略；本应用 SrtParser 也天然跳过（该块无时间轴行）。
   */
  private const val REC_END_MARK = "; suixinji:recording-end="
  private val recEndRegex = Regex("; suixinji:recording-end=(\\d+)")

  /** 句末标点：在此切行最自然。 */
  private val lineBreakAfter = charArrayOf('。', '！', '？', '；', '，', '、', ' ')
  private val sentenceEnd = charArrayOf('。', '！', '？', '；', ' ')

  fun fromSegments(segments: List<TranscriptSegment>): List<SubtitleCue> {
    val cues = ArrayList<SubtitleCue>(segments.size)
    for (seg in segments) {
      splitIntoCues(seg).forEach { cues.add(it) }
    }
    return cues
  }

  /**
   * 渲染字幕。recordingEndMs > 0 时在末尾追加录音结束时刻标记行（epoch ms）——
   * ASR 阶段从源音频读出，随 srt 传递给下游（照片插图按录音时段匹配，
   * 不依赖音频文件位置）。
   */
  fun render(cues: List<SubtitleCue>, recordingEndMs: Long = 0L): String {
    val sb = StringBuilder()
    cues.forEachIndexed { i, cue ->
      sb.append(i + 1).append('\n')
      sb.append(formatTs(cue.startMs)).append(" --> ").append(formatTs(cue.endMs)).append('\n')
      sb.append(cue.text.trim()).append("\n\n")
    }
    if (recordingEndMs > 0) sb.append(REC_END_MARK).append(recordingEndMs).append('\n')
    return sb.toString()
  }

  /** 从 srt 原文提取录音结束时刻（epoch ms）；无标记返回 0。 */
  fun extractRecordingEndMs(raw: String): Long {
    for (line in raw.lineSequence()) {
      val m = recEndRegex.matchEntire(line.trim()) ?: continue
      return m.groupValues[1].toLongOrNull() ?: 0L
    }
    return 0L
  }

  /**
   * 把一个语音段切成多行字幕。SenseVoice 只给整段文本（token 时间戳在部分
   * 版本可用），这里按「段内均匀插值 + 标点优先切行」生成自然的时间轴。
   */
  private fun splitIntoCues(seg: TranscriptSegment): List<SubtitleCue> {
    val text = seg.text.trim()
    if (text.isEmpty()) return emptyList()
    val dur = (seg.endMs - seg.startMs).coerceAtLeast(1L)
    val lines = softWrap(text, MAX_LINE_CHARS)
    // 按字符数占比分配时间
    val totalChars = lines.sumOf { it.length }.coerceAtLeast(1)
    var cursor = seg.startMs
    val cues = ArrayList<SubtitleCue>(lines.size)
    for ((i, line) in lines.withIndex()) {
      val share = line.length.toLong() * dur / totalChars
      val isLast = i == lines.lastIndex
      val start = cursor
      val end = if (isLast) seg.endMs else (cursor + share).coerceAtMost(seg.endMs)
      cues.add(SubtitleCue(startMs = start, endMs = end, text = line))
      cursor = end
    }
    return cues
  }

  /** 软换行：优先在句末标点切，超长再在逗号/顿号切，最后硬切。 */
  private fun softWrap(text: String, maxChars: Int): List<String> {
    val out = ArrayList<String>()
    var rest = text
    while (rest.length > maxChars) {
      val head = rest.take(maxChars)
      var cut = -1
      for (i in head.indices.reversed().take(maxChars / 2)) {
        if (head[i] in lineBreakAfter) {
          cut = i + 1
          break
        }
      }
      if (cut <= 0) cut = maxChars
      out.add(rest.take(cut).trim())
      rest = rest.drop(cut).trim()
    }
    if (rest.isNotEmpty()) out.add(rest)
    return out.filter { it.isNotEmpty() }
  }

  private fun sentenceEnd(c: Char): Boolean = c in sentenceEnd

  fun formatTs(ms: Long): String {
    val totalSec = ms.coerceAtLeast(0L) / 1000.0
    val h = (totalSec / 3600).toInt()
    val m = ((totalSec - h * 3600) / 60).toInt()
    val s = (totalSec - h * 3600 - m * 60).toInt()
    val msPart = (ms % 1000).toInt()
    return String.format(Locale.US, "%02d:%02d:%02d,%03d", h, m, s, msPart)
  }

  @Suppress("unused")
  private fun isSentenceEnd(c: Char): Boolean = sentenceEnd(c)
}
