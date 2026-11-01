package com.eli.suixinji.llm

import com.eli.suixinji.util.AppLog
import java.time.Duration
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** 云端 OpenAI 兼容配置；密钥由 SettingsStore 解密后传入，不落日志。 */
data class CloudLlmConfig(
  val baseUrl: String?,
  val model: String?,
  val apiKey: String?,
) {
  val complete: Boolean
    get() = !baseUrl.isNullOrBlank() && !model.isNullOrBlank()

  val safeLabel: String
    get() = model?.takeIf { it.isNotBlank() } ?: "云端模型"
}

/**
 * OpenAI 兼容的归纳后端（v1.1）。
 *
 * - 只用于“AI 归纳”，不碰 ChatScreen 的本地对话；
 * - baseUrl 支持三种写法：`.../v1`、`.../chat/completions`、`.../openai`
 *   （最后一种给 Pollinations 这类免费测试端点）；
 * - API Key 可留空（免鉴权服务），有值时只放 Authorization 头，不写日志；
 * - 只记录 host/耗时/回复长度，不记录 prompt、字幕、密钥。
 */
class CloudLlmEngine(private val config: CloudLlmConfig) : SummarizerLlm {

  override val label: String = config.model?.trim()?.takeIf { it.isNotEmpty() } ?: "云端模型"
  override val backendLabel: String = "云端"

  private val client = OkHttpClient.Builder()
    .callTimeout(Duration.ofSeconds(120))
    .connectTimeout(Duration.ofSeconds(20))
    .readTimeout(Duration.ofSeconds(120))
    .build()

  override fun initialize() {
    require(config.complete) { "云端模型未配置：需要接口地址与模型名" }
    val url = endpoint(config.baseUrl!!)
    require(url.startsWith("https://")) { "云端模型接口必须使用 HTTPS" }
    AppLog.i("CloudLlm：就绪 host=${hostOf(url)} model=${config.model}")
  }

  override fun chat(systemInstruction: String, prompt: String, maxOutputTokens: Int): String {
    val url = endpoint(config.baseUrl ?: error("云端模型未配置"))
    require(url.startsWith("https://")) { "云端模型接口必须使用 HTTPS" }
    val body = JSONObject().apply {
      put("model", config.model ?: "")
      put("temperature", 0.2)
      put("max_tokens", maxOutputTokens)
      put(
        "messages",
        JSONArray().apply {
          put(JSONObject().put("role", "system").put("content", systemInstruction))
          put(JSONObject().put("role", "user").put("content", prompt))
        },
      )
    }.toString()
    val builder = Request.Builder()
      .url(url)
      .header("Content-Type", "application/json")
      .post(body.toRequestBody("application/json".toMediaType()))
    val key = config.apiKey.orEmpty().trim()
    if (key.isNotEmpty()) builder.header("Authorization", "Bearer $key")
    val request = builder.build()
    var attempt = 0
    while (true) {
      val t0 = android.os.SystemClock.elapsedRealtime()
      client.newCall(request).execute().use { resp ->
        val text = resp.body?.string().orEmpty()
        val ms = android.os.SystemClock.elapsedRealtime() - t0
        if (!resp.isSuccessful) {
          // 免费/共享端点常见 402/429；按 Retry-After 或响应里的 "Retry after Ns" 退避。
          if ((resp.code == 402 || resp.code == 429) && attempt < 2) {
            val wait = retryAfterSeconds(resp.header("Retry-After"), text) ?: (5L shl attempt)
            AppLog.w(
              "CloudLlm：限流 HTTP=${resp.code}，等待 ${wait}s 后重试（${attempt + 1}/2） host=${hostOf(url)}",
            )
            Thread.sleep(wait * 1000L)
            attempt++
            return@use
          }
          // 不把响应体写入日志/异常：恶意或代理服务可能回显请求内容。
          AppLog.w("CloudLlm：HTTP=${resp.code} 耗时=${ms}ms host=${hostOf(url)}")
          throw IllegalStateException("${label} HTTP ${resp.code}：请检查接口地址/密钥/额度")
        }
        val reply = runCatching {
          JSONObject(text).getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").optString("content")
        }.getOrNull().orEmpty()
        if (reply.isBlank()) {
          AppLog.w("CloudLlm：空回复 耗时=${ms}ms host=${hostOf(url)}")
          throw IllegalStateException("${label} 返回空内容")
        }
        AppLog.i("CloudLlm：调用成功 耗时=${ms}ms replyChars=${reply.length} model=${config.model}")
        return reply
      }
    }
  }

  override fun close() = Unit

  companion object {
    private fun hostOf(url: String): String =
      runCatching { java.net.URI(url).host }.getOrNull() ?: "?"

    /** Retry-After 支持秒数与 HTTP-date；部分免费端点只在 JSON message 里写 "Retry after 43s"。 */
    private fun retryAfterSeconds(header: String?, body: String): Long? {
      header?.trim()?.toLongOrNull()?.let { return it.coerceIn(1L, 65L) }
      val m = Regex("Retry after\\s+(\\d+)s", RegexOption.IGNORE_CASE).find(body)
      return m?.groupValues?.getOrNull(1)?.toLongOrNull()?.coerceIn(1L, 65L)
    }
    /** 兼容 `/v1`、完整 `/chat/completions`、以及 `/openai` 端点。 */
    fun endpoint(base: String): String {
      val b = base.trim().trimEnd('/')
      return when {
        b.endsWith("/chat/completions") -> b
        b.endsWith("/openai") -> b
        else -> "$b/chat/completions"
      }
    }

    /** 设置页「测试连接」：只发 ping，最多 8 tokens；不打印密钥/正文。 */
    fun probe(config: CloudLlmConfig): String {
      val url = endpoint(config.baseUrl ?: return "未填写接口地址")
      if (!url.startsWith("https://")) return "接口地址必须使用 HTTPS"
      val body = JSONObject().apply {
        put("model", config.model ?: "")
        put("temperature", 0.0)
        put("max_tokens", 8)
        put("messages", JSONArray().apply {
          put(JSONObject().put("role", "user").put("content", "ping"))
        })
      }.toString()
      val builder = Request.Builder()
        .url(url)
        .header("Content-Type", "application/json")
        .post(body.toRequestBody("application/json".toMediaType()))
      config.apiKey.orEmpty().trim().takeIf { it.isNotEmpty() }?.let {
        builder.header("Authorization", "Bearer $it")
      }
      val t0 = android.os.SystemClock.elapsedRealtime()
      return runCatching {
        OkHttpClient.Builder()
          .callTimeout(Duration.ofSeconds(30))
          .connectTimeout(Duration.ofSeconds(15))
          .build()
          .newCall(builder.build()).execute().use { resp ->
            val ms = android.os.SystemClock.elapsedRealtime() - t0
            val text = resp.body?.string().orEmpty()
            AppLog.i("CloudLlm测试：HTTP=${resp.code} 耗时=${ms}ms host=${hostOf(url)}")
            when {
              resp.isSuccessful -> "连接成功（HTTP ${resp.code}，${ms}ms）"
              resp.code == 401 || resp.code == 403 -> "认证失败（HTTP ${resp.code}）：检查 API Key"
              else -> "HTTP ${resp.code}：请检查接口地址/额度"
            }
          }
      }.getOrElse { "连接失败：${it.message}" }
    }
  }
}
