package com.eli.suixinji.ui.screens

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.eli.suixinji.util.AppLog
import com.eli.suixinji.workspace.WorkspaceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * 笔记页：WebView 逐行实时预览编辑器（Obsidian live-preview 逻辑）——
 * 平时渲染 Markdown，点按某行进入源码编辑，失焦恢复渲染；保存写回原文件。
 *
 * 图片渲染用「正常网站」方式（M2.6）：页面基址 https://suixinji.local/，
 * markdown 里相对路径 picture/xxx 由浏览器直接发请求，
 * [shouldInterceptRequest] 充当源站服务器从 picture/ 目录回字节流——
 * 原生解码带缓存；此前 JS 桥换 src 的方式真机上一直不可靠。
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun NoteScreen(
  noteUri: Uri,
  ws: WorkspaceStore,
  onBack: () -> Unit,
  onOpenSrt: (stem: String) -> Unit,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val mainHandler = remember { Handler(Looper.getMainLooper()) }

  var content by remember { mutableStateOf("") }          // 已保存内容
  var pending by remember { mutableStateOf("") }          // 编辑器当前内容
  var hasSrt by remember { mutableStateOf(false) }
  var mdName by remember { mutableStateOf("笔记") }
  var saving by remember { mutableStateOf(false) }
  var statusMsg by remember { mutableStateOf<String?>(null) }
  var webViewRef by remember { mutableStateOf<WebView?>(null) }
  var editorReady by remember { mutableStateOf(false) }   // 页面脚本已执行完，可接收 setMarkdown

  // —— 图片性能（M2.7）：插图原图 5~8MB×几十张整载进 WebView 会把页面拖到卡死 ——
  // ① 只回 ≤1280px 的 JPEG 缩略图（显示宽度远小于此）；② LruCache 32MB 兜住重复渲染；
  // ③ picture/ 文件索引只建一次，替代每张图都查 SAF+DataStore。
  val thumbCache = remember {
    object : LruCache<String, ByteArray>(32 * 1024 * 1024) {
      override fun sizeOf(key: String, value: ByteArray): Int = value.size
    }
  }
  val pictureIndex = remember { mutableMapOf<String, Uri>() }

  LaunchedEffect(noteUri) {
    runCatching {
      context.contentResolver.openInputStream(noteUri)?.use { ins ->
        content = ins.readBytes().toString(Charsets.UTF_8)
        pending = content
      }
      val doc = androidx.documentfile.provider.DocumentFile.fromSingleUri(context, noteUri)
      mdName = doc?.name ?: "笔记"
      val stem = mdName.substringBeforeLast('.')
      hasSrt = ws.audioDir()?.listFiles()?.any { it.name == "$stem.srt" } == true
      // 一次建好 picture/ 文件名 → uri 索引（每张图请求都查 SAF 太慢）
      withContext(Dispatchers.IO) {
        ws.picturesDir()?.listFiles()?.forEach { f ->
          f.name?.let { pictureIndex[it] = f.uri }
        }
      }
    }.onFailure { statusMsg = "读取失败：${it.message}" }
  }

  // 内容就绪且编辑器就绪后推送渲染。
  // 不能在 onPageFinished 里直接推：SAF 读文件比页面加载慢，届时 content 还是空串，
  // 且之后无人再推 → 编辑器整页空白（真机实测踩坑）。
  LaunchedEffect(editorReady, content) {
    if (editorReady) {
      val wv = webViewRef ?: return@LaunchedEffect
      wv.evaluateJavascript("window.Editor.setMarkdown(${jsonStr(content)})", null)
      // 自检：确认桥接对象存在、推送了多少内容（排查渲染空白）
      wv.evaluateJavascript("typeof window.Editor") { r ->
        AppLog.d("笔记编辑器：已推送 ${content.length} 字，Editor桥=${r?.trim()?.trim('"')}")
      }
    }
  }

  fun save() {
    if (saving) return
    val wv = webViewRef ?: return
    saving = true
    wv.evaluateJavascript("window.Editor.getMarkdown()") { raw ->
      val text = try {
        JSONObject("{\"v\":$raw}").getString("v")
      } catch (t: Throwable) {
        AppLog.e("笔记保存：解析编辑器内容失败", t)
        null
      }
      scope.launch {
        val ok = text != null && withContext(Dispatchers.IO) {
          runCatching {
            context.contentResolver.openOutputStream(noteUri, "wt")?.use { os ->
              os.write(text!!.toByteArray(Charsets.UTF_8))
              os.flush()
            } != null
          }.getOrDefault(false)
        }
        saving = false
        if (ok && text != null) {
          content = text
          pending = text
          statusMsg = "已保存"
          AppLog.i("笔记保存：$mdName 字符数=${text.length}")
        } else if (text != null) {
          statusMsg = "保存失败（打开输出流失败）"
          AppLog.e("笔记保存失败：$mdName")
        }
      }
    }
  }

  val jsBridge = remember(noteUri) {
    object {
      @JavascriptInterface
      fun onReady() {
        AppLog.d("笔记编辑器：页面脚本就绪")
      }

      @JavascriptInterface
      fun onChanged(md: String?) {
        md ?: return
        mainHandler.post { pending = md }
      }
    }
  }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(mdName) },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
          }
        },
        actions = {
          IconButton(onClick = { save() }, enabled = !saving && pending != content) {
            Icon(Icons.Filled.Save, contentDescription = "保存")
          }
        },
      )
    },
  ) { padding ->
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(padding),
    ) {
      statusMsg?.let {
        Text(
          it,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.primary,
          modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
      }
      AndroidView(
        factory = { ctx ->
          WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
              override fun onPageFinished(view: WebView, url: String?) {
                // 内联脚本此时已执行完（marked.min.js 同步加载），可以接收内容
                editorReady = true
              }

              override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest?,
                error: WebResourceError?,
              ) {
                AppLog.e("笔记编辑器：资源加载失败 ${request?.url} ${error?.description}")
              }

              /** 源站服务器：picture/ 图片与 assets 静态资源都从这里回字节流。
               *  回调在后台线程，可以阻塞读文件。 */
              override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
              ): WebResourceResponse? {
                val url = request.url
                if (url.host != "suixinji.local") return null
                val segs = url.pathSegments
                // picture/<文件名> → 缩略图（≤1280px JPEG，LruCache 兜底），不整载原图
                if (segs.size == 2 && segs[0] == WorkspaceStore.DIR_PICTURE) {
                  val name = segs[1]
                  thumbCache.get(name)?.let {
                    return WebResourceResponse("image/jpeg", null, ByteArrayInputStream(it))
                  }
                  val docUri = pictureIndex[name] ?: return null
                  val bytes = decodePictureThumb(context, docUri) ?: return null
                  thumbCache.put(name, bytes)
                  return WebResourceResponse("image/jpeg", null, ByteArrayInputStream(bytes))
                }
                // 其余（marked.min.js 等）→ assets
                return runCatching {
                  val path = segs.joinToString("/")
                  val mime = when (path.substringAfterLast('.', "")) {
                    "js" -> "application/javascript"
                    "css" -> "text/css"
                    else -> "application/octet-stream"
                  }
                  WebResourceResponse(mime, "utf-8", ctx.assets.open(path))
                }.getOrNull()
              }

              override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                // 链接交给系统浏览器，不在编辑器内跳转
                runCatching {
                  ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                }
                return true
              }
            }
            webChromeClient = object : WebChromeClient() {
              override fun onConsoleMessage(message: ConsoleMessage?): Boolean {
                AppLog.w(
                  "编辑器JS：${message?.message()} (${message?.sourceId()}:${message?.lineNumber()})",
                )
                return true
              }
            }
            addJavascriptInterface(jsBridge, "Android")
            // 带基址加载页面：相对路径 picture/xxx 才能解析到 suixinji.local 域下被拦截
            val html = runCatching {
              ctx.assets.open("editor.html").bufferedReader().use { it.readText() }
            }.getOrElse {
              AppLog.e("笔记编辑器：editor.html 读取失败", it)
              "<html><body>editor.html 读取失败</body></html>"
            }
            loadDataWithBaseURL("https://suixinji.local/", html, "text/html", "utf-8", null)
            webViewRef = this
          }
        },
        onRelease = { it.destroy() },
        modifier = Modifier
          .fillMaxWidth()
          .weight(1f),
      )
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        if (hasSrt) {
          Button(onClick = {
            onOpenSrt(mdName.substringBeforeLast('.'))
          }) { Text("看字幕") }
        }
        OutlinedButton(onClick = { save() }, enabled = !saving && pending != content) {
          Text(if (saving) "保存中…" else "保存")
        }
        OutlinedButton(onClick = {
          val cm = context.getSystemService(ClipboardManager::class.java) ?: return@OutlinedButton
          cm.setPrimaryClip(ClipData.newPlainText("随心记", pending))
          statusMsg = "已复制"
        }) { Text("复制") }
        OutlinedButton(onClick = {
          val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, mdName)
            putExtra(Intent.EXTRA_TEXT, pending)
          }
          runCatching {
            context.startActivity(
              Intent.createChooser(send, "分享").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
          }
        }) { Text("分享") }
      }
    }
  }
}

/**
 * 字符串 → JS 字符串字面量。
 * 必须用 [JSONObject.quote] 才会转义换行/引号；JSONObject.getString 只会原样取回值，
 * 之前误用它导致裸 markdown 直接拼进 JS 源码 → SyntaxError → 编辑器整页空白（真机踩坑）。
 */
private fun jsonStr(s: String): String = JSONObject.quote(s)

/**
 * 解码图片为 ≤1280px 的 JPEG 缩略图字节（编辑器显示宽度远小于此；
 * 整载 5~8MB 原图×几十张是 WebView 卡死的主因）。在拦截器后台线程调用。
 */
private fun decodePictureThumb(context: android.content.Context, uri: Uri): ByteArray? =
  runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use {
      BitmapFactory.decodeStream(it, null, bounds)
    }
    if (bounds.outWidth <= 0) return null
    var sample = 1
    while (bounds.outWidth / sample > 1280 || bounds.outHeight / sample > 1280) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    val bmp = context.contentResolver.openInputStream(uri)?.use {
      BitmapFactory.decodeStream(it, null, opts)
    } ?: return null
    val out = ByteArrayOutputStream()
    bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
    bmp.recycle()
    out.toByteArray()
  }.getOrNull()
