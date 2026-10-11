package com.eli.suixinji.audio

import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 录音会话状态（UI 观察）。 */
sealed interface RecordingState {
  data object Idle : RecordingState
  data class Recording(val elapsedMs: Long, val level: Float) : RecordingState
  data object Finalizing : RecordingState
  data class Done(val wavName: String?, val durationMs: Long) : RecordingState
  data class Failed(val message: String) : RecordingState
}

/** 全局录音会话单例：RecordService 写入，UI 观察。 */
object RecordingSession {
  private val _state = MutableStateFlow<RecordingState>(RecordingState.Idle)
  val state: StateFlow<RecordingState> = _state

  @Volatile var pcmDoc: DocumentFile? = null
    private set

  @Volatile var sessionName: String = ""
    private set

  fun begin(pcm: DocumentFile, name: String) {
    pcmDoc = pcm
    sessionName = name
    _state.value = RecordingState.Recording(elapsedMs = 0, level = 0f)
  }

  fun tick(stats: SafeRecorder.Stats) {
    if (_state.value is RecordingState.Recording) {
      _state.value = RecordingState.Recording(elapsedMs = stats.durationMs, level = stats.level)
    }
  }

  fun finish(wavName: String?, durationMs: Long) {
    pcmDoc = null
    sessionName = ""
    _state.value = RecordingState.Done(wavName, durationMs)
  }

  fun fail(message: String) {
    pcmDoc = null
    sessionName = ""
    _state.value = RecordingState.Failed(message)
  }

  fun finalizing() {
    if (_state.value is RecordingState.Recording) {
      _state.value = RecordingState.Finalizing
    }
  }

  fun reset() {
    pcmDoc = null
    sessionName = ""
    _state.value = RecordingState.Idle
  }
}
