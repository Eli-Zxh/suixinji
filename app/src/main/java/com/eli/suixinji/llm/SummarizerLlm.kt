package com.eli.suixinji.llm

/**
 * 归纳管线的最小 LLM 抽象（v1.1）：
 * - 本地 [NoteLlmEngine]：litertlm 三阶段；
 * - 网络 [CloudLlmEngine]：OpenAI 兼容 /chat/completions。
 *
 * 只暴露“一次系统提示 + 一次用户提示 → 文本回复”，与 NoteSummarizer 的
 * 提示词隔离/重试/回显检测解耦；不把对话对象泄漏给管线。
 */
interface SummarizerLlm {
  /** 结果元数据用：实际模型名（如 Gemma-4 E2B / Qwen2.5 0.5B / openai）。 */
  val label: String

  /** 状态展示用：CPU / NPU / GPU / 云端。 */
  val backendLabel: String

  fun initialize()

  fun chat(systemInstruction: String, prompt: String, maxOutputTokens: Int = 2048): String

  fun close()
}
