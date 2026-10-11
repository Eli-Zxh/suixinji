package com.eli.suixinji.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.eli.suixinji.util.AppLog

/**
 * 通用音频解码器：把任意系统可解码的音频（wav/mp3/m4a/aac/amr…）
 * 解码、重采样、混缩为 16kHz mono FloatArray 增量块，喂给下游（VAD/ASR）。
 *
 * 用法：
 * ```
 * AudioDecoder.decodeTo16kMono(context, uri) { chunk -> engine.feed(chunk) {...} }
 * ```
 */
object AudioDecoder {

  const val TARGET_RATE = 16_000

  /**
   * 解码整条音轨，分块回调 [onChunk]（每块 ≤ 1 秒）。
   * @return 音频总时长（毫秒）；解码失败抛异常。
   */
  fun decodeTo16kMono(context: Context, uri: Uri, onChunk: (FloatArray) -> Unit): Long {
    val startedAt = android.os.SystemClock.elapsedRealtime()
    val extractor = MediaExtractor()
    try {
      extractor.setDataSource(context, uri, null)
      var trackIndex = -1
      var format: MediaFormat? = null
      for (i in 0 until extractor.trackCount) {
        val f = extractor.getTrackFormat(i)
        val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
        if (mime.startsWith("audio/")) {
          trackIndex = i
          format = f
          break
        }
      }
      require(trackIndex >= 0) { "文件中未找到音频轨" }
      val fmt = format!!
      val srcMime = fmt.getString(MediaFormat.KEY_MIME)!!
      val srcRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
      val channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
      val durationUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
      AppLog.i("开始解码 mime=$srcMime srcRate=$srcRate ch=$channels durationMs=${durationUs / 1000}")

      extractor.selectTrack(trackIndex)
      val codec = MediaCodec.createDecoderByType(srcMime)
      codec.configure(fmt, null, null, 0)
      codec.start()

      // 线性重采样状态
      val resampler = LinearResampler(srcRate, channels, TARGET_RATE)

      val info = MediaCodec.BufferInfo()
      var inputDone = false
      var outputDone = false
      // 解码器输出 PCM 编码：多数设备输出 16bit，部分设备/封装会输出 float，
      // 不能默认按 16bit 读，否则音频全错（会表现为识别结果乱码）
      var pcmEncoding = if (fmt.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
        fmt.getInteger(MediaFormat.KEY_PCM_ENCODING)
      } else {
        AudioFormat.ENCODING_PCM_16BIT
      }
      var chunkCount = 0L
      var fedSamples = 0L
      while (!outputDone) {
        if (!inputDone) {
          val inIdx = codec.dequeueInputBuffer(10_000)
          if (inIdx >= 0) {
            val buf = codec.getInputBuffer(inIdx)!!
            val n = extractor.readSampleData(buf, 0)
            if (n < 0) {
              codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
              inputDone = true
            } else {
              codec.queueInputBuffer(inIdx, 0, n, extractor.sampleTime, 0)
              extractor.advance()
            }
          }
        }
        val outIdx = codec.dequeueOutputBuffer(info, 10_000)
        when {
          outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
            val outFmt = codec.outputFormat
            val outRate = outFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val outCh = outFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val outEnc = if (outFmt.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
              outFmt.getInteger(MediaFormat.KEY_PCM_ENCODING)
            } else {
              AudioFormat.ENCODING_PCM_16BIT
            }
            AppLog.i("输出格式变更 rate=$outRate ch=$outCh pcmEncoding=$outEnc")
            pcmEncoding = outEnc
            resampler.reconfigure(outRate, outCh)
          }
          outIdx >= 0 -> {
            val outBuf = codec.getOutputBuffer(outIdx)!!
            val size = info.size
            if (size > 0) {
              val floats: FloatArray = when (pcmEncoding) {
                AudioFormat.ENCODING_PCM_FLOAT -> {
                  // float32 PCM：部分设备解码器输出该格式，按 4 字节/样本读
                  val fb = outBuf.order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                  val fs = FloatArray(fb.remaining())
                  fb.get(fs)
                  fs
                }
                else -> {
                  // 16bit PCM：(size + 1) / 2 兼容奇数字节长度
                  val shorts = ShortArray((size + 1) / 2)
                  outBuf.order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
                  FloatArray(shorts.size) { shorts[it] / 32768f }
                }
              }
              fedSamples += floats.size.toLong()
              chunkCount++
              resampler.process(floats, onChunk)
            }
            val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
            codec.releaseOutputBuffer(outIdx, false)
            if (eos) outputDone = true
          }
        }
      }
      resampler.finish(onChunk)
      codec.stop()
      codec.release()
      AppLog.i(
        "解码完成 原始样本=$fedSamples 分块=$chunkCount " +
          "耗时=${android.os.SystemClock.elapsedRealtime() - startedAt}ms",
      )
      return durationUs / 1000L
    } catch (t: Throwable) {
      AppLog.e("解码失败 uri=$uri", t)
      throw t
    } finally {
      runCatching { extractor.release() }
    }
  }

  /** 解码到短浮点的同时报告进度（0f..1f）。 */
  fun decodeWithProgress(
    context: Context,
    uri: Uri,
    onProgress: (Float) -> Unit,
    onChunk: (FloatArray) -> Unit,
  ): Long {
    var durationMs = 0L
    var fedUs = 0L
    val wrapped: (FloatArray) -> Unit = { chunk ->
      fedUs += chunk.size.toLong() * 1_000_000L / TARGET_RATE
      onChunk(chunk)
    }
    // 先解一遍拿不到 duration，这里直接用 extractor 探测
    val extractor = MediaExtractor()
    try {
      extractor.setDataSource(context, uri, null)
      for (i in 0 until extractor.trackCount) {
        val f = extractor.getTrackFormat(i)
        if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) {
          durationMs = if (f.containsKey(MediaFormat.KEY_DURATION)) f.getLong(MediaFormat.KEY_DURATION) / 1000L else 0L
          break
        }
      }
    } finally {
      extractor.release()
    }
    if (durationMs > 0) {
      var lastReport = 0f
      val progressWrapped: (FloatArray) -> Unit = { chunk ->
        fedUs += chunk.size.toLong() * 1_000_000L / TARGET_RATE
        val p = (fedUs / 1000f / durationMs).coerceIn(0f, 1f)
        if (p - lastReport >= 0.01f) {
          lastReport = p
          onProgress(p)
        }
        onChunk(chunk)
      }
      return decodeTo16kMonoRaw(context, uri, progressWrapped)
    }
    return decodeTo16kMonoRaw(context, uri, wrapped)
  }

  private fun decodeTo16kMonoRaw(context: Context, uri: Uri, onChunk: (FloatArray) -> Unit): Long =
    decodeTo16kMono(context, uri, onChunk)

  /** 简单线性插值重采样 + 多声道均值混缩。 */
  private class LinearResampler(var srcRate: Int, var srcChannels: Int, private val dstRate: Int) {
    private var carry = FloatArray(0)
    private var pos = 0.0

    fun reconfigure(rate: Int, channels: Int) {
      srcRate = rate
      srcChannels = channels
      pos = 0.0
    }

    fun process(samples: FloatArray, sink: (FloatArray) -> Unit) {
      if (srcChannels > 1) {
        val mono = FloatArray(samples.size / srcChannels)
        for (i in mono.indices) {
          var acc = 0f
          for (c in 0 until srcChannels) acc += samples[i * srcChannels + c]
          mono[i] = acc / srcChannels
        }
        resample(mono, sink)
      } else {
        resample(samples, sink)
      }
    }

    private fun resample(mono: FloatArray, sink: (FloatArray) -> Unit) {
      if (srcRate == dstRate) {
        sink(mono)
        return
      }
      val all = FloatArray(carry.size + mono.size)
      carry.copyInto(all)
      mono.copyInto(all, carry.size)
      val ratio = srcRate.toDouble() / dstRate
      val outSize = (((all.size - 1) - pos) / ratio).toInt().coerceAtLeast(0)
      val out = FloatArray(outSize)
      var o = 0
      while (o < outSize) {
        val idx = pos.toInt()
        val frac = pos - idx
        val s0 = all[idx]
        val s1 = if (idx + 1 < all.size) all[idx + 1] else s0
        out[o++] = (s0 + (s1 - s0) * frac).toFloat()
        pos += ratio
      }
      val consumed = pos.toInt()
      carry = all.copyOfRange(consumed, all.size)
      pos -= consumed
      if (out.isNotEmpty()) sink(out)
    }

    fun finish(sink: (FloatArray) -> Unit) {
      if (carry.isNotEmpty() && srcRate != dstRate) {
        sink(carry)
        carry = FloatArray(0)
      }
    }
  }

  fun supports(mime: String): Boolean = MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(
    MediaFormat.createAudioFormat(mime, TARGET_RATE, 1),
  ) != null
}
