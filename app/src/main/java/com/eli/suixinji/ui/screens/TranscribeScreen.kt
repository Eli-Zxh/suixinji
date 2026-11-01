package com.eli.suixinji.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.eli.suixinji.audio.RecordService
import com.eli.suixinji.audio.RecordingSession
import com.eli.suixinji.audio.RecordingState
import com.eli.suixinji.note.NoteComposer
import com.eli.suixinji.ui.components.KeyValueRow
import com.eli.suixinji.ui.components.SectionCard
import com.eli.suixinji.ui.components.StatusPill
import com.eli.suixinji.ui.components.Tone
import com.eli.suixinji.workspace.WorkspaceStore
import kotlinx.coroutines.launch

/**
 * 转写页（左侧导航第二项）：录音 / 批量选音频转写 / 一键归纳。
 * 转写只产出 audio/ 下的 .srt 字幕；点「立即归纳此篇」由本地 LLM 从 srt 生成 md 笔记。
 */
@Composable
fun TranscribeScreen(
  controller: TranscribeController,
  llm: LlmController,
  ws: WorkspaceStore,
  recoveredMessage: String?,
  onOpenSettings: () -> Unit,
  onOpenNote: (Uri) -> Unit,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val recordingState by RecordingSession.state.collectAsState()
  var workplaceReady by remember { mutableStateOf(false) }
  var photoStem by remember { mutableStateOf<String?>(null) }   // 独立插图弹窗目标
  var insertMsg by remember { mutableStateOf<String?>(null) }
  var showLlmBatchPicker by remember { mutableStateOf(false) }  // 批量归纳字幕库弹窗

  LaunchedEffect(Unit) {
    workplaceReady = ws.isReady()
  }

  val filePicker = rememberLauncherForActivityResult(
    ActivityResultContracts.GetContent(),
  ) { uri -> if (uri != null) controller.transcribe(uri, baseName = null) }

  val multiFilePicker = rememberLauncherForActivityResult(
    ActivityResultContracts.GetMultipleContents(),
  ) { uris -> if (uris.isNotEmpty()) controller.transcribeMany(uris) }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    recoveredMessage?.let {
      SectionCard(title = "恢复提示") {
        Text(it, style = MaterialTheme.typography.bodySmall)
      }
    }
    insertMsg?.let {
      SectionCard(title = "插图完成") {
        Text(it, style = MaterialTheme.typography.bodySmall)
      }
    }

    // ---- 模型状态 ----
    SectionCard(
      title = "离线引擎",
      subtitle = "SenseVoice-Small(int8) + silero VAD，全程本地",
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        if (controller.modelReady()) {
          StatusPill("模型就绪", Tone.Good)
        } else {
          StatusPill("模型未导入", Tone.Warn)
          Text(
            "  到「设置」导入模型文件",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }

    // ---- 录音 ----
    SectionCard(title = "录音", subtitle = "边录边写盘，异常退出也不丢数据") {
      when (val st = recordingState) {
        is RecordingState.Recording -> {
          KeyValueRow("已录时长", NoteComposer.formatDur(st.elapsedMs))
          LinearProgressIndicator(
            progress = { st.level },
            modifier = Modifier.fillMaxWidth(),
          )
          Button(onClick = { RecordService.stop(context) }) {
            Icon(Icons.Filled.Stop, contentDescription = null)
            Text(" 停止录音")
          }
        }
        is RecordingState.Finalizing -> {
          Text("正在保存…", style = MaterialTheme.typography.bodySmall)
        }
        is RecordingState.Done -> {
          KeyValueRow("已保存", st.wavName ?: "（保存失败）")
          KeyValueRow("时长", NoteComposer.formatDur(st.durationMs))
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
              enabled = st.wavName != null && !controller.isBusy() && controller.modelReady(),
              onClick = { st.wavName?.let { controller.transcribeRecording(it) } },
            ) { Text("转写这段录音") }
            OutlinedButton(onClick = { RecordingSession.reset() }) { Text("完成") }
          }
        }
        is RecordingState.Failed -> {
          Text(
            "录音失败：${st.message}",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
          )
          OutlinedButton(onClick = { RecordingSession.reset() }) { Text("重置") }
        }
        RecordingState.Idle -> {
          Button(
            onClick = {
              if (workplaceReady) RecordService.start(context) else onOpenSettings()
            },
          ) {
            Icon(Icons.Filled.Mic, contentDescription = null)
            Text(if (workplaceReady) " 开始录音" else " 先去设置授权工作目录")
          }
        }
      }
    }

    // ---- 音频文件转写（支持批量） ----
    SectionCard(
      title = "转写音频文件",
      subtitle = "转写产出字幕（audio/同名.srt），不直接生成笔记",
    ) {
      if (controller.pendingCount > 0) {
        Text(
          "等待管线：队列中还有 ${controller.pendingCount} 个待转写（可继续排队）",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      when (val p = controller.phase) {
        is TranscribePhase.Decoding -> {
          if (controller.batchTotal > 0) {
            KeyValueRow("批量进度", "${controller.batchIndex}/${controller.batchTotal}")
          }
          KeyValueRow("解码中", "%.0f%%".format(p.progress * 100))
          LinearProgressIndicator(progress = { p.progress }, modifier = Modifier.fillMaxWidth())
        }
        is TranscribePhase.Recognizing -> {
          if (controller.batchTotal > 0) {
            KeyValueRow("批量进度", "${controller.batchIndex}/${controller.batchTotal}")
          }
          KeyValueRow("识别中", "已出 ${p.segmentCount} 段")
          LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        TranscribePhase.Saving -> {
          Text("正在写入字幕…")
        }
        is TranscribePhase.Done -> {
          KeyValueRow("字幕", p.srtName)
          KeyValueRow(
            "耗时",
            "%.1fs（音频 %s）".format(
              p.transcribeMs / 1000.0,
              NoteComposer.formatDur(p.durationMs),
            ),
          )
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
              enabled = llm.modelReady() && !llm.busy() && !controller.isBusy(),
              onClick = {
                val stems = controller.batchStems
                if (stems.size > 1) {
                  // 批量转写完成 → 主操作是整批 AI 归纳（逐篇排队，逐篇落盘）
                  llm.summarizeFromSrtMany(stems)
                } else {
                  llm.summarizeFromSrt(p.srtName.substringBeforeLast('.'))
                }
              },
            ) {
              Text(
                if (controller.batchStems.size > 1) "批量归纳本次 ${controller.batchStems.size} 篇"
                else "立即归纳此篇",
              )
            }
            OutlinedButton(onClick = { controller.reset() }) { Text("完成") }
          }
          Text(
            if (llm.modelReady()) "  归纳完成后到「笔记」页查看；也可稍后在笔记页对该字幕归纳"
            else "  未导入归纳模型，可稍后在「笔记」页归纳（设置里导入）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        is TranscribePhase.Failed -> {
          Text(
            "失败：${p.message}",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
          )
          OutlinedButton(onClick = { controller.reset() }) { Text("重置") }
        }
        TranscribePhase.Idle -> {
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
              enabled = controller.modelReady(),
              onClick = { filePicker.launch("audio/*") },
            ) { Text("选择音频文件") }
            OutlinedButton(
              enabled = controller.modelReady(),
              onClick = { multiFilePicker.launch("audio/*") },
            ) { Text("批量选择") }
          }
        }
      }
    }

    // ---- AI 归纳（接续刚转写的字幕） ----
    SectionCard(
      title = "AI 归纳",
      subtitle = "本地 LLM 三阶段整理：全局概览 → 分段总结 → 审核定稿",
    ) {
      when (val lp = llm.phase) {
        is LlmPhase.LoadingModel -> {
          Text("正在加载模型…（数秒到数十秒）", style = MaterialTheme.typography.bodySmall)
          LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        is LlmPhase.Overview -> {
          KeyValueRow("第 1 步 全局概览", "长文本分片 ${lp.part}/${lp.total}")
          LinearProgressIndicator(
            progress = { lp.part.toFloat() / lp.total },
            modifier = Modifier.fillMaxWidth(),
          )
        }
        is LlmPhase.Sections -> {
          KeyValueRow("第 2 步 分段总结", "段落 ${lp.part}/${lp.total}")
          LinearProgressIndicator(
            progress = { lp.part.toFloat() / lp.total },
            modifier = Modifier.fillMaxWidth(),
          )
        }
        is LlmPhase.Reviewing -> {
          Text(
            if (lp.retry) "第 3 步 审核与自测（首轮打回，仅重做对应部分后复审）…"
            else "第 3 步 审核与自测（通过即出题，打回只重做对应部分）…",
            style = MaterialTheme.typography.bodySmall,
          )
          LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        LlmPhase.Questions -> {
          Text("生成「问题与思考」…", style = MaterialTheme.typography.bodySmall)
          LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        is LlmPhase.Done -> {
          KeyValueRow("笔记", lp.mdName)
          KeyValueRow("耗时", "%.1fs（模型 %s）".format(lp.elapsedMs / 1000.0, lp.backend))
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
              scope.launch {
                ws.rootDir()?.listFiles()?.firstOrNull { it.name == lp.mdName }
                  ?.let { onOpenNote(it.uri) }
              }
            }) { Text("打开笔记") }
            OutlinedButton(onClick = {
              photoStem = lp.mdName.substringBeforeLast('.')
            }) { Text("插入图片") }
            OutlinedButton(onClick = { llm.reset() }) { Text("完成") }
          }
        }
        is LlmPhase.Failed -> {
          Text(
            "AI 归纳失败：${lp.message}",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
          )
          OutlinedButton(onClick = { llm.reset() }) { Text("重置") }
        }
        LlmPhase.Idle -> {
          val ready = llm.modelReady()
          val canRun = controller.phase is TranscribePhase.Done &&
            ready && !controller.isBusy() && !llm.busy()
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
              enabled = canRun,
              modifier = Modifier.weight(1f),
              onClick = {
                val stem = controller.lastSourceName.substringBeforeLast('.')
                if (stem.isNotBlank()) llm.summarizeFromSrt(stem)
              },
            ) { Text("归纳", maxLines = 1) }
            OutlinedButton(
              enabled = ready && !llm.busy() && !controller.isBusy(),
              modifier = Modifier.weight(1f),
              onClick = { showLlmBatchPicker = true },
            ) { Text("批量", maxLines = 1) }
          }
          Text(
            when {
              !ready -> "  先到「设置」导入本地模型或配置云端模型"
              controller.phase is TranscribePhase.Done ->
                "  归纳当前字幕，或批量选择 audio/ 里的字幕排队"
              else -> "  先在「转写音频文件」里完成一次转写，或直接点「批量」"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }

    // ---- 批量归纳字幕库弹窗（转写页常驻入口，不依赖「先完成一次转写」） ----
    if (showLlmBatchPicker) {
      var srtItems by remember { mutableStateOf<List<Pair<String, Boolean>>>(emptyList()) } // stem to 已有md
      val batchChecked = remember { mutableStateMapOf<String, Boolean>() }
      LaunchedEffect(Unit) {
        val root = ws.rootDir()
        val audio = ws.audioDir()
        val mds = root?.listFiles()
          ?.filter { it.name?.endsWith(".md") == true }
          ?.mapNotNull { it.name }?.toSet().orEmpty()
        val stems = audio?.listFiles()
          ?.filter { it.name?.endsWith(".srt") == true }
          ?.mapNotNull { it.name?.removeSuffix(".srt") }
          ?.sorted() ?: emptyList()
        srtItems = stems.map { it to ("$it.md" in mds) }
      }
      AlertDialog(
        onDismissRequest = { showLlmBatchPicker = false },
        title = { Text("批量 AI 归纳") },
        text = {
          Column {
            if (!llm.modelReady()) {
              Text(
                "未导入归纳模型，请先到「设置 → 归纳模型」导入 .litertlm 文件",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
              )
            }
            Text(
              "勾选要归纳的字幕（${srtItems.size} 篇可选），按顺序排队执行，每篇约 10~15 分钟，逐篇完成即保存：",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LazyColumn(modifier = Modifier.heightIn(max = 380.dp)) {
              items(srtItems.size) { idx ->
                val (stem, hasMd) = srtItems[idx]
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  verticalAlignment = Alignment.CenterVertically,
                ) {
                  Checkbox(
                    checked = batchChecked[stem] == true,
                    onCheckedChange = { on -> if (on) batchChecked[stem] = true else batchChecked.remove(stem) },
                  )
                  Column(modifier = Modifier.weight(1f)) {
                    Text(stem, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                    Text(
                      if (hasMd) "已有笔记（重新归纳会覆盖）" else "未归纳",
                      style = MaterialTheme.typography.bodySmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                  }
                }
              }
            }
          }
        },
        confirmButton = {
          TextButton(
            enabled = batchChecked.isNotEmpty() && llm.modelReady() && !llm.busy(),
            onClick = {
              llm.summarizeFromSrtMany(batchChecked.keys.toList().sorted())
              showLlmBatchPicker = false
            },
          ) { Text("开始归纳（${batchChecked.size}）") }
        },
        dismissButton = {
          TextButton(onClick = { showLlmBatchPicker = false }) { Text("取消") }
        },
      )
    }

    // ---- 独立插图确认弹窗（工作流末尾入口） ----
    photoStem?.let { stem ->
      PhotoInsertDialog(
        stem = stem,
        ws = ws,
        onDismiss = { photoStem = null },
        onInserted = { n ->
          photoStem = null
          insertMsg = if (n > 0) "已插入 $n 张照片到 $stem.md" else "没有新照片插入（$stem.md）"
        },
      )
    }
  }
}
