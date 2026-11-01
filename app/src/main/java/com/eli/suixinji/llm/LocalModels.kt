package com.eli.suixinji.llm

/**
 * 本地归纳模型配置（v1.1）。
 *
 * 不把“设备/芯片”硬编码进推理链：模型文件、上下文长度随 profile 走；
 * 用户按设备内存选择轻量模型，CPU/GPU/NPU 仍由设置里的后端策略决定。
 */
data class LocalModelProfile(
  val id: String,
  val label: String,
  /** 相对 filesDir 的路径。 */
  val fileName: String,
  val maxNumTokens: Int,
  val approxMb: Int,
  val hint: String,
)

object LocalModels {
  const val DEFAULT_ID = "gemma4-e2b"

  val all: List<LocalModelProfile> = listOf(
    LocalModelProfile(
      id = DEFAULT_ID,
      label = "Gemma-4 E2B",
      fileName = NoteLlmEngine.MODEL_PATH,
      maxNumTokens = 8192,
      approxMb = 2538,
      hint = "高质量；12GB 麒麟用 LiteRT 加载会 OOM，建议小内存设备选轻量模型",
    ),
    LocalModelProfile(
      id = "qwen2.5-0.5b",
      label = "Qwen2.5 0.5B",
      fileName = "models/qwen2.5-0.5b.litertlm",
      maxNumTokens = 8192,
      approxMb = 400,
      hint = "极轻量；12GB 麒麟 CPU 实测可加载，但三阶段归纳质量偏弱",
    ),
    LocalModelProfile(
      id = "qwen2.5-1.5b",
      label = "Qwen2.5 1.5B",
      fileName = "models/qwen2.5-1.5b.litertlm",
      maxNumTokens = 8192,
      approxMb = 1100,
      hint = "轻量质量档；12GB 麒麟 CPU 预计 2~4GB 内存，优先验证这档",
    ),
  )

  fun byId(id: String?): LocalModelProfile = all.firstOrNull { it.id == id } ?: all.first()

  fun label(id: String?): String = byId(id).label
}
