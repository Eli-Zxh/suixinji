package com.eli.suixinji.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import com.eli.suixinji.asr.SubtitleCue
import com.eli.suixinji.asr.TranscriptSegment
import com.eli.suixinji.note.NoteComposer
import com.eli.suixinji.srt.SrtParser
import com.eli.suixinji.srt.SrtWriter
import com.eli.suixinji.util.AppLog
import com.eli.suixinji.workspace.WorkspaceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 字幕页：audio/<stem>.srt 的分段视图——时间戳 + 文本列表，
 * 文本可直接改错字（改完保存回写 srt，可再重新归纳提升质量）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SrtScreen(
  stem: String,
  ws: WorkspaceStore,
  onBack: () -> Unit,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var segments by remember { mutableStateOf<List<TranscriptSegment>>(emptyList()) }
  var saving by remember { mutableStateOf(false) }
  var statusMsg by remember { mutableStateOf<String?>(null) }
  var loaded by remember { mutableStateOf(false) }

  LaunchedEffect(stem) {
    withContext(Dispatchers.IO) {
      val doc: DocumentFile? = ws.audioDir()?.listFiles()?.firstOrNull { it.name == "$stem.srt" }
      val text = doc?.let {
        context.contentResolver.openInputStream(it.uri)?.use { s ->
          s.readBytes().toString(Charsets.UTF_8)
        }
      }
      if (text != null) segments = SrtParser.parse(text)
    }
    loaded = true
  }

  fun save() {
    if (saving) return
    scope.launch {
      saving = true
      val ok = withContext(Dispatchers.IO) {
        runCatching {
          val dir = ws.audioDir() ?: return@runCatching false
          val doc = dir.findFile("$stem.srt") ?: return@runCatching false
          val cues = segments.map { SubtitleCue(it.startMs, it.endMs, it.text.trim()) }
          // 保留 srt 尾部的录音时间标记（改错字不能弄丢它，照片插图靠它定位录音时段）
          val recEnd = context.contentResolver.openInputStream(doc.uri)?.use {
            SrtWriter.extractRecordingEndMs(it.readBytes().toString(Charsets.UTF_8))
          } ?: 0L
          val text = SrtWriter.render(cues, recEnd)
          context.contentResolver.openOutputStream(doc.uri, "wt")?.use { os ->
            os.write(text.toByteArray(Charsets.UTF_8))
            os.flush()
          } != null
        }.getOrDefault(false)
      }
      saving = false
      statusMsg = if (ok) {
        AppLog.i("字幕保存：$stem.srt 段数=${segments.size}")
        "已保存"
      } else {
        AppLog.e("字幕保存失败：$stem.srt")
        "保存失败"
      }
    }
  }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("$stem.srt") },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
          }
        },
        actions = {
          IconButton(onClick = { save() }, enabled = !saving) {
            Icon(Icons.Filled.Save, contentDescription = "保存")
          }
        },
      )
    },
  ) { padding ->
    if (loaded && segments.isEmpty()) {
      Text(
        "字幕不存在或为空",
        modifier = Modifier.padding(padding).padding(16.dp),
        color = MaterialTheme.colorScheme.error,
      )
      return@Scaffold
    }
    LazyColumn(
      modifier = Modifier
        .fillMaxSize()
        .padding(padding),
      contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      item {
        statusMsg?.let {
          Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
        Text(
          "可直接修改每段文字（改错字），保存后可重新归纳",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      itemsIndexed(segments) { i, seg ->
        var text by remember(seg.startMs, seg.endMs) { mutableStateOf(seg.text) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(
            NoteComposer.formatDur(seg.startMs),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 18.dp),
          )
          OutlinedTextField(
            value = text,
            onValueChange = {
              text = it
              segments = segments.toMutableList().also { l ->
                l[i] = seg.copy(text = it)
              }
            },
            modifier = Modifier.fillMaxWidth(),
            textStyle = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp),
            minLines = 1,
          )
        }
      }
      item {
        androidx.compose.material3.Button(
          onClick = { save() },
          enabled = !saving,
          modifier = Modifier.fillMaxWidth(),
        ) { Text(if (saving) "保存中…" else "保存修改") }
      }
    }
  }
}
