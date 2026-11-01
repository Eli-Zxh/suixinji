package com.eli.suixinji.llm

import android.content.Context
import com.eli.suixinji.util.AppLog
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import java.io.File

/**
 * LiteRT-LM 引擎封装：从应用私有目录加载 .litertlm 模型（Gemma-4 E2B int4）。
 * 后端尝试顺序 GPU → NPU → CPU，每级「初始化 + 首推理探活」通过才算可用：
 * - GPU 最先试：先预载设备 OpenCL 库再初始化（真机 litert 自身 dlopen 失败会报
 *   Can not find OpenCL library，预载 /vendor/lib64/libOpenCL*.so 后可命中）；
 * - NPU 次选（M2.9 用户拍板重测）：探活真机实测通过；早期「比 CPU 慢 4~6 倍」
 *   的数据是 M2.6 输出精简前测的，现在输出 token 大减，值得再实测一次——
 *   注意 litertlm 内部 10 分钟会话超时不可配置，若单次调用仍超时则该级失败降级；
 * - CPU 是可靠基线（真机全文归纳 785~903s，单次调用 100~340s）。
 * 与 ASR 引擎不同时驻留：每次归纳前 [initialize]，结束调用 [close] 释放内存。
 */
class NoteLlmEngine(
  private val context: Context,
  private val profile: LocalModelProfile = LocalModels.byId(LocalModels.DEFAULT_ID),
) : SummarizerLlm {

  @Volatile private var engine: Engine? = null

  @Volatile private var backendLabelRaw: String = "未加载"

  override val label: String get() = profile.label

  override val backendLabel: String get() = backendLabelRaw

  /** 接口默认初始化：由 LlmController 在加载前用显式后端策略调用参数版。 */
  override fun initialize() {
    initialize("auto")
  }

  override fun chat(systemInstruction: String, prompt: String, maxOutputTokens: Int): String =
    conversation(systemInstruction, maxOutputTokens).use { conv ->
      val m = conv.sendMessage(prompt)
      m.contents.contents.firstNotNullOfOrNull { it as? Content.Text }?.text ?: ""
    }

  fun modelFile(): File = File(context.filesDir, profile.fileName)

  fun profile(): LocalModelProfile = profile

  fun modelReady(): Boolean {
    val f = modelFile()
    return f.isFile && f.length() > MIN_MODEL_BYTES
  }

  /**
   * 加载模型（数秒到数十秒），须在 IO 线程调用。
   *
   * [backendPolicy]（M2.10 设置项）：
   * - "auto"（默认）：GPU→NPU→CPU 逐级探活降级，省电优先；
   * - "cpu" / "npu" / "gpu"：强制指定单后端——仍做探活，失败即报错不降级
   *   （强制语义：用户明确选了它，静默降级反而违背预期）。
   */
  fun initialize(backendPolicy: String = "auto") {
    if (engine != null) return
    val started = System.currentTimeMillis()
    val f = modelFile()
    if (!f.isFile) {
      AppLog.e("LLM：模型文件不存在 path=${f.absolutePath}")
      error("模型文件不存在：${f.name}")
    }

    val chain: List<Pair<String, Backend>> = when (backendPolicy) {
      "cpu" -> listOf("CPU" to Backend.CPU(CPU_THREADS))
      "npu" -> listOf("NPU" to Backend.NPU())
      "gpu" -> listOf("GPU" to Backend.GPU())
      else -> listOf(
        "GPU" to Backend.GPU(),
        "NPU" to Backend.NPU(),
        "CPU" to Backend.CPU(CPU_THREADS),
      )
    }
    AppLog.i(
      "LLM：开始加载模型 size=${f.length()}B 策略=$backendPolicy " +
        "设备=${DeviceCompat.describe()} 链=${chain.joinToString("→") { it.first }} maxTokens=${profile.maxNumTokens}",
    )
    AppLog.mem("LLM加载前")
    // OpenCL 预载只服务 GPU 探活；纯 CPU 策略跳过，省去无效尝试
    if (chain.any { it.first == "GPU" }) preloadOpenCl()
    val cacheDir = File(context.cacheDir, "llm").apply { mkdirs() }
    val base = EngineConfig(
      modelPath = f.absolutePath,
      backend = Backend.GPU(),
      maxNumTokens = profile.maxNumTokens,
      cacheDir = cacheDir.absolutePath,
    )

    // 0.17.1 的各后端 initialize() 不做预热：本机曾出现 GPU「加载成功」但首次
    // sendMessage 抛 Can not find OpenCL library。因此每个后端必须「初始化 + 一次
    // 极小推理探活」都通过才算可用，探活失败同样降级。
    // auto 链序 GPU → NPU → CPU（M2.9 用户拍板：NPU 探活实测通过且耗电低，作为
    // 省电默认；早期「比 CPU 慢 4~6 倍」的结论是 M2.6 输出精简前测的，精简后
    // 单次调用预估回落到 10 分钟超时线内。真机实测 NPU 仍显著慢于 CPU 时，
    // 用户可在设置里强制 CPU；链序回退改一行即可）。
    var lastError: Throwable? = null
    for ((index, item) in chain.withIndex()) {
      val (label, backend) = item
      AppLog.mem("LLM尝试${label}前")
      val t0 = System.currentTimeMillis()
      try {
        engine = initAndProbe(base.copy(backend = backend), label)
        backendLabelRaw = label
        AppLog.i("LLM：$label 后端加载成功（含首推理探活） 耗时=${System.currentTimeMillis() - started}ms")
        AppLog.mem("LLM就绪$label")
        return
      } catch (t: Throwable) {
        lastError = t
        val kind = if (t is OutOfMemoryError) "OOM" else t.javaClass.simpleName
        AppLog.w(
          "LLM：$label 后端不可用（$kind，${System.currentTimeMillis() - t0}ms），" +
            if (index + 1 < chain.size) "尝试下一后端" else "无后续后端",
          t,
        )
        AppLog.mem("LLM失败$label")
        // 让 native 释放上一后端的 mmap，再试下一后端；否则 2.5GB 模型可能叠两份。
        System.gc()
        if (index + 1 < chain.size) Thread.sleep(500)
      }
    }
    val reason = lastError?.message ?: lastError?.javaClass?.simpleName ?: "未知原因"
    error("LLM：后端链全部不可用（策略=$backendPolicy）——$reason")
  }

  /**
   * 预载设备 OpenCL 库（GPU 后端可用性关键）。真机 /vendor/lib64/libOpenCL.so 存在
   * 且列入厂商公共库清单，但 litert 按名 dlopen 仍失败（Can not find OpenCL library）；
   * 先把库 System.load 进进程，litert 后续 dlopen 按已注册的 soname 即可命中。
   * 全部预载失败也不阻塞（GPU 探活自然失败降级 CPU）。
   */
  private fun preloadOpenCl() {
    if (openClPreloaded) return
    openClPreloaded = true
    // 统一交给 OpenClLoader（M2.9 定稿）：
    // 1) 链接级 shim——ROM 命名空间配置正常时一步到位；
    // 2) 依赖闭包拷贝——Honor/MagicOS 上厂商公共库机制未接入应用命名空间，
    //    改为：应用域可读 /vendor 文件（SELinux 实测放行）+ /system/lib64 与
    //    应用私有目录在许可路径内。运行时解析 ELF NEEDED + 库内按名 dlopen
    //    名单，从 libOpenCL.so BFS 出完整闭包（vendor 44 个拷贝、system 13 个
    //    绝对路径），拓扑序逐个 System.load 注册 soname——litert 按名 dlopen
    //    全部命中。任一路径失败只记日志，GPU 探活自然降级 CPU。
    OpenClLoader.preload(context)
  }

  @Volatile private var openClPreloaded = false

  /** 初始化并用一次极小推理探活；任一步失败都释放资源后抛出。 */
  private fun initAndProbe(cfg: EngineConfig, label: String): Engine {
    val eng = Engine(cfg)
    try {
      eng.initialize()
      if (!eng.isInitialized()) error("$label 后端初始化失败")
      val conv = eng.createConversation(
        ConversationConfig(systemInstruction = Contents.of("你是探活助手，只回复 ok")),
      )
      try {
        conv.sendMessage("ping")
      } finally {
        runCatching { conv.close() }
      }
      return eng
    } catch (t: Throwable) {
      runCatching { eng.close() }
      throw t
    }
  }

  fun conversation(
    systemInstruction: String,
    maxOutputTokens: Int = MAX_OUTPUT_TOKENS,
  ): Conversation {
    val e = engine ?: error("LLM 引擎未初始化")
    return e.createConversation(
      ConversationConfig(
        systemInstruction = Contents.of(systemInstruction),
        maxOutputToken = maxOutputTokens,
      ),
    )
  }

  override fun close() {
    if (engine != null) AppLog.i("LLM：释放引擎")
    runCatching { engine?.close() }
    engine = null
    backendLabelRaw = "未加载"
  }

  companion object {
    /** 单次生成上限：防止小模型在自测/审核阶段无限续写（1.5B CPU 实测会卡住）。 */
    private const val MAX_OUTPUT_TOKENS: Int = 2048

    const val MODEL_PATH = "models/gemma-4-E2B-it.litertlm"
    const val MODEL_NAME = "gemma-4-E2B-it.litertlm"

    /** 限制上下文长度以压缩 KV cache 内存（单次调用输入 ≤4.5K 字符）。 */
    private const val CPU_THREADS = 6
    private const val MIN_MODEL_BYTES = 100L * 1024 * 1024
  }
}
