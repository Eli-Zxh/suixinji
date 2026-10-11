package com.eli.suixinji.asr

import com.eli.suixinji.util.AppLog
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File

/**
 * 离线转写引擎：silero VAD 切分 + SenseVoice-Small(int8) 识别。
 *
 * - 模型从磁盘加载（assetManager=null 走 newFromFile）。
 * - 音频以 16kHz/mono/float 增量喂入 VAD，VAD 吐出的每个语音段立即送识别，
 *   因而不需要把整段 2h 音频常驻内存（峰值 = 模型 + 单段 ≤ ~15s）。
 * - SenseVoice 结果自带 token 级 timestamps，可直接生成精确到字的 SRT 时间轴。
 */
class SenseVoiceEngine(
  modelDir: File,
  vadModelFile: File,
  language: String = "zh",
  numThreads: Int = DEFAULT_THREADS,
) {
  private val recognizer: OfflineRecognizer
  private val vad: Vad

  init {
    val initStart = System.currentTimeMillis()
    val modelFile = File(modelDir, MODEL_FILE)
    val tokensFile = File(modelDir, TOKENS_FILE)
    require(modelFile.isFile) { "缺少模型文件: ${modelFile.absolutePath}" }
    require(tokensFile.isFile) { "缺少词表文件: ${tokensFile.absolutePath}" }
    require(vadModelFile.isFile) { "缺少 VAD 模型: ${vadModelFile.absolutePath}" }

    recognizer = OfflineRecognizer(
      config = OfflineRecognizerConfig(
        featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
        modelConfig = OfflineModelConfig(
          senseVoice = OfflineSenseVoiceModelConfig(
            model = modelFile.absolutePath,
            language = language,
            useInverseTextNormalization = true,
          ),
          tokens = tokensFile.absolutePath,
          numThreads = numThreads,
          modelType = MODEL_TYPE,
        ),
      ),
    )
    vad = Vad(
      config = VadModelConfig(
        sileroVadModelConfig = SileroVadModelConfig(
          model = vadModelFile.absolutePath,
          threshold = 0.5f,
          minSilenceDuration = 0.4f,
          minSpeechDuration = 0.3f,
          maxSpeechDuration = 12f,
        ),
        sampleRate = SAMPLE_RATE,
        numThreads = 1,
      ),
    )
    AppLog.i(
      "SenseVoiceEngine 就绪 model=$MODEL_FILE vad=$VAD_FILE lang=$language threads=$numThreads " +
        "加载耗时=${System.currentTimeMillis() - initStart}ms",
    )
  }

  /**
   * 流式转写：把 16kHz mono float 音频增量喂进来（推荐每 0.5~2s 一块），
   * 每识别完一个 VAD 语音段回调一次 [onSegment]。
   */
  fun feed(samples: FloatArray, onSegment: (TranscriptSegment) -> Unit) {
    vad.acceptWaveform(samples)
    drainVad(onSegment)
  }

  /** 音频结束后调用：冲掉 VAD 缓冲里最后一段。 */
  fun flush(onSegment: (TranscriptSegment) -> Unit) {
    vad.flush()
    drainVad(onSegment)
  }

  fun release() {
    vad.release()
    recognizer.release()
  }

  private fun drainVad(onSegment: (TranscriptSegment) -> Unit) {
    while (!vad.empty()) {
      val seg = vad.front()
      vad.pop()
      recognizeSegment(seg.start, seg.samples, onSegment)
    }
  }

  private fun recognizeSegment(startSample: Int, samples: FloatArray, onSegment: (TranscriptSegment) -> Unit) {
    if (samples.isEmpty()) return
    val stream = recognizer.createStream()
    stream.acceptWaveform(samples, SAMPLE_RATE)
    recognizer.decode(stream)
    val result = recognizer.getResult(stream)
    val text = result.text.trim()
    stream.release()
    if (text.isEmpty()) return

    val startMs = startSample.toLong() * 1000L / SAMPLE_RATE
    val endMs = startMs + samples.size.toLong() * 1000L / SAMPLE_RATE
    onSegment(TranscriptSegment(startMs = startMs, endMs = endMs, text = text))
  }

  companion object {
    const val SAMPLE_RATE = 16_000
    const val MODEL_FILE = "model.int8.onnx"
    const val TOKENS_FILE = "tokens.txt"
    const val VAD_FILE = "silero_vad.onnx"
    const val MODEL_TYPE = "sense-voice"

    /**
     * ONNX intra-op 线程数默认值：按设备核数自适应（M2.10）。
     * 旧值硬编码 4——骁龙 8 Elite（8 核全性能核）上只用了半数算力；
     * 低端机核少自动回落。上限 8：ORT 线程超过物理核收益归零。
     */
    val DEFAULT_THREADS: Int =
      Runtime.getRuntime().availableProcessors().coerceIn(4, 8)
  }
}
