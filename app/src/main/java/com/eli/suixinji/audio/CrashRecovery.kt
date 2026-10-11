package com.eli.suixinji.audio

import androidx.documentfile.provider.DocumentFile
import com.eli.suixinji.util.AppLog
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃恢复：扫描 recordings/ 下残留的 .pcm（进程被杀时没来得及转 wav），
 * 逐个补出标准 .wav 并删除 .pcm。保证「程序异常退出，录音文件也在」。
 */
object CrashRecovery {

  data class Result(val recovered: Int, val names: List<String>)

  suspend fun recover(recordingsDir: DocumentFile?, resolver: android.content.ContentResolver): Result {
    if (recordingsDir == null) return Result(0, emptyList())
    val orphans = recordingsDir.listFiles().filter { it.name?.endsWith(".pcm") == true }
    if (orphans.isEmpty()) return Result(0, emptyList())
    AppLog.i("崩溃恢复：发现 ${orphans.size} 个残留 .pcm")
    val names = ArrayList<String>()
    for (pcm in orphans) {
      val base = pcm.name?.removeSuffix(".pcm") ?: continue
      val wavName = "$base.wav"
      if (pcm.length() <= 44L) {
        // 空壳（<1.4ms 音频）：无法也无需恢复，清掉避免每次启动反复重试
        AppLog.w("崩溃恢复：跳过空 .pcm ${pcm.name}（${pcm.length()}B），直接清理")
        runCatching { pcm.delete() }
        continue
      }
      val ok = runCatching { convertPcmToWav(pcm, recordingsDir, wavName, resolver) }
        .onFailure { AppLog.e("崩溃恢复：转换失败 file=$wavName", it) }
        .getOrDefault(false)
      if (ok) {
        runCatching { pcm.delete() }
        names.add(wavName)
        AppLog.i("崩溃恢复：已恢复 $wavName（${pcm.length()}B）")
      } else {
        AppLog.w("崩溃恢复：转换失败，保留 .pcm 下次再试 file=$wavName")
      }
    }
    return Result(names.size, names)
  }

  /** 流式拷贝：写 WAV 头 + 逐块拷 PCM。不做整文件读入（录音可能数百 MB）。 */
  private fun convertPcmToWav(
    pcm: DocumentFile,
    dir: DocumentFile,
    wavName: String,
    resolver: android.content.ContentResolver,
  ): Boolean {
    val pcmSize = pcm.length()
    if (pcmSize <= 44L) return false
    val wav = dir.findFile(wavName)?.takeIf { it.isFile }
      ?: dir.createFile("audio/wav", wavName) ?: return false
    val input = resolver.openInputStream(pcm.uri) ?: return false
    val output: OutputStream = resolver.openOutputStream(wav.uri, "w") ?: run {
      runCatching { input.close() }
      return false
    }
    input.use { ins ->
      output.use { os ->
        os.write(wavHeader(pcmSize))
        val buf = ByteArray(64 * 1024)
        while (true) {
          val n = ins.read(buf)
          if (n <= 0) break
          os.write(buf, 0, n)
        }
        os.flush()
      }
    }
    return true
  }

  /** 标准 44 字节 WAV 头（16kHz / 16bit / mono）。 */
  fun wavHeader(pcmDataSize: Long): ByteArray {
    val totalDataLen = pcmDataSize + 36
    val byteRate = SafeRecorder.SAMPLE_RATE * 2 // mono, 16bit
    val bb = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    fun putStr(s: String) = s.forEach { bb.put(it.code.toByte()) }
    fun putInt(v: Long) { bb.put((v and 0xFF).toByte()); bb.put((v shr 8 and 0xFF).toByte()); bb.put((v shr 16 and 0xFF).toByte()); bb.put((v shr 24 and 0xFF).toByte()) }
    fun putShort(v: Long) { bb.put((v and 0xFF).toByte()); bb.put((v shr 8 and 0xFF).toByte()) }
    putStr("RIFF"); putInt(totalDataLen); putStr("WAVE")
    putStr("fmt "); putInt(16); putShort(1) // PCM
    putShort(1) // mono
    putInt(SafeRecorder.SAMPLE_RATE.toLong()); putInt(byteRate.toLong())
    putShort(2); putShort(16) // block align, bits
    putStr("data"); putInt(pcmDataSize)
    return bb.array()
  }

  fun defaultName(prefix: String = "REC"): String =
    SimpleDateFormat("${prefix}-yyyyMMdd-HHmmss", Locale.US).format(Date())
}
