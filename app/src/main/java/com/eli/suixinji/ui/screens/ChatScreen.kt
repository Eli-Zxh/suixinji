package com.eli.suixinji.ui.screens

import android.content.Context
import android.media.MediaRecorder
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.eli.suixinji.asr.SenseVoiceEngine
import com.eli.suixinji.audio.AudioDecoder
import com.eli.suixinji.data.SettingsStore
import com.eli.suixinji.llm.NoteLlmEngine
import com.eli.suixinji.util.AppLog
import com.eli.suixinji.util.TaskTrace
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Conversation
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 一条对话消息：role = user（用户）/ ai（模型）/ sys（系统提示，居中灰字）。 */
private data class ChatMsg(val role: String, val text: String)

/**
 * 彩蛋页：与本地 Gemma 自由对话，兼作引擎测试页——
 * 首次发送时加载模型并把「实际后端」（GPU 探活结果）打到这里和日志。
 * 语音输入用应用内离线 ASR（SenseVoice）：录音 → 识别 → 文字进输入框（可改后发送），
 * 不依赖系统语音服务（荣耀无 GMS，SpeechRecognizer 不可靠）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(settings: SettingsStore) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  // 后端策略与笔记语言随设置读取（对话系统提示词语言跟随笔记语言）
  val llmBackend by settings.llmBackend.collectAsState(initial = "auto")
  val noteLang by settings.noteLanguage.collectAsState(initial = "zh")
  val msgs = remember { mutableStateListOf<ChatMsg>() }
  var input by remember { mutableStateOf("") }
  var busy by remember { mutableStateOf(false) }           // LLM 正在生成
  var llmStatus by remember { mutableStateOf<String?>(null) }
  var engine by remember { mutableStateOf<NoteLlmEngine?>(null) }
  var conversation by remember { mutableStateOf<Conversation?>(null) }
  var recording by remember { mutableStateOf(false) }
  var asrBusy by remember { mutableStateOf(false) }
  val recorderHolder = remember { arrayOfNulls<MediaRecorder>(1) }
  val recPathHolder = remember { arrayOfNulls<File>(1) }
  var asr by remember { mutableStateOf<SenseVoiceEngine?>(null) }

  val asrReady = remember {
    File(context.filesDir, TranscribeController.MODELS_DIR).resolve(SenseVoiceEngine.MODEL_FILE).isFile &&
      File(context.filesDir, TranscribeController.VAD_PATH).isFile
  }

  DisposableEffect(Unit) {
    onDispose {
      recorderHolder[0]?.let { mr ->
        runCatching { mr.stop() }
        runCatching { mr.release() }
      }
      // 内存/崩溃修复（M2.9）：sendMessage 是阻塞 JNI 调用且不可中断——
      // 生成中立刻 close 引擎会让 IO 线程 use-after-free 原生崩溃。
      // busy 时挂独立作用域等本轮生成完再释放；空闲则立即释放。
      if (busy) {
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
          while (busy) kotlinx.coroutines.delay(300)
          runCatching { conversation?.close() }
          runCatching { engine?.close() }
        }
      } else {
        runCatching { conversation?.close() }
        runCatching { engine?.close() }
      }
      runCatching { asr?.release() }
    }
  }

  suspend fun ensureLlm() {
    var e = engine
    if (e == null) {
      llmStatus = "正在加载模型…（首次数秒到数十秒；探活失败会自动降级，详见日志）"
      AppLog.i("对话：模型加载开始 backend=$llmBackend")
      val t0 = System.currentTimeMillis()
      e = NoteLlmEngine(context)
      // M2.10：后端策略跟随设置（auto/cpu/npu/gpu），探活失败强制模式直接报错
      e.initialize(llmBackend)
      engine = e
      val sysPrompt = if (noteLang == "en") {
        "You are a concise, accurate study assistant. Answer in English with Markdown; " +
          "say so when unsure, never fabricate."
      } else {
        "你是简洁、准确的中文学习助手，用 Markdown 回答；不确定的内容要明说，不要编造。"
      }
      conversation = e.conversation(sysPrompt)
      llmStatus = null
      AppLog.i("对话：模型就绪 backend=${e.backendLabel} 耗时=${System.currentTimeMillis() - t0}ms")
      AppLog.mem("对话模型就绪")
      msgs.add(ChatMsg("sys", "模型就绪 · 当前后端=${e.backendLabel}（探活详情见日志）"))
    } else if (conversation == null) {
      conversation = e.conversation(
        if (noteLang == "en") {
          "You are a concise, accurate study assistant. Answer in English with Markdown."
        } else {
          "你是简洁、准确的中文学习助手，用 Markdown 回答。"
        },
      )
    }
  }

  fun send() {
    val text = input.trim()
    if (text.isEmpty() || busy) return
    input = ""
    msgs.add(ChatMsg("user", text))
    busy = true
    val turn = msgs.count { it.role == "user" }
    AppLog.i("对话：第${turn}轮发送 promptChars=${text.length}")
    val t0 = System.currentTimeMillis()
    scope.launch(Dispatchers.IO) {
      try {
        ensureLlm()
        TaskTrace.begin("对话")
        TaskTrace.update("第${turn}轮推理中")
        val reply = conversation?.sendMessage(text)
        TaskTrace.end()
        val out = reply?.contents?.contents
          ?.firstNotNullOfOrNull { it as? Content.Text }?.text.orEmpty()
        AppLog.i(
          "对话：第${turn}轮回复 replyChars=${out.length} " +
            "耗时=${System.currentTimeMillis() - t0}ms backend=${engine?.backendLabel ?: "?"}",
        )
        withContext(Dispatchers.Main) {
          msgs.add(ChatMsg("ai", out.ifBlank { "（空回复，可重试）" }))
          busy = false
        }
      } catch (t: Throwable) {
        AppLog.e("对话：发送失败", t)
        withContext(Dispatchers.Main) {
          msgs.add(ChatMsg("sys", "出错了：${t.message ?: t.javaClass.simpleName}"))
          busy = false
        }
      }
    }
  }

  fun startRec() {
    if (recording || busy) return
    runCatching {
      val path = File(context.cacheDir, "chat-voice-${System.currentTimeMillis()}.m4a")
      val mr = MediaRecorder(context)
      mr.setAudioSource(MediaRecorder.AudioSource.MIC)
      mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
      mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
      mr.setAudioSamplingRate(16000)
      mr.setAudioEncodingBitRate(64000)
      mr.setOutputFile(path.absolutePath)
      mr.prepare()
      mr.start()
      recorderHolder[0] = mr
      recPathHolder[0] = path
      recording = true
      msgs.add(ChatMsg("sys", "录音中… 点方块结束并识别"))
    }.onFailure { AppLog.e("对话：录音启动失败", it) }
  }

  fun stopRecAndTranscribe() {
    val mr = recorderHolder[0] ?: return
    val path = recPathHolder[0]
    recorderHolder[0] = null
    recPathHolder[0] = null
    recording = false
    runCatching { mr.stop() }
    runCatching { mr.release() }
    if (path == null || !path.exists() || path.length() < 2048) {
      msgs.add(ChatMsg("sys", "录音太短，再试一次"))
      path?.delete()
      return
    }
    asrBusy = true
    val asrT0 = System.currentTimeMillis()
    scope.launch(Dispatchers.IO) {
      val text = try {
        val e = asr ?: SenseVoiceEngine(
          modelDir = File(context.filesDir, TranscribeController.MODELS_DIR),
          vadModelFile = File(context.filesDir, TranscribeController.VAD_PATH),
        ).also { asr = it }
        val sb = StringBuilder()
        AudioDecoder.decodeWithProgress(context, Uri.fromFile(path), onProgress = {}) { chunk ->
          e.feed(chunk) { seg -> sb.append(seg.text) }
        }
        e.flush { seg -> sb.append(seg.text) }
        sb.toString().trim()
      } catch (t: Throwable) {
        AppLog.e("对话：语音识别失败", t)
        null
      } finally {
        path.delete()
      }
      AppLog.i("对话：语音识别 chars=${text?.length ?: -1} 耗时=${System.currentTimeMillis() - asrT0}ms")
      withContext(Dispatchers.Main) {
        asrBusy = false
        if (text.isNullOrBlank()) {
          msgs.add(ChatMsg("sys", "没识别到内容，再试一次"))
        } else {
          input = (if (input.isBlank()) "" else "$input ") + text
        }
      }
    }
  }

  Scaffold(
    topBar = { TopAppBar(title = { Text("AI 对话 · 本机模型") }) },
  ) { padding ->
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(padding)
        .padding(horizontal = 12.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      LazyColumn(
        modifier = Modifier
          .weight(1f)
          .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(vertical = 8.dp),
      ) {
        if (msgs.isEmpty()) {
          item {
            Text(
              "和本地 Gemma 自由对话（多轮上下文）。点麦克风说话，识别文字会填进输入框，可改后发送。" +
                "首次发送会加载模型，并把 GPU 探活结果显示在这里——正好当引擎测试页。",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
        items(msgs) { m -> Bubble(m) }
      }
      llmStatus?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
      }
      if (asrBusy) {
        Text("语音识别中…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        OutlinedTextField(
          value = input,
          onValueChange = { input = it },
          modifier = Modifier.weight(1f),
          placeholder = { Text("输入消息…") },
          maxLines = 4,
          enabled = !busy,
        )
        IconButton(
          onClick = { if (recording) stopRecAndTranscribe() else startRec() },
          enabled = asrReady && !busy && !asrBusy,
        ) {
          Icon(
            if (recording) Icons.Filled.Stop else Icons.Filled.Mic,
            contentDescription = if (recording) "停止录音" else "语音输入",
            tint = if (recording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Button(onClick = { send() }, enabled = !busy && !recording && input.isNotBlank()) {
          Text("发送")
        }
      }
      Spacer(Modifier.height(6.dp))
    }
  }
}

@Composable
private fun Bubble(m: ChatMsg) {
  if (m.role == "sys") {
    Text(
      m.text,
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.fillMaxWidth(),
      textAlign = TextAlign.Center,
    )
    return
  }
  val isUser = m.role == "user"
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
  ) {
    Surface(
      color = if (isUser) MaterialTheme.colorScheme.primaryContainer
      else MaterialTheme.colorScheme.surfaceVariant,
      shape = RoundedCornerShape(12.dp),
    ) {
      Text(
        m.text,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(10.dp),
      )
    }
  }
}
