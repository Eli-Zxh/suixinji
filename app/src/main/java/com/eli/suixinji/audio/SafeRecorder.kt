package com.eli.suixinji.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.documentfile.provider.DocumentFile
import com.eli.suixinji.util.AppLog
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.sqrt

/**
 * 崩溃安全录音器。
 *
 * 可靠性设计（用户硬性要求：程序异常退出也必须保留录音）：
 * 1. 音频直接写入 workplace/recordings/<时间戳>.pcm（SAF 文件描述符），
 *    每 100ms 一块即时写盘、无内存缓冲 —— 进程被杀最多丢最后 100ms。
 * 2. 录音期间磁盘上始终存在完整可用的原始 PCM；结束后再「头 + 流拷贝」
 *    生成标准 .wav，成功后删除 .pcm。
 * 3. 若进程中途死亡，残留 .pcm 由 [CrashRecovery] 在下次启动时补出 .wav。
 */
class SafeRecorder(
  private val pcmDoc: DocumentFile,
  private val resolver: android.content.ContentResolver,
) {
  data class Stats(val durationMs: Long, val level: Float)

  private val running = AtomicBoolean(false)
  private var threadRef: Thread? = null
  @Volatile private var lastStats = Stats(0L, 0f)

  /** 启动录音。返回 false 表示启动失败（权限/设备占用）。 */
  @SuppressLint("MissingPermission") // 调用方已检查 RECORD_AUDIO
  fun start(): Boolean {
    if (!running.compareAndSet(false, true)) return false
    val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    if (minBuf <= 0) {
      running.set(false)
      return false
    }
    val record = try {
      AudioRecord(
        MediaRecorder.AudioSource.VOICE_RECOGNITION,
        SAMPLE_RATE,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
        maxOf(minBuf * 4, SAMPLE_RATE), // ≥1s 缓冲
      )
    } catch (_: Throwable) {
      running.set(false)
      return false
    }
    if (record.state != AudioRecord.STATE_INITIALIZED) {
      record.release()
      running.set(false)
      return false
    }
    val out = resolver.openOutputStream(pcmDoc.uri, "w") ?: run {
      record.release()
      running.set(false)
      return false
    }

    AppLog.i("录音线程启动 pcm=${pcmDoc.name} minBuf=$minBuf")
    threadRef = thread(name = "suixinji-recorder") {
      record.startRecording()
      val chunkBytes = SAMPLE_RATE / 10 * 2 // 100ms @16k mono 16bit = 3200B
      val buf = ByteArray(chunkBytes)
      var written = 0L
      var readErrors = 0
      try {
        while (running.get()) {
          val n = record.read(buf, 0, chunkBytes)
          if (n <= 0) {
            // 读失败：连续 ~3 秒仍失败则判定麦克风异常，主动退出，避免空转
            readErrors++
            if (readErrors >= 30) {
              AppLog.e("录音读取连续失败 $readErrors 次（bytes=$written），终止录音线程")
              break
            }
            continue
          }
          readErrors = 0
          out.write(buf, 0, n) // 直写 fd，无缓冲
          written += n
          lastStats = Stats(written * 1000L / (SAMPLE_RATE * 2), rms(buf, n))
        }
      } catch (t: Throwable) {
        // 写盘/设备异常：保住已写数据后退出循环。必须清 running，否则上层轮询永远认为还在录音
        AppLog.e("录音线程异常（已写入 bytes=$written）", t)
      } finally {
        running.set(false)
        runCatching { record.stop() }
        runCatching { record.release() }
        runCatching { out.flush() }
        runCatching { out.close() }
        AppLog.i("录音线程结束 bytes=$written ≈${written / (SAMPLE_RATE * 2)}s")
      }
    }
    return true
  }

  fun stop(): Stats {
    running.set(false)
    threadRef?.join(3_000)
    threadRef = null
    return lastStats
  }

  fun isRunning(): Boolean = running.get()

  fun currentStats(): Stats = lastStats

  private fun rms(buf: ByteArray, n: Int): Float {
    var acc = 0.0
    var i = 0
    while (i + 1 < n) {
      val v = ((buf[i].toInt() and 0xFF) or (buf[i + 1].toInt() shl 8)).toShort().toInt()
      acc += v.toDouble() * v
      i += 2
    }
    val r = sqrt(acc / (n / 2)) / 32768.0
    return (r * 4.0).coerceIn(0.0, 1.0).toFloat()
  }

  companion object {
    const val SAMPLE_RATE = 16_000
  }
}
