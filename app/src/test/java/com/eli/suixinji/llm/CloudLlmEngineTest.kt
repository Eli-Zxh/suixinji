package com.eli.suixinji.llm

import org.junit.Assert.assertEquals
import org.junit.Test

class CloudLlmEngineTest {
  @Test
  fun `label uses configured model name not transport name`() {
    val engine = CloudLlmEngine(
      CloudLlmConfig("https://text.pollinations.ai/openai", "openai", null),
    )
    assertEquals("openai", engine.label)
    assertEquals("云端", engine.backendLabel)
  }

  @Test
  fun `endpoint accepts base v1 full path and openai suffix`() {
    assertEquals("https://api.example.com/v1/chat/completions", CloudLlmEngine.endpoint("https://api.example.com/v1"))
    assertEquals("https://api.example.com/v1/chat/completions", CloudLlmEngine.endpoint("https://api.example.com/v1/chat/completions/"))
    assertEquals("https://text.pollinations.ai/openai", CloudLlmEngine.endpoint("https://text.pollinations.ai/openai"))
  }
}
