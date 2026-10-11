package com.eli.suixinji.srt

import com.eli.suixinji.asr.TranscriptSegment

/** 标准 SRT → TranscriptSegment 列表（从历史字幕重建归纳输入，不重跑识别）。 */
object SrtParser {

  private val tsRegex = Regex(
    "(\\d+):(\\d+):(\\d+)[,.](\\d+)\\s*-->\\s*(\\d+):(\\d+):(\\d+)[,.](\\d+)",
  )
  private val indexLine = Regex("^\\d+$")

  fun parse(raw: String): List<TranscriptSegment> {
    val text = raw.replace("\r\n", "\n")
    val segments = ArrayList<TranscriptSegment>()
    for (block in text.split(Regex("\n{2,}"))) {
      val lines = block.lines().filter { it.isNotBlank() }
      if (lines.size < 2) continue
      val tsLine = lines.firstOrNull { it.contains("-->") } ?: continue
      val m = tsRegex.find(tsLine) ?: continue
      val start = toMs(m.groupValues[1], m.groupValues[2], m.groupValues[3], m.groupValues[4])
      val end = toMs(m.groupValues[5], m.groupValues[6], m.groupValues[7], m.groupValues[8])
      val body = lines
        .filter { it != tsLine && !indexLine.matches(it.trim()) }
        .joinToString("")
        .trim()
      if (body.isNotEmpty() && end >= start) {
        segments.add(TranscriptSegment(startMs = start, endMs = end, text = body))
      }
    }
    return segments
  }

  private fun toMs(h: String, m: String, s: String, ms: String): Long =
    h.toLong() * 3_600_000L + m.toLong() * 60_000L + s.toLong() * 1000L + ms.toLong()
}
