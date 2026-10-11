package com.eli.suixinji.ui.screens

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.documentfile.provider.DocumentFile
import com.eli.suixinji.asr.SenseVoiceEngine
import com.eli.suixinji.asr.TranscriptSegment
import com.eli.suixinji.audio.AudioDecoder
import com.eli.suixinji.data.SettingsStore
import com.eli.suixinji.srt.SrtWriter
import com.eli.suixinji.task.TaskNotify
import com.eli.suixinji.util.AppLog
import com.eli.suixinji.util.TaskTrace
import com.eli.suixinji.workspace.WorkspaceStore
import kotlinx.coroutines.flow.firstOrNull
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 转写流程状态机。 */
sealed interface TranscribePhase {
  data object Idle : TranscribePhase
  data class Decoding(val progress: Float) : TranscribePhase
  data class Recognizing(val segmentCount: Int, val elapsedMs: Long) : TranscribePhase
  data object Saving : TranscribePhase
  data class Done(
    val srtName: String,
    val durationMs: Long,
    val segmentCount: Int,
    val transcribeMs: Long,
  ) : TranscribePhase

  data class Failed(val message: String) : TranscribePhase
}

/**
 * 转写控制器：选文件（支持批量）→ 解码 → VAD+SenseVoice → 字幕写入 audio/。
 * 转写只产出 srt（不生成 md 笔记）；md 笔记由 AI 归纳从 srt 生成。
 *
 * 录音结束时刻在本阶段从源音频读出（导入音频=源文件 mtime，应用内录音=wav 收尾
 * mtime），记入 srt 尾部标记随链路传递——照片插图按「srt 里的录音时段」匹配候选，
 * 不依赖音频文件位置（导入的源文件留在原处，位置不固定）。
 *
 * 等待管线（M2.6）：转写请求先进队列（[queue]），单个消耗线程按顺序消化——
 * 归纳批次运行中也能继续排转写批次，互不阻塞（ASR 与 LLM 引擎独立、可同驻内存）。
 */
class TranscribeController(
  private val context: Context,
  private val scope: CoroutineScope,
  private val settings: SettingsStore,
  private val ws: WorkspaceStore,
) {
  var phase by mutableStateOf<TranscribePhase>(TranscribePhase.Idle)
    private set

  /** 已出结果计数（本轮队列累计处理到第 x 个）。 */
  var batchIndex by mutableStateOf(0)
    private set
  var batchTotal by mutableStateOf(0)
    private set

  /** 等待管线：队列中还没轮到的数量。 */
  var pendingCount by mutableStateOf(0)
    private set

  /** 是否正在消化队列。 */
  var queueRunning by mutableStateOf(false)
    private set

  /**
   * 本轮已成功转写的 stem 列表（批量转写完成后供「批量归纳本次 N 篇」一键接续）。
   * 新批量开始入队时清空；用户点「完成」后保留，方便回转写页补归纳。
   */
  var batchStems by mutableStateOf<List<String>>(emptyList())
    private set

  private val queue = ArrayDeque<Pair<Uri, String?>>() // uri → 期望 stem（null=从 uri 取名）
  private val enqueueMutex = Mutex()
  @Volatile private var currentUri: Uri? = null        // 正在转写的 uri（入队去重用）

  fun modelReady(): Boolean {
    val dir = File(context.filesDir, MODELS_DIR)
    return File(dir, SenseVoiceEngine.MODEL_FILE).isFile &&
      File(dir, SenseVoiceEngine.TOKENS_FILE).isFile &&
      File(context.filesDir, VAD_PATH).isFile
  }

  fun isBusy(): Boolean =
    queueRunning || pendingCount > 0 ||
      (phase !is TranscribePhase.Idle && phase !is TranscribePhase.Done && phase !is TranscribePhase.Failed)

  /** 单文件转写（排队执行）。 */
  fun transcribe(uri: Uri, baseName: String?) = enqueue(listOf(uri to baseName))

  /** 批量转写：全部进队列，按顺序逐个处理，单个失败不中断。 */
  fun transcribeMany(uris: List<Uri>) = enqueue(uris.map { it to null })

  /** 转写最近一次录音（按文件名在 audio/ 下定位）。 */
  fun transcribeRecording(wavName: String) {
    scope.launch {
      val doc = ws.audioDir()?.listFiles()?.firstOrNull { it.name == wavName }
      if (doc == null) {
        AppLog.e("转写入队失败：audio/ 下找不到 $wavName")
        phase = TranscribePhase.Failed("找不到录音文件：$wavName")
        return@launch
      }
      enqueue(listOf(doc.uri to wavName))
    }
  }

  /** 入队并确保队列在消化。 */
  private fun enqueue(items: List<Pair<Uri, String?>>) {
    if (items.isEmpty()) return
    scope.launch {
      enqueueMutex.withLock {
        // 去重：正在转写的与已排队的不再重复入队
        val fresh = items.filter { (uri, _) -> uri != currentUri && queue.none { it.first == uri } }
        if (fresh.isNotEmpty() && !queueRunning) batchStems = emptyList() // 新一批：重置「本次转写」清单
        queue.addAll(fresh)
        pendingCount = queue.size
        fresh.size
      }.let { added ->
        if (added > 0) AppLog.i("转写队列：+$added 个，待处理 $pendingCount")
      }
      drainQueue()
    }
  }

  /** 单消费者消化队列：running 标志在首个挂起点前置位（Main 调度器顺序执行），保证唯一。 */
  private suspend fun drainQueue() {
    if (queueRunning) return
    queueRunning = true
    try {
      TaskNotify.begin(context, "离线转写")
      while (true) {
        val item = enqueueMutex.withLock {
          val head = queue.removeFirstOrNull()
          pendingCount = queue.size
          head
        } ?: break
        currentUri = item.first
        batchTotal += 1
        batchIndex = batchTotal
        TaskNotify.update(
          context, "离线转写",
          "第 $batchIndex/$batchTotal 个 · ${item.second ?: item.first.lastPathSegment ?: ""}",
          batchIndex, batchTotal,
        )
        val (uri, baseName) = item
        if (!runOne(uri, baseName)) {
          AppLog.w("批量转写：${baseName ?: uri.lastPathSegment} 失败，继续下一个")
        }
      }
      AppLog.i("批量转写结束 共=$batchTotal")
    } finally {
      currentUri = null
      queueRunning = false
      batchTotal = 0
      batchIndex = 0
      enqueueMutex.withLock { pendingCount = queue.size }
      TaskNotify.end(context)
    }
  }

  /** 执行一次转写；返回是否成功。 */
  private suspend fun runOne(uri: Uri, baseName: String?): Boolean {
    TaskTrace.begin("ASR转写")
    TaskTrace.update(baseName ?: uri.lastPathSegment ?: uri.toString())
    try {
      val started = System.currentTimeMillis()
      AppLog.i("转写开始 baseName=$baseName")

      // 0. 模型
      val modelDir = File(context.filesDir, MODELS_DIR)
      val vadFile = File(context.filesDir, VAD_PATH)
      if (!modelReady()) {
        AppLog.e("转写中止：模型未就绪 dir=$modelDir")
        phase = TranscribePhase.Failed("模型未就绪：请先在「设置」中导入模型文件")
        return false
      }

      // 0.5 录音结束时刻：导入音频=源文件 mtime（≈录音收尾），应用内录音=wav 收尾 mtime。
      // 记入 srt 尾部标记随链路传递（照片插图按录音时段匹配），不依赖音频文件位置。
      val recordingEndMs = queryLastModified(uri).takeIf { it > 0 } ?: System.currentTimeMillis()
      AppLog.i("转写：录音结束时刻=${fmtTs(recordingEndMs)}")

      // 1. 解码 → VAD → 识别（流式，边解码边喂）
      phase = TranscribePhase.Decoding(0f)
      val segments = ArrayList<TranscriptSegment>(1024)
      var durationMs = 0L
      withContext(Dispatchers.IO) {
        // M2.10：识别语种跟随「笔记语言」设置（zh/en）——英文课程切 en；
        // 中英混说课程建议保持 zh（SenseVoice 的多语混识能力有限，实测为准）
        val asrLang = settings.noteLanguage.firstOrNull() ?: "zh"
        val engine = SenseVoiceEngine(
          modelDir = modelDir, vadModelFile = vadFile, language = asrLang,
        )
        try {
          durationMs = AudioDecoder.decodeWithProgress(
            context, uri,
            onProgress = { p -> phase = TranscribePhase.Decoding(p) },
          ) { chunk ->
            engine.feed(chunk) { seg ->
              segments.add(seg)
              phase = TranscribePhase.Recognizing(segments.size, System.currentTimeMillis() - started)
            }
          }
          engine.flush { seg ->
            segments.add(seg)
            phase = TranscribePhase.Recognizing(segments.size, System.currentTimeMillis() - started)
          }
          TaskTrace.update("识别完成 ${segments.size}段")
        } finally {
          engine.release()
        }
      }
      AppLog.mem("ASR识别完成")
      AppLog.i(
        "解码识别完成 durationMs=$durationMs segments=${segments.size} " +
          "耗时=${System.currentTimeMillis() - started}ms",
      )
      if (segments.isEmpty()) {
        AppLog.w("转写中止：0 个语音段（音频可能为空或全程静音）")
        phase = TranscribePhase.Failed("未识别到语音内容（音频可能为空或全程静音）")
        return false
      }

      // 2. 生成 SRT 并落盘（字幕 → audio/；md 笔记由 AI 归纳生成）
      phase = TranscribePhase.Saving
      val sourceName = baseName ?: queryDisplayName(uri) ?: "audio"
      AppLog.i("生成字幕 source=$sourceName")
      val stem = sourceName.substringBeforeLast('.')
      val srtText = SrtWriter.render(SrtWriter.fromSegments(segments), recordingEndMs)

      val srtName = "$stem.srt"
      val audioDir = ws.audioDir()
      if (audioDir == null) {
        phase = TranscribePhase.Failed("工作目录不可用：请先在「设置」中授权 workplace 目录")
        return false
      }
      ws.writeText(audioDir, srtName, "application/x-subrip", srtText)
        ?: throw IllegalStateException("写入字幕失败")

      lastSegments = segments.toList()
      lastSourceName = sourceName
      batchStems = batchStems + stem
      val totalMs = System.currentTimeMillis() - started
      val rtf = if (durationMs > 0) "%.2f".format(totalMs.toDouble() / durationMs) else "n/a"
      AppLog.i(
        "转写完成 file=$srtName segments=${segments.size} durationMs=$durationMs " +
          "耗时=${totalMs}ms RTF=$rtf",
      )
      phase = TranscribePhase.Done(
        srtName = srtName,
        durationMs = durationMs,
        segmentCount = segments.size,
        transcribeMs = totalMs,
      )
      return true
    } catch (t: Throwable) {
      AppLog.e("转写失败", t)
      phase = TranscribePhase.Failed(t.message ?: t.javaClass.simpleName)
      return false
    } finally {
      TaskTrace.end()
    }
  }

  fun reset() {
    if (!isBusy()) {
      phase = TranscribePhase.Idle
      lastSegments = emptyList()
      lastSourceName = ""
    }
  }

  /** 最近一次成功转写的片段与来源名（供 AI 归纳复用；reset 时清空）。 */
  var lastSegments: List<TranscriptSegment> = emptyList()
    private set
  var lastSourceName: String = ""
    private set

  private fun queryDisplayName(uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, null, null, null, null)?.use { c ->
      val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
      if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
    }
  }.getOrNull()

  /** 源文件的修改时刻（SAF；读不到返回 0）。 */
  private fun queryLastModified(uri: Uri): Long = runCatching {
    DocumentFile.fromSingleUri(context, uri)?.lastModified() ?: 0L
  }.getOrDefault(0L)

  private fun fmtTs(ms: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(ms))

  companion object {
    const val MODELS_DIR = "models/sense-voice-small-int8"
    const val VAD_PATH = "models/silero_vad.onnx"
  }
}
