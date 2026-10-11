package com.eli.suixinji.workspace

import android.content.ContentResolver
import android.util.Base64
import androidx.documentfile.provider.DocumentFile
import com.eli.suixinji.util.AppLog
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * WebDAV 客户端（OkHttp）。
 *
 * 为什么不能用 HttpURLConnection：Android 的 huc 只接受标准方法，
 * setRequestMethod("PROPFIND") 直接抛 ProtocolException（真机日志已证实）。
 * OkHttp 的 Request.Builder.method() 支持任意 WebDAV 方法。
 *
 * 同步策略：单向推送——本地 lastModified 比远端新才上传；远端时间从
 * PROPFIND 的 207 multistatus XML 里取 getlastmodified（HTTP header 里没有）。
 */
class WebDavClient(
  private val resolver: ContentResolver,
  baseUrl: String,
  user: String,
  password: String,
) {

  data class Result(val uploaded: Int, val skipped: Int, val errors: List<String>) {
    val ok: Boolean get() = errors.isEmpty()
    fun describe(): String = "上传 $uploaded，跳过 $skipped" +
      (if (errors.isEmpty()) "" else "，失败 ${errors.size}")
  }

  private val baseUrlNorm = baseUrl.trimEnd('/')
  private val authHeader: String =
    "Basic " + Base64.encodeToString("$user:$password".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
  private val xmlType = "application/xml".toMediaType()

  /**
   * 由「远端相对路径」构造完整 URL：逐段百分号编码。
   * 远端目录名/文件名可能含空格（如 "my notes"），直接拼接 URL 会请求到错误路径。
   */
  private fun url(remotePath: String): String {
    val segs = remotePath.split('/').filter { it.isNotBlank() }.map { enc(it) }
    val trailing = if (remotePath.endsWith("/")) "/" else ""
    return baseUrlNorm + (if (segs.isEmpty()) "" else "/" + segs.joinToString("/")) + trailing
  }

  /** 单个路径段编码（URLEncoder 是表单编码，空格为 +，需换回 %20）。 */
  private fun enc(seg: String): String =
    java.net.URLEncoder.encode(seg, "UTF-8").replace("+", "%20")

  private val client = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(120, TimeUnit.SECONDS)
    .writeTimeout(120, TimeUnit.SECONDS)
    .build()

  /** 连接测试：PROPFIND depth 0。返回 null=成功，否则为错误描述。 */
  fun testConnection(): String? {
    return try {
      propfind(url("/")).use { resp ->
        when (resp.code) {
          in 200..299, 207 -> null
          401 -> "用户名或密码错误（401）"
          403 -> "服务器拒绝访问（403）：检查账号权限或使用应用密码"
          404 -> "路径不存在，请检查 URL（404）"
          else -> "HTTP ${resp.code}"
        }
      }
    } catch (e: Exception) {
      AppLog.e("WebDAV：连接测试失败 url=$baseUrlNorm", e)
      "无法连接：${e.message ?: e.javaClass.simpleName}"
    }
  }

  /**
   * 同步整个库：远端建 remoteDir + audio/ + picture/，上传根目录 .md、
   * audio/、picture/ 下所有文件（本地较新才传，不删除远端）。
   */
  suspend fun syncAll(root: DocumentFile, remoteDir: String): Result {
    var uploaded = 0
    var skipped = 0
    val errors = mutableListOf<String>()

    // 远端目录链：remoteDir 及 audio/、picture/
    val dirs = mutableListOf<String>()
    var acc = ""
    for (seg in remoteDir.trim('/').split('/').filter { it.isNotBlank() }) {
      acc = "$acc/$seg"
      dirs.add(acc)
    }
    if (dirs.isEmpty()) dirs.add("/")
    for (sub in listOf(WorkspaceStore.DIR_AUDIO, WorkspaceStore.DIR_PICTURE)) {
      dirs.add("$acc/$sub")
    }
    for (d in dirs) {
      val code = mkcol(url(d))
      if (code !in listOf(null, 405, 301)) errors.add("创建远端目录 $d 失败：HTTP $code")
    }
    if (errors.isNotEmpty()) return Result(uploaded, skipped, errors)

    // 待上传清单：根目录 .md + audio/ + picture/ 全部文件
    val jobs = mutableListOf<Pair<DocumentFile, String>>()
    for (f in root.listFiles()) {
      if (f.isFile && f.name?.endsWith(".md") == true) {
        jobs.add(f to "$acc/${f.name}")
      }
    }
    for (sub in listOf(WorkspaceStore.DIR_AUDIO, WorkspaceStore.DIR_PICTURE)) {
      root.findFile(sub)?.takeIf { it.isDirectory }?.listFiles()?.forEach { f ->
        if (f.isFile && f.name != null) jobs.add(f to "$acc/$sub/${f.name}")
      }
    }

    for ((file, remotePath) in jobs) {
      val name = file.name ?: continue
      try {
        val remoteMtime = statModified(url(remotePath))
        val localMtime = file.lastModified()
        if (remoteMtime != null && localMtime in 1 until remoteMtime) {
          skipped++
          continue
        }
        put(file, url(remotePath))
        uploaded++
        AppLog.i("WebDAV：已上传 $name")
      } catch (e: Exception) {
        AppLog.e("WebDAV：上传失败 $name", e)
        errors.add("$name：${e.message ?: e.javaClass.simpleName}")
      }
    }
    val r = Result(uploaded, skipped, errors)
    AppLog.i("WebDAV：同步完成 ${r.describe()}")
    return r
  }

  // ---- WebDAV 方法 ----

  /** PROPFIND depth 0（带 getlastmodified 请求体，NextCloud 兼容）。调用方消费 body 并关闭。 */
  private fun propfind(url: String): Response {
    val body = ("<?xml version=\"1.0\"?>\n<D:propfind xmlns:D=\"DAV:\"><D:prop>" +
      "<D:getlastmodified/></D:prop></D:propfind>").toRequestBody(xmlType)
    val req = Request.Builder()
      .url(url)
      .header("Authorization", authHeader)
      .header("Depth", "0")
      .method("PROPFIND", body)
      .build()
    return client.newCall(req).execute()
  }

  /** 单文件 PROPFIND → getlastmodified（秒）。404 → null。 */
  private fun statModified(url: String): Long? {
    return try {
      propfind(url).use { resp ->
        if (resp.code == 404) return null
        if (resp.code !in 200..299 && resp.code != 207) return null
        val body = resp.body?.string() ?: return null
        val m = LASTMOD_REGEX.find(body) ?: return null
        parseHttpDate(m.groupValues[1])
      }
    } catch (e: IOException) {
      AppLog.w("WebDAV：statModified 失败 $url（视为远端不存在）")
      null
    }
  }

  /** MKCOL 建目录，返回状态码（405=已存在）。 */
  private fun mkcol(url: String): Int? = try {
    val req = Request.Builder()
      .url(url)
      .header("Authorization", authHeader)
      .method("MKCOL", ByteArray(0).toRequestBody(null))
      .build()
    client.newCall(req).execute().use { it.code }
  } catch (e: Exception) {
    AppLog.e("WebDAV：MKCOL 失败 $url", e)
    null
  }

  /** 流式 PUT 上传本地文件（未知长度 → OkHttp 自动 chunked）。 */
  private fun put(file: DocumentFile, url: String) {
    val body = object : RequestBody() {
      override fun contentType() = "application/octet-stream".toMediaType()
      override fun writeTo(sink: okio.BufferedSink) {
        resolver.openInputStream(file.uri)?.use { ins ->
          val buf = ByteArray(64 * 1024)
          while (true) {
            val n = ins.read(buf)
            if (n <= 0) break
            sink.write(buf, 0, n)
          }
        } ?: throw IOException("无法打开本地文件流")
      }
    }
    val req = Request.Builder()
      .url(url)
      .header("Authorization", authHeader)
      .put(body)
      .build()
    client.newCall(req).execute().use { resp ->
      if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
    }
  }

  private fun parseHttpDate(raw: String): Long? = try {
    SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).parse(raw)?.time?.div(1000)
  } catch (e: Exception) {
    null
  }

  private companion object {
    /** 207 multistatus 里的 getlastmodified（命名空间前缀不固定，忽略大小写）。 */
    val LASTMOD_REGEX = Regex(
      "<(?:[A-Za-z0-9]+:)?getlastmodified[^>]*>([^<]+)<",
      RegexOption.IGNORE_CASE,
    )
  }
}
