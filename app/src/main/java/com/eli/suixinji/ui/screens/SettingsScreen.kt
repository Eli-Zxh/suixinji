package com.eli.suixinji.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.eli.suixinji.data.SettingsStore
import com.eli.suixinji.llm.NoteLlmEngine
import com.eli.suixinji.ui.components.KeyValueRow
import com.eli.suixinji.ui.components.MonospaceBlock
import com.eli.suixinji.ui.components.SectionCard
import com.eli.suixinji.ui.components.StatusPill
import com.eli.suixinji.ui.components.Tone
import com.eli.suixinji.ui.components.prettyWorkplacePath
import com.eli.suixinji.util.AppLog
import com.eli.suixinji.workspace.ModelImporter
import com.eli.suixinji.workspace.PhotoMatcher
import com.eli.suixinji.workspace.WebDavClient
import com.eli.suixinji.workspace.WorkspaceStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(
  modifier: Modifier = Modifier,
  store: SettingsStore,
  ws: WorkspaceStore,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val workplace by store.workplaceTreeUri.collectAsState(initial = null)

  val picker = rememberLauncherForActivityResult(
    ActivityResultContracts.OpenDocumentTree()
  ) { uri ->
    if (uri != null) {
      runCatching {
        context.contentResolver.takePersistableUriPermission(
          uri,
          Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
      }
      scope.launch { store.setWorkplaceTreeUri(uri.toString()) }
    }
  }

  // ---- 模型导入状态 ----
  var importProgress by remember { mutableStateOf<Float?>(null) }
  var modelStatus by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
  var llmImportProgress by remember { mutableStateOf<Float?>(null) }
  var llmStatus by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

  fun refreshModelStatus() {
    val dir = File(context.filesDir, TranscribeController.MODELS_DIR)
    val model = File(dir, "model.int8.onnx")
    val tokens = File(dir, "tokens.txt")
    val vad = File(context.filesDir, TranscribeController.VAD_PATH)
    modelStatus = if (model.isFile && tokens.isFile && vad.isFile) {
      true to "已就绪（${"%.0f".format(model.length() / 1e6)}MB）"
    } else {
      val missing = listOfNotNull(
        if (!model.isFile) "model.int8.onnx" else null,
        if (!tokens.isFile) "tokens.txt" else null,
        if (!vad.isFile) "silero_vad.onnx" else null,
      )
      false to "缺少 ${missing.joinToString("、")}"
    }
  }

  fun refreshLlmStatus() {
    val f = File(context.filesDir, NoteLlmEngine.MODEL_PATH)
    llmStatus = if (f.isFile && f.length() > 1_000_000L) {
      true to "已就绪（${"%.2f".format(f.length() / 1e9)}GB）"
    } else {
      false to "未导入（${NoteLlmEngine.MODEL_NAME}）"
    }
  }

  LaunchedEffect(Unit) {
    refreshModelStatus()
    refreshLlmStatus()
  }

  val modelPicker = rememberLauncherForActivityResult(
    ActivityResultContracts.OpenDocumentTree(),
  ) { treeUri ->
    if (treeUri == null) {
      AppLog.w("识别模型导入：用户取消或未选择目录")
      return@rememberLauncherForActivityResult
    }
    scope.launch {
      importProgress = 0f
      val report = ModelImporter.importFromTree(
        context, treeUri,
        mapOf(
          "model.int8.onnx" to File(context.filesDir, "${TranscribeController.MODELS_DIR}/model.int8.onnx"),
          "tokens.txt" to File(context.filesDir, "${TranscribeController.MODELS_DIR}/tokens.txt"),
          "silero_vad.onnx" to File(context.filesDir, TranscribeController.VAD_PATH),
        ),
      ) { p -> importProgress = p }
      importProgress = null
      refreshModelStatus()
      if (!report.ok) {
        modelStatus = modelStatus?.copy(second = report.describe() + "（详细原因见日志 SuixinJi）")
      }
    }
  }

  // ---- 归纳模型导入（选单个 .litertlm 文件；ASR 是多文件才用文件夹，这里只要一个文件） ----
  val llmPicker = rememberLauncherForActivityResult(
    ActivityResultContracts.OpenDocument(),
  ) { fileUri ->
    if (fileUri == null) {
      AppLog.w("归纳模型导入：用户取消或未选择文件")
      return@rememberLauncherForActivityResult
    }
    scope.launch {
      llmImportProgress = 0f
      val err = ModelImporter.importFromFile(
        context, fileUri,
        File(context.filesDir, NoteLlmEngine.MODEL_PATH),
      ) { p -> llmImportProgress = p }
      llmImportProgress = null
      refreshLlmStatus()
      if (err != null) {
        val ready = llmStatus?.first ?: false
        llmStatus = ready to "导入失败：$err（详细原因见日志 SuixinJi）"
      }
    }
  }

  // ---- 日志导出（M2.10b 诊断）：打包 files/logs 下全部日志 + 设备信息成 zip ----
  // 无 adb 的设备靠这个把历史日志打包发回给开发者
  val versionName = remember {
    runCatching {
      context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "1.0"
  }
  var logExportMsg by remember { mutableStateOf<String?>(null) }
  var logExportBusy by remember { mutableStateOf(false) }
  val logSaver = rememberLauncherForActivityResult(
    ActivityResultContracts.CreateDocument("application/zip"),
  ) { dest ->
    if (dest == null) return@rememberLauncherForActivityResult
    logExportBusy = true
    logExportMsg = null
    scope.launch {
      val result = withContext(Dispatchers.IO) {
        runCatching {
          context.contentResolver.openOutputStream(dest, "wt")?.use { os ->
            java.util.zip.ZipOutputStream(os.buffered()).use { zip ->
              fun addEntry(name: String, content: String) {
                zip.putNextEntry(java.util.zip.ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
              }
              fun addFile(name: String, f: File) {
                zip.putNextEntry(java.util.zip.ZipEntry(name))
                f.inputStream().use { it.copyTo(zip, 64 * 1024) }
                zip.closeEntry()
              }
              // 设备与环境快照（开源反馈诊断的第一入口——机型/ROM/内存/设置一次看全）
              val now = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                .format(java.util.Date())
              val mi = android.app.ActivityManager.MemoryInfo().also { m ->
                (context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as? android.app.ActivityManager)
                  ?.getMemoryInfo(m)
              }
              val totalRamMb = mi.totalMem / (1024 * 1024)
              val availRamMb = mi.availMem / (1024 * 1024)
              val backend = runCatching { store.llmBackend.firstOrNull() ?: "auto" }.getOrNull() ?: "auto"
              val noteLang = runCatching { store.noteLanguage.firstOrNull() ?: "zh" }.getOrNull() ?: "zh"
              val modelFile = File(context.filesDir, com.eli.suixinji.llm.NoteLlmEngine.MODEL_PATH)
                .takeIf { it.isFile }
              addEntry(
                "info.txt",
                buildString {
                  appendLine("app=suixinji version=$versionName")
                  appendLine("exported=$now")
                  appendLine("device=${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} product=${android.os.Build.PRODUCT}")
                  appendLine("android=${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})")
                  appendLine("rom=${android.os.Build.DISPLAY}")
                  appendLine("abi=${android.os.Build.SUPPORTED_ABIS.joinToString(",")}")
                  appendLine("cores=${Runtime.getRuntime().availableProcessors()}")
                  appendLine("ram: total=${totalRamMb}MB avail=${availRamMb}MB lowMemory=${mi.lowMemory}")
                  appendLine("settings: llmBackend=$backend noteLanguage=$noteLang asrThreads=${com.eli.suixinji.asr.SenseVoiceEngine.DEFAULT_THREADS}")
                  appendLine(
                    "asrModel=" + File(context.filesDir, "models/sense-voice-small-int8")
                      .listFiles()?.joinToString(",") { "${it.name}:${it.length()}" }.orEmpty(),
                  )
                  appendLine("llmModel=" + (modelFile?.let { "${it.name}:${it.length()}" } ?: "未找到"))
                  appendLine()
                  appendLine("包内文件清单与用途：")
                  appendLine("  logs/app.log(.old) —— 全量运行日志（会话头含版本/机型；MEM[...] 为内存采样）")
                  appendLine("  logs/crash.log —— Java 层未捕获异常（堆栈+最后300条日志现场）")
                  appendLine("  logs/native_crash.log —— 原生信号崩溃（SIGSEGV等：信号/出错地址/回溯地址）")
                  appendLine("  logs/shim.log —— OpenCL 命名空间穿透过程（GPU 探活诊断）")
                  appendLine("  logs/running.json —— 任务心跳（存在=上次任务未正常结束，进程被杀）")
                },
              )
              val files = File(context.filesDir, "logs").listFiles()
                ?.filter { it.isFile }?.sortedBy { it.name }.orEmpty()
              if (files.isEmpty()) {
                addEntry("README.txt", "日志目录为空（应用尚未写文件日志）\n")
              }
              files.forEach { f ->
                try {
                  addFile("logs/${f.name}", f)
                } catch (t: Throwable) {
                  addEntry("logs/${f.name}.error.txt", "读取失败：${t.message}\n")
                }
              }
            }
          } != null
        }.getOrElse { false }
      }
      logExportBusy = false
      logExportMsg = if (result) {
        "日志包已保存（含设备信息与全部历史日志），请把该 zip 文件发回给开发者"
      } else {
        "导出失败，请重试或换一个保存位置"
      }
    }
  }

  // ---- WebDAV ----
  var davUrl by remember { mutableStateOf("") }
  var davUser by remember { mutableStateOf("") }
  var davPass by remember { mutableStateOf("") }
  var showDavPass by remember { mutableStateOf(false) }
  var davDir by remember { mutableStateOf("suixinji") }
  var davHasConfig by remember { mutableStateOf(false) }
  var davTestMsg by remember { mutableStateOf<String?>(null) }
  var davSyncMsg by remember { mutableStateOf<String?>(null) }
  var davBusy by remember { mutableStateOf(false) }

  // ---- 照片自动插图权限（从系统授权页返回时刷新） ----
  var allFilesGranted by remember { mutableStateOf(PhotoMatcher.hasAllFilesAccess()) }
  val lifecycleOwner = LocalLifecycleOwner.current
  DisposableEffect(lifecycleOwner) {
    val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_RESUME) allFilesGranted = PhotoMatcher.hasAllFilesAccess()
    }
    lifecycleOwner.lifecycle.addObserver(obs)
    onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
  }

  LaunchedEffect(Unit) {
    val url = store.webdavUrl.firstOrNull()
    val user = store.webdavUser.firstOrNull()
    val pass = store.webdavPassword.firstOrNull()
    val dir = store.webdavRemoteDir.firstOrNull()
    if (url != null) {
      davUrl = url
      davUser = user.orEmpty()
      davPass = pass.orEmpty()
      davDir = dir ?: "suixinji"
      davHasConfig = true
    }
  }

  Column(
    modifier = modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text(
      text = "设置",
      style = MaterialTheme.typography.headlineSmall,
      color = MaterialTheme.colorScheme.primary,
    )

    SectionCard(
      title = "笔记库目录（workplace）",
      subtitle = "根目录存笔记 *.md；audio/ 存录音与字幕；picture/ 存插图（对齐 Obsidian 规范）",
    ) {
      KeyValueRow("当前", prettyWorkplacePath(workplace))
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { picker.launch(null) }) {
          Text(if (workplace.isNullOrBlank()) "选择目录" else "更换目录")
        }
        if (!workplace.isNullOrBlank()) {
          TextButton(onClick = {
            runCatching {
              context.contentResolver.releasePersistableUriPermission(
                Uri.parse(workplace),
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
              )
            }
            scope.launch { store.clearWorkplaceTreeUri() }
          }) { Text("清除授权") }
        }
      }
    }

    SectionCard(
      title = "离线识别模型",
      subtitle = "SenseVoice-Small int8 (~239MB) + silero VAD (~0.6MB)",
    ) {
      modelStatus?.let { (ready, msg) ->
        Row(verticalAlignment = Alignment.CenterVertically) {
          StatusPill(if (ready) "已就绪" else "未就绪", if (ready) Tone.Good else Tone.Warn)
          Text(
            "  $msg",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
      importProgress?.let { p ->
        LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
      }
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
          enabled = importProgress == null,
          onClick = { modelPicker.launch(null) },
        ) { Text(if (modelStatus?.first == true) "重新导入" else "导入模型") }
      }
      MonospaceBlock(
        text = "导入方法二选一：\n" +
          "1) 把三个文件放进手机某个文件夹（如 Download/suixinji-models），点「导入模型」选中该文件夹；\n" +
          "2) adb push model.int8.onnx tokens.txt silero_vad.onnx /sdcard/Download/ 后按 1) 操作。\n\n" +
          "文件清单：\n" +
          "  model.int8.onnx\n" +
          "  tokens.txt\n" +
          "  silero_vad.onnx",
      )
    }

    SectionCard(
      title = "归纳模型（可选）",
      subtitle = "Gemma-4 E2B（.litertlm，约 2.4GB），用于 AI 归纳笔记",
    ) {
      llmStatus?.let { (ready, msg) ->
        Row(verticalAlignment = Alignment.CenterVertically) {
          StatusPill(if (ready) "已就绪" else "未导入", if (ready) Tone.Good else Tone.Warn)
          Text(
            "  $msg",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
      llmImportProgress?.let { p ->
        LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
      }
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
          enabled = llmImportProgress == null,
          onClick = { llmPicker.launch(arrayOf("*/*")) },
        ) { Text(if (llmStatus?.first == true) "重新导入" else "导入模型") }
      }
      MonospaceBlock(
        text = "导入方法二选一：\n" +
          "1) 点「导入模型」，在文件选择器里直接选中 gemma-4-E2B-it.litertlm 文件；\n" +
          "2) adb push gemma-4-E2B-it.litertlm /sdcard/Download/ 后按 1) 操作。\n\n" +
          "注意：首次归纳加载模型需数秒到数十秒；不用时不占内存。",
      )
    }

    // ---- M2.10：推理后端策略 + 笔记语言 ----
    val llmBackend by store.llmBackend.collectAsState(initial = "auto")
    val noteLanguage by store.noteLanguage.collectAsState(initial = "zh")

    SectionCard(
      title = "推理后端",
      subtitle = "归纳笔记用哪个算力（对话页同样生效），即时生效",
    ) {
      val options = listOf(
        "auto" to "自动（省电优先：GPU→NPU→CPU 逐级探活）",
        "npu" to "NPU（实测可用，耗电低，速度略慢）",
        "cpu" to "CPU（速度优先，发热与耗电更高）",
        "gpu" to "GPU（本机 ROM 屏蔽厂商 OpenCL，仅其他设备可用）",
      )
      options.forEach { (value, label) ->
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .clickable { scope.launch { store.setLlmBackend(value) } },
          verticalAlignment = Alignment.CenterVertically,
        ) {
          RadioButton(
            selected = llmBackend == value,
            onClick = { scope.launch { store.setLlmBackend(value) } },
          )
          Text(label, style = MaterialTheme.typography.bodyMedium)
        }
      }
      Text(
        "强制模式下探活失败会直接报错（不自动降级）；切回「自动」即可恢复逐级探活。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }

    SectionCard(
      title = "笔记语言",
      subtitle = "归纳提示词与识别语种同步切换（即时生效，仅影响之后的新笔记）",
    ) {
      listOf(
        "zh" to "中文（默认；中英混说课程也建议用中文）",
        "en" to "English（英文课程；提示词、标题与自测题均为英文）",
      ).forEach { (value, label) ->
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .clickable { scope.launch { store.setNoteLanguage(value) } },
          verticalAlignment = Alignment.CenterVertically,
        ) {
          RadioButton(
            selected = noteLanguage == value,
            onClick = { scope.launch { store.setNoteLanguage(value) } },
          )
          Text(label, style = MaterialTheme.typography.bodyMedium)
        }
      }
      Text(
        "中文课程请勿切 English：识别语种会同步切到英文，中文内容识别质量会下降。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }

    SectionCard(
      title = "照片自动插图",
      subtitle = "归纳笔记时按拍摄时间把相册照片插入对应段落",
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        StatusPill(
          if (allFilesGranted) "已授权" else "未授权",
          if (allFilesGranted) Tone.Good else Tone.Warn,
        )
        Text(
          if (allFilesGranted) "  归纳完成自动匹配照片插入，无需手动插图"
          else "  需要「所有文件访问」权限，用于按时间扫描相册",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      if (!allFilesGranted) {
        Button(onClick = {
          // 先尝试直达本应用的授权页；设备不支持时退到通用列表页
          val direct = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${context.packageName}"),
          )
          runCatching { context.startActivity(direct) }.onFailure {
            runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
          }
        }) { Text("去授权") }
        Text(
          "授权仅用于读取 DCIM/Pictures 的拍摄时间做匹配；" +
            "照片会复制进笔记库 picture/ 目录，不修改原图，不上传任何数据。",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }

    SectionCard(
      title = "WebDAV 同步",
      subtitle = "把整个笔记库（笔记 + audio + picture）上传到 WebDAV，与 Obsidian 等软件共用",
    ) {
      OutlinedTextField(
        value = davUrl,
        onValueChange = { davUrl = it },
        label = { Text("服务器地址") },
        placeholder = { Text("https://dav.example.com/dav/") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
      )
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
          value = davUser,
          onValueChange = { davUser = it },
          label = { Text("用户名") },
          modifier = Modifier.weight(1f),
          singleLine = true,
        )
        OutlinedTextField(
          value = davPass,
          onValueChange = { davPass = it },
          label = { Text("密码") },
          modifier = Modifier.weight(1f),
          singleLine = true,
          visualTransformation = if (showDavPass) VisualTransformation.None else PasswordVisualTransformation(),
          trailingIcon = {
            IconButton(onClick = { showDavPass = !showDavPass }) {
              Icon(
                if (showDavPass) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                contentDescription = if (showDavPass) "隐藏密码" else "显示密码",
              )
            }
          },
        )
      }
      OutlinedTextField(
        value = davDir,
        onValueChange = { davDir = it },
        label = { Text("远端目录名") },
        supportingText = { Text("同步目标目录（相对 WebDAV 根，不存在会自动创建，支持含空格目录名）") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
      )
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
          enabled = davUrl.isNotBlank() && davUser.isNotBlank() && !davBusy,
          onClick = {
            scope.launch {
              davBusy = true
              davTestMsg = null
              withContext(Dispatchers.IO) {
                WebDavClient(
                  context.contentResolver, davUrl.trim(), davUser.trim(), davPass,
                ).testConnection()
              }.let { err ->
                davTestMsg = if (err == null) "连接成功 ✓" else "连接失败：$err"
              }
              davBusy = false
            }
          },
        ) { Text("测试连接") }
        OutlinedButton(
          enabled = davUrl.isNotBlank() && davUser.isNotBlank() && !davBusy,
          onClick = {
            scope.launch {
              davBusy = true
              davSyncMsg = "同步中…"
              val root = ws.rootDir()
              if (root == null) {
                davSyncMsg = "工作目录不可用，请先授权笔记库目录"
                davBusy = false
                return@launch
              }
              // 先保存配置再同步
              store.setWebdavConfig(davUrl.trim(), davUser.trim(), davPass, davDir.trim())
              davHasConfig = true
              val result = withContext(Dispatchers.IO) {
                WebDavClient(
                  context.contentResolver, davUrl.trim(), davUser.trim(), davPass,
                ).syncAll(root, davDir.trim())
              }
              davSyncMsg = if (result.ok) {
                "同步完成：${result.describe()}"
              } else {
                "部分失败（${result.describe()}）：\n" + result.errors.take(3)
                  .joinToString("\n") { it }
              }
              davBusy = false
            }
          },
        ) { Text("立即同步") }
      }
      davTestMsg?.let {
        Text(
          it,
          style = MaterialTheme.typography.bodySmall,
          color = if (it.contains("成功")) MaterialTheme.colorScheme.primary
          else MaterialTheme.colorScheme.error,
        )
      }
      davSyncMsg?.let {
        Text(
          it,
          style = MaterialTheme.typography.bodySmall,
          color = if (it.startsWith("同步完成")) MaterialTheme.colorScheme.primary
          else MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      Text(
        text = "策略：本地文件比远端新才上传（单向推送，不删除远端文件）。" +
          "密码仅保存在本机设置中。坚果云等支持 WebDAV 的网盘均可使用。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }

    // ---- M2.10b：日志导出（诊断） ----
    SectionCard(
      title = "日志导出（诊断）",
      subtitle = "把设备上的历史运行日志打包成 zip，发给开发者定位问题（无需连电脑）",
    ) {
      Button(
        enabled = !logExportBusy,
        onClick = {
          val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
            .format(java.util.Date())
          logSaver.launch("suixinji-logs-$stamp.zip")
        },
      ) { Text(if (logExportBusy) "打包中…" else "导出日志包（.zip）") }
      logExportMsg?.let {
        Text(
          it,
          style = MaterialTheme.typography.bodySmall,
          color = if (it.startsWith("日志包")) MaterialTheme.colorScheme.primary
          else MaterialTheme.colorScheme.error,
        )
      }
      Text(
        "包含：设备信息（机型/系统/ABI）、app.log（应用全量日志）、shim.log（GPU 加载器日志）。" +
          "不包含任何笔记内容、录音或个人信息。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }

    SectionCard(title = "关于") {
      KeyValueRow("应用", "随心记（SuixinJi）")
      KeyValueRow("包名", context.packageName)
      KeyValueRow("版本", versionName)
      KeyValueRow("阶段", "M2.10")
      Text(
        text = "全离线链路：录音/导入音频 → SenseVoice 识别 → SRT 字幕（可编辑改错）→ " +
          "本地 LLM 三阶段归纳 → 照片按拍摄时间自动插图 → WebDAV 手动同步。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}
