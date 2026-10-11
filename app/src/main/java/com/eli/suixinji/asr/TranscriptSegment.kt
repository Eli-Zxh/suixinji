package com.eli.suixinji.asr

/** 一段带时间轴的转写片段（VAD 切分后的语音段）。 */
data class TranscriptSegment(
  /** 段起点（相对音频开头，毫秒）。 */
  val startMs: Long,
  /** 段终点（毫秒）。 */
  val endMs: Long,
  /** 段文本（已做逆文本归一化，如数字转汉字）。 */
  val text: String,
)

/** 字幕行（SRT 用）。 */
data class SubtitleCue(
  val startMs: Long,
  val endMs: Long,
  val text: String,
)
