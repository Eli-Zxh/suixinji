package com.eli.suixinji.ui.screens

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.eli.suixinji.asr.TranscriptSegment
import com.eli.suixinji.data.SettingsStore
import com.eli.suixinji.llm.CloudLlmConfig
import com.eli.suixinji.llm.CloudLlmEngine
import com.eli.suixinji.llm.LocalModels
import com.eli.suixinji.llm.NoteLlmEngine
import com.eli.suixinji.llm.SummarizerLlm
import com.eli.suixinji.llm.NoteSummarizer
import com.eli.suixinji.srt.SrtParser
import com.eli.suixinji.task.TaskNotify
import com.eli.suixinji.util.AppLog
import com.eli.suixinji.util.TaskTrace
import com.eli.suixinji.workspace.WorkspaceStore
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** AI 归纳流程状态机。 */
sealed interface LlmPhase {
  data object Idle : LlmPhase
  data object LoadingModel : LlmPhase
  data class Overview(val part: Int, val total: Int) : LlmPhase
  data class Sections(val part: Int, val total: Int) : LlmPhase
  data class Reviewing(val retry: Boolean) : LlmPhase
  data object Questions : LlmPhase
  data class Done(val mdName: String, val elapsedMs: Long, val backend: String) : LlmPhase
  data class Failed(val message: String) : LlmPhase
}

/**
 * AI 归纳控制器：输入是 audio/ 下的 .srt（支持单个或多个排队），
 * 产物为同 stem 的 .md 写入根目录（不含转写原文）。插图是归纳后的独立步骤。
 *
 * 等待管线（M2.6）：归纳请求先进队列（[stemQueue]），单个消费线程按顺序消化——
 * 与转写队列相互独立，ASR 批量与 LLM 批量可同时进行，互不阻塞。
 *
 * M2.8 稳定性：
 * - **整批复用一个引擎**——此前逐篇新建/释放引擎，2.5GB 原生内存反复 mmap/munmap，
 *   长队列下原生堆碎片化 → 卡死闪退（真机踩坑）；顺带省掉每篇重复加载的 20~30s。
 * - **队列去重**——正在跑的与已排队的 stem 不再重复入队（真机曾重复跑同一篇）。
 * - **前台服务 + 通知进度**（TaskNotify）——可离开应用页面等待，进程不被回收。
 */
class LlmController(
  private val context: Context,
  private val scope: CoroutineScope,
  private val ws: WorkspaceStore,
  private val settings: SettingsStore,
) {
  var phase by mutableStateOf<LlmPhase>(LlmPhase.Idle)
    private set

  /** 已出结果计数（本轮队列累计处理到第 x 篇）。 */
  var batchIndex by mutableStateOf(0)
    private set
  var batchTotal by mutableStateOf(0)
    private set

  /** 等待管线：队列中还没轮到的篇数。 */
  var pendingCount by mutableStateOf(0)
    private set

  /** 是否正在消化队列。 */
  var queueRunning by mutableStateOf(false)
    private set

  private val stemQueue = ArrayDeque<String>()
  private val enqueueMutex = Mutex()
  @Volatile private var currentStem: String? = null

  /** 归纳方式：local（默认）/ cloud（全局使用网络 API）。 */
  var provider by mutableStateOf("local")
    private set

  private var cloudConfig by mutableStateOf(CloudLlmConfig(null, null, null))

  var localModelId by mutableStateOf(LocalModels.DEFAULT_ID)
    private set

  init {
    scope.launch {
      settings.llmProvider.collect { provider = it }
    }
    scope.launch {
      settings.localModelId.collect { localModelId = it }
    }
    scope.launch {
      combine(
        settings.cloudBaseUrl,
        settings.cloudModelName,
        settings.cloudApiKey,
      ) { url, model, key -> CloudLlmConfig(url, model, key) }
        .collect { cloudConfig = it }
    }
  }

  fun modelReady(): Boolean =
    if (provider == "cloud") cloudConfig.complete
    else NoteLlmEngine(context, LocalModels.byId(localModelId)).modelReady()

  fun localModelLabel(): String = LocalModels.label(localModelId)

  fun providerLabel(): String = if (provider == "cloud") "云端模型" else "本地模型"

  fun busy(): Boolean =
    queueRunning || pendingCount > 0 || phase is LlmPhase.LoadingModel ||
      phase is LlmPhase.Overview || phase is LlmPhase.Sections ||
      phase is LlmPhase.Reviewing || phase is LlmPhase.Questions || batchTotal > 0

  /** 归纳单个历史字幕（排队执行）。 */
  fun summarizeFromSrt(stem: String) = summarizeFromSrtMany(listOf(stem))

  /** 归纳多个历史字幕：全部进队列，按顺序逐个处理，单个失败不中断。 */
  fun summarizeFromSrtMany(stems: List<String>) {
    if (stems.isEmpty()) return
    scope.launch {
      enqueueMutex.withLock {
        // 去重：正在跑的与已排队的不再入队（真机曾把同一篇跑了两遍）
        val fresh = stems.filter { it != currentStem && !stemQueue.contains(it) }
        stemQueue.addAll(fresh)
        pendingCount = stemQueue.size
        fresh.size
      }.let { added ->
        AppLog.i("归纳队列：+$added 篇，待处理 $pendingCount")
      }
      drainQueue()
    }
  }

  /** 单消费者消化队列：running 标志在首个挂起点前置位（Main 调度器顺序执行），保证唯一。 */
  private suspend fun drainQueue() {
    if (queueRunning) return
    queueRunning = true
    var okCount = 0
    // 整批复用一个引擎（加载慢 + 反复 mmap 2.5GB 是闪退根源），finally 统一释放
    var eng: SummarizerLlm? = null
    try {
      TaskNotify.begin(context, "AI 归纳")
      while (true) {
        val stem = enqueueMutex.withLock {
          val s = stemQueue.removeFirstOrNull()
          pendingCount = stemQueue.size
          s
        } ?: break
        currentStem = stem
        batchTotal += 1
        batchIndex = batchTotal
        TaskNotify.update(context, "AI 归纳", "第 $batchIndex/$batchTotal 篇 · $stem", batchIndex, batchTotal)
        TaskTrace.begin("AI归纳")
        TaskTrace.update("$stem 未开始")

        val segments = loadSegmentsFromSrt(stem)
        if (segments == null) {
          AppLog.w("批量归纳：$stem 读取失败，继续下一个")
          continue
        }
        // 引擎惰性创建；模型/网络配置缺失或加载失败 → 终止整批
        if (eng == null) {
          phase = LlmPhase.LoadingModel
          eng = withContext(Dispatchers.IO) {
            try {
              when (provider) {
                "cloud" -> {
                  val cfg = cloudConfig
                  if (!cfg.complete) {
                    phase = LlmPhase.Failed("云端模型未配置：请先在「设置 → 云端模型」填写接口地址与模型名")
                    null
                  } else {
                    CloudLlmEngine(cfg).also {
                      it.initialize()
                      AppLog.i("归纳引擎：cloud model=${cfg.model}（密钥不入日志）")
                    }
                  }
                }
                else -> {
                  val e = NoteLlmEngine(context, LocalModels.byId(localModelId))
                  if (!e.modelReady()) {
                    AppLog.e("批量归纳中止：模型未导入 path=${e.modelFile().absolutePath}")
                    phase = LlmPhase.Failed("归纳模型未导入：请先在「设置 → 归纳模型」导入 .litertlm 文件")
                    runCatching { e.close() }
                    null
                  } else {
                    // M2.10：推理后端策略与笔记语言均从设置读取（每次归纳即时生效）
                    e.initialize(settings.llmBackend.firstOrNull() ?: "auto")
                    AppLog.mem("LLM引擎就绪 backend=${e.backendLabel}")
                    e
                  }
                }
              }
            } catch (t: Throwable) {
              AppLog.e("批量归纳中止：引擎加载失败", t)
              phase = LlmPhase.Failed(t.message ?: t.javaClass.simpleName)
              null
            }
          } ?: break
        }
        if (runPipeline(eng, segments, "$stem.m4a") != null) okCount++
        else AppLog.w("批量归纳：$stem 失败，继续下一个")
        TaskTrace.end()
        AppLog.mem("第${batchIndex}篇完成")
      }
      AppLog.i("批量归纳结束 ok=$okCount/$batchTotal")
    } finally {
      currentStem = null
      runCatching { eng?.close() }
      queueRunning = false
      batchTotal = 0
      batchIndex = 0
      enqueueMutex.withLock { pendingCount = stemQueue.size }
      TaskTrace.end()
      AppLog.mem("LLM队列结束")
      TaskNotify.end(context)
    }
  }

  private suspend fun loadSegmentsFromSrt(stem: String): List<TranscriptSegment>? {
    return try {
      val srtName = "$stem.srt"
      val srtDoc = ws.audioDir()?.listFiles()?.firstOrNull { it.name == srtName }
      if (srtDoc == null) {
        AppLog.e("归纳中止：audio/ 下找不到 $srtName")
        phase = LlmPhase.Failed("找不到字幕 $srtName")
        return null
      }
      val srtText = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(srtDoc.uri)?.use {
          it.readBytes().toString(Charsets.UTF_8)
        }
      } ?: ""
      val segments = SrtParser.parse(srtText)
      if (segments.isEmpty()) {
        AppLog.e("归纳中止：$srtName 解析结果为空")
        phase = LlmPhase.Failed("字幕内容为空，无法归纳")
        return null
      }
      AppLog.i("归纳开始 source=$stem.srt segments=${segments.size}")
      segments
    } catch (t: Throwable) {
      AppLog.e("归纳失败（读取字幕）", t)
      phase = LlmPhase.Failed(t.message ?: t.javaClass.simpleName)
      null
    }
  }

  /** 用给定引擎执行一次完整归纳并写盘（插图是独立步骤，不在此处）；返回 md 名，失败返回 null。 */
  private suspend fun runPipeline(
    eng: SummarizerLlm,
    segments: List<TranscriptSegment>,
    sourceName: String,
  ): String? {
    val started = System.currentTimeMillis()
    try {
      val stem = sourceName.substringBeforeLast('.').ifBlank { "课堂笔记" }
      val mdName = "$stem.md"
      val root = ws.rootDir()
      if (root == null) {
        phase = LlmPhase.Failed("工作目录不可用：请先在「设置」中授权 workplace 目录")
        return null
      }
      val markdown = withContext(Dispatchers.IO) {
        // M2.10：笔记语言从设置读取（zh/en），提示词与解析标记随 LangPack 切换
        val noteLang = settings.noteLanguage.firstOrNull() ?: "zh"
        NoteSummarizer.run(eng, segments, sourceName, language = noteLang, onProgress = { stage, i, n ->
          phase = when (stage) {
            NoteSummarizer.Stage.OVERVIEW -> LlmPhase.Overview(i, n)
            NoteSummarizer.Stage.SECTIONS -> LlmPhase.Sections(i, n)
            NoteSummarizer.Stage.REVIEW -> LlmPhase.Reviewing(retry = i > 1)
            NoteSummarizer.Stage.QUESTIONS -> LlmPhase.Questions
          }
          TaskNotify.update(
            context, "AI 归纳",
            "第 $batchIndex/$batchTotal 篇 · 阶段进度 $i/$n",
            batchIndex, batchTotal,
          )
          TaskTrace.update("$sourceName 阶段$stage $i/$n")
        }, onCheckpoint = { partial ->
          // 逐阶段落盘：概览完成/每个分段完成后覆盖写同一文件——长任务中途
          // 崩溃（原生崩溃兜不住，只能靠已落盘内容）时已完成部分不丢
          runCatching { ws.writeText(root, mdName, "text/markdown", partial) }
            .onFailure { AppLog.w("归纳：阶段性保存失败 ${it.message}") }
        })
      }
      ws.writeText(root, mdName, "text/markdown", markdown)
        ?: throw IllegalStateException("写入归纳笔记失败")

      val totalMs = System.currentTimeMillis() - started
      AppLog.i("归纳完成 file=$mdName model=${eng.label} backend=${eng.backendLabel} 耗时=${totalMs}ms")
      phase = LlmPhase.Done(mdName, totalMs, eng.label)
      return mdName
    } catch (t: Throwable) {
      AppLog.e("归纳失败", t)
      phase = LlmPhase.Failed(t.message ?: t.javaClass.simpleName)
      return null
    }
  }

  fun reset() {
    if (!busy()) phase = LlmPhase.Idle
  }
}
