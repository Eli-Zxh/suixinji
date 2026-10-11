package com.eli.suixinji.ui.screens

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.collection.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.eli.suixinji.ui.components.SectionCard
import com.eli.suixinji.util.AppLog
import com.eli.suixinji.workspace.WorkspaceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 笔记主页：按 stem 分组的资源卡片——笔记 md（点击进实时预览编辑器）、
 * 字幕 srt（可看可改错字、勾选批量归纳）、录音、插图缩略。
 * 「AI 重新归纳」收在 ⋮ 菜单里，不再是大按钮。
 *
 * 性能（M2.5）：分组结果带目录指纹缓存——文件清单与修改时间没变就直接复用上次
 * 分组与预览，进主页秒显缓存快照再后台校对；md 只读头部 8KB；缩略图 LruCache。
 * 真机多笔记时曾出现每次进入主页全量重读 md + 重解码缩略图导致的明显卡顿。
 */
@Composable
fun LibraryScreen(
  ws: WorkspaceStore,
  llm: LlmController,
  onOpenNote: (Uri) -> Unit,
  onOpenSrt: (stem: String) -> Unit,
  onGoTranscribe: () -> Unit,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  // 首帧显示优先级：磁盘缓存（冷启动/进程被杀后仍秒显）→ 进程内内存缓存 → 空列表；
  // 然后后台 buildGroups 校对目录，有变化再覆盖刷新
  var groups by remember {
    mutableStateOf(
      LibraryDiskCache.load(context.applicationContext).ifEmpty { LibraryCache.groups },
    )
  }
  var workplaceReady by remember { mutableStateOf(false) }
  var loaded by remember { mutableStateOf(false) }
  val expanded = remember { mutableStateMapOf<String, Boolean>() }
  val selected = remember { mutableStateMapOf<String, Boolean>() }
  var menuFor by remember { mutableStateOf<String?>(null) }
  var photoStem by remember { mutableStateOf<String?>(null) }   // 独立插图弹窗目标
  var showBatchPicker by remember { mutableStateOf(false) }     // 批量归纳选择弹窗
  val batchChecked = remember { mutableStateMapOf<String, Boolean>() }

  fun refresh() {
    scope.launch {
      // 目录扫描/指纹校对全程 IO：SAF 调用在主线程会卡住进页动画（真机踩坑）
      val (ready, result) = withContext(Dispatchers.IO) {
        ws.isReady() to buildGroups(context.applicationContext, ws)
      }
      workplaceReady = ready
      groups = result
      loaded = true
      // 目录校对完成：把最新分组持久化，供下次冷启动首帧直接显示（SAF 全量扫描要数秒）
      LibraryDiskCache.save(context.applicationContext, result)
    }
  }

  LaunchedEffect(Unit) { refresh() }

  // 归纳结束后刷新（新 md 出现、待归纳卡变化）并清空勾选
  LaunchedEffect(llm.phase, llm.batchTotal) {
    val p = llm.phase
    if (llm.batchTotal == 0 && (p is LlmPhase.Done || p is LlmPhase.Idle)) {
      if (selected.isNotEmpty()) selected.clear()
      refresh()
    }
  }

  Box(modifier = Modifier.fillMaxSize()) {
    LazyColumn(
      modifier = Modifier.fillMaxSize(),
      contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
    item {
      Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
          text = "笔记",
          style = MaterialTheme.typography.headlineSmall,
          color = MaterialTheme.colorScheme.primary,
          modifier = Modifier.weight(1f),
        )
        TextButton(
          enabled = !llm.busy() && groups.any { it.srt != null },
          onClick = { batchChecked.clear(); showBatchPicker = true },
        ) { Text("AI 归纳") }
        IconButton(onClick = { refresh() }) {
          Icon(Icons.Filled.Refresh, contentDescription = "刷新")
        }
      }
    }

    // 归纳进行中：顶部进度卡
    if (llm.busy()) {
      item {
        SectionCard(
          title = "AI 归纳中",
          subtitle = buildString {
            if (llm.batchTotal > 0) append("第 ${llm.batchIndex}/${llm.batchTotal} 篇")
            if (llm.pendingCount > 0) {
              if (isNotEmpty()) append(" · ")
              append("排队 ${llm.pendingCount} 篇")
            }
          }.ifEmpty { null },
        ) {
          InlineLlmProgress(llm)
        }
      }
    }

    if (loaded && !workplaceReady) {
      item {
        SectionCard(title = "还没有工作目录") {
          Text("先到「设置 → 笔记库目录」授权一个文件夹作为笔记库（workplace）。")
        }
      }
    } else if (loaded && groups.isEmpty()) {
      item {
        SectionCard(title = "笔记库是空的") {
          Text("到「转写」页录音或导入音频，转写完成后这里会出现字幕与归纳出的笔记。")
          Button(onClick = onGoTranscribe) { Text("去转写") }
        }
      }
    }

    items(groups, key = { it.stem }) { g ->
      GroupCard(
        group = g,
        expanded = expanded[g.stem] ?: false,
        checked = selected[g.stem] == true,
        menuOpen = menuFor == g.stem,
        llmReady = llm.modelReady() && !llm.busy(),
        onToggleExpand = { expanded[g.stem] = !(expanded[g.stem] ?: false) },
        onCheck = { checked ->
          if (checked) selected[g.stem] = true else selected.remove(g.stem)
        },
        onMenuOpen = { menuFor = g.stem },
        onMenuClose = { menuFor = null },
        onResummarize = { menuFor = null; llm.summarizeFromSrt(g.stem) },
        onInsertPhotos = { menuFor = null; photoStem = g.stem },
        onOpenNote = { g.md?.let { onOpenNote(it.uri) } },
        onOpenSrt = { onOpenSrt(g.stem) },
        onPlayAudio = {
          g.audio ?: return@GroupCard
          runCatching {
            context.startActivity(
              Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(g.audio.uri, "audio/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
              },
            )
          }
        },
      )
    }

    // 底部批量归纳
    if (selected.isNotEmpty()) {
      item {
        Button(
          onClick = { llm.summarizeFromSrtMany(selected.keys.toList().sorted()) },
          enabled = llm.modelReady() && !llm.busy(),
          modifier = Modifier.fillMaxWidth(),
        ) { Text("归纳所选（${selected.size} 篇字幕）") }
      }
    }
    }

    // 独立插图确认弹窗（笔记卡 ⋮ 菜单入口）
    photoStem?.let { stem ->
      PhotoInsertDialog(
        stem = stem,
        ws = ws,
        onDismiss = { photoStem = null },
        onInserted = { n ->
          photoStem = null
          AppLog.i("照片插图：$stem 插入 $n 张")
          refresh()
        },
      )
    }

    // 批量归纳选择弹窗（顶栏「AI 归纳」入口）
    if (showBatchPicker) {
      val srtGroups = groups.filter { it.srt != null }
      AlertDialog(
        onDismissRequest = { showBatchPicker = false },
        title = { Text("批量 AI 归纳") },
        text = {
          Column {
            if (!llm.modelReady()) {
              Text(
                "未导入归纳模型，请先到「设置 → 归纳模型」导入 .litertlm 文件",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
              )
            }
            Text(
              "勾选要归纳的字幕（${srtGroups.size} 篇可选），按顺序排队执行，每篇约 10~15 分钟：",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LazyColumn(modifier = Modifier.heightIn(max = 380.dp)) {
              items(srtGroups, key = { it.stem }) { g ->
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  verticalAlignment = Alignment.CenterVertically,
                ) {
                  Checkbox(
                    checked = batchChecked[g.stem] == true,
                    onCheckedChange = { on ->
                      if (on) batchChecked[g.stem] = true else batchChecked.remove(g.stem)
                    },
                  )
                  Column(modifier = Modifier.weight(1f)) {
                    Text(g.title, style = MaterialTheme.typography.bodyMedium)
                    Text(
                      g.stem + if (g.hasMd) " · 已有笔记（重新归纳会覆盖）" else " · 未归纳",
                      style = MaterialTheme.typography.bodySmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                      maxLines = 1,
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
              showBatchPicker = false
            },
          ) { Text("开始归纳（${batchChecked.size}）") }
        },
        dismissButton = {
          TextButton(onClick = { showBatchPicker = false }) { Text("取消") }
        },
      )
    }
  }
}

@Composable
private fun GroupCard(
  group: ResGroup,
  expanded: Boolean,
  checked: Boolean,
  menuOpen: Boolean,
  llmReady: Boolean,
  onToggleExpand: () -> Unit,
  onCheck: (Boolean) -> Unit,
  onMenuOpen: () -> Unit,
  onMenuClose: () -> Unit,
  onResummarize: () -> Unit,
  onInsertPhotos: () -> Unit,
  onOpenNote: () -> Unit,
  onOpenSrt: () -> Unit,
  onPlayAudio: () -> Unit,
) {
  val context = LocalContext.current
  ElevatedCard(modifier = Modifier.fillMaxWidth()) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
      // 卡头：标题 + 展开箭头 + ⋮ 菜单
      Row(verticalAlignment = Alignment.CenterVertically) {
        Column(
          modifier = Modifier
            .weight(1f)
            .clickable(onClick = onToggleExpand),
        ) {
          Text(group.title, style = MaterialTheme.typography.titleMedium)
          Text(
            if (group.hasMd) group.stem else "未归纳 · ${group.stem}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        IconButton(onClick = onToggleExpand) {
          Icon(
            if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = if (expanded) "收起" else "展开资源",
          )
        }
        Box {
          IconButton(onClick = onMenuOpen) {
            Icon(Icons.Filled.MoreVert, contentDescription = "更多")
          }
          DropdownMenu(expanded = menuOpen, onDismissRequest = onMenuClose) {
            if (group.srt != null) {
              DropdownMenuItem(
                text = { Text(if (group.hasMd) "AI 重新归纳" else "AI 归纳成笔记") },
                onClick = onResummarize,
                enabled = llmReady,
              )
            }
            if (group.hasMd) {
              DropdownMenuItem(
                text = { Text("插入图片（按录音时段）") },
                onClick = onInsertPhotos,
              )
            }
            if (group.audio != null) {
              DropdownMenuItem(
                text = { Text("用系统播放器打开录音") },
                onClick = { onMenuClose(); onPlayAudio() },
              )
            }
          }
        }
      }

      // 笔记预览（渲染前的摘要文本）
      if (group.hasMd && !expanded) {
        Text(
          group.preview,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 3,
          modifier = Modifier.padding(top = 4.dp),
        )
      }

      // 展开的资源区
      if (expanded) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
          if (group.hasMd) {
            ResRow(
              icon = {
                Icon(
                  Icons.Filled.Description, null,
                  tint = MaterialTheme.colorScheme.primary,
                )
              },
              text = "${group.stem}.md · 打开实时预览编辑",
              onClick = onOpenNote,
            )
          }
          if (group.srt != null) {
            ResRow(
              icon = {
                Icon(
                  Icons.Filled.Subtitles, null,
                  tint = MaterialTheme.colorScheme.primary,
                )
              },
              text = "${group.stem}.srt · 查看与改错字",
              onClick = onOpenSrt,
              leading = {
                Checkbox(checked = checked, onCheckedChange = onCheck)
              },
            )
          }
          if (group.audio != null) {
            ResRow(
              icon = {
                Icon(
                  Icons.Filled.AudioFile, null,
                  tint = MaterialTheme.colorScheme.primary,
                )
              },
              text = "${group.audio.name} · 播放",
              onClick = onPlayAudio,
            )
          }
          if (group.images.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
              group.images.take(6).forEach { img ->
                val bmp by produceState<Bitmap?>(null, img.uri) {
                  value = withContext(Dispatchers.IO) { decodeThumbCached(context, img.uri) }
                }
                bmp?.let {
                  Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = img.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(72.dp),
                  )
                }
              }
            }
          }
        }
      }
    }
  }
}

// ---------- 数据 ----------

/** 按 stem 聚合的一组资源：md 笔记 + srt 字幕 + 录音 + 插图。 */
data class ResGroup(
  val stem: String,
  val md: DocumentFile?,
  val srt: DocumentFile?,
  val audio: DocumentFile?,
  val images: List<DocumentFile>,
  val title: String,
  val preview: String,
) {
  val hasMd: Boolean get() = md != null
}

/** 主页内存缓存：目录未变化时复用上次分组与预览（多笔记时进主页不再全量重读）。 */
private object LibraryCache {
  var groups: List<ResGroup> = emptyList()
  var fingerprint: String = ""
}

/**
 * 主页磁盘缓存：把分组元数据（标题/预览/各文件 uri）持久化到 files/library_cache.json。
 * 冷启动/进程被杀后首帧直接用缓存渲染卡片（SAF 全量扫描 20+ 组要数秒，用户感知就是
 * 「每次打开首页都很慢」）；随后后台校对目录，有变化再覆盖。缓存 uri 失效的极端情况
 * （重装/换目录）最多导致卡片点开失败，后台刷新立刻纠正。
 */
private object LibraryDiskCache {
  private const val FILE = "library_cache.json"

  fun load(context: Context): List<ResGroup> = runCatching {
    val f = File(context.filesDir, FILE)
    if (!f.isFile) return emptyList()
    val arr = org.json.JSONArray(f.readText())
    (0 until arr.length()).map { i ->
      val o = arr.getJSONObject(i)
      fun doc(key: String): DocumentFile? =
        o.optString(key, "").takeIf { it.isNotEmpty() }?.let {
          DocumentFile.fromSingleUri(context, android.net.Uri.parse(it))
        }
      val imgs = o.optJSONArray("images") ?: org.json.JSONArray()
      ResGroup(
        stem = o.getString("stem"),
        md = doc("md"),
        srt = doc("srt"),
        audio = doc("audio"),
        images = (0 until imgs.length()).mapNotNull { j ->
          imgs.optString(j, "").takeIf { it.isNotEmpty() }?.let {
            DocumentFile.fromSingleUri(context, android.net.Uri.parse(it))
          }
        },
        title = o.getString("title"),
        preview = o.getString("preview"),
      )
    }
  }.getOrDefault(emptyList())

  fun save(context: Context, groups: List<ResGroup>) {
    runCatching {
      val arr = org.json.JSONArray()
      groups.forEach { g ->
        arr.put(
          org.json.JSONObject().apply {
            put("stem", g.stem)
            put("title", g.title)
            put("preview", g.preview)
            g.md?.uri?.let { put("md", it.toString()) }
            g.srt?.uri?.let { put("srt", it.toString()) }
            g.audio?.uri?.let { put("audio", it.toString()) }
            put("images", org.json.JSONArray().apply { g.images.forEach { put(it.uri.toString()) } })
          },
        )
      }
      File(context.filesDir, FILE).writeText(arr.toString())
    }
  }
}

/** 已解码缩略图缓存（uri 为键；picture/ 内文件名不可变，无需失效机制）。 */
// 缩略图缓存按字节限界（M2.9 泄露排查：此前按条目数 96 限界，几十篇×288px
// 位图最坏可积到 ~32MB 且不受控）；24MB ≈ 一页可见缩略图足够
private val thumbCache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
  override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
}

private suspend fun buildGroups(context: Context, ws: WorkspaceStore): List<ResGroup> {
  val root = ws.rootDir() ?: return emptyList()
  val audio = ws.audioDir()
  val pics = ws.picturesDir()
  val mds = root.listFiles().filter { it.name?.endsWith(".md") == true }
  val srts = audio?.listFiles()?.filter { it.name?.endsWith(".srt") == true } ?: emptyList()
  val wavs = audio?.listFiles()?.filter {
    it.name?.endsWith(".wav") == true || it.name?.endsWith(".pcm") == true
  } ?: emptyList()
  val images = pics?.listFiles()?.filter { it.isFile } ?: emptyList()

  // 目录指纹：任一文件的名字/修改时间变化才重建分组内容，否则直接复用
  val fp = (mds + srts + wavs + images).joinToString("|") { d ->
    (d.name ?: "?") + "@" + d.lastModified()
  }
  if (fp == LibraryCache.fingerprint && LibraryCache.groups.isNotEmpty()) {
    AppLog.d("主页：目录未变化，复用缓存（${LibraryCache.groups.size} 组）")
    return LibraryCache.groups
  }

  val stems = (
    mds.map { it.name!!.removeSuffix(".md") } +
      srts.map { it.name!!.removeSuffix(".srt") } +
      wavs.map { it.name!!.substringBeforeLast('.') }
    ).distinct()

  // 只读 md 头部（标题与三行预览足够），多笔记时避免整读所有 md 全文
  val mdHeads = withContext(Dispatchers.IO) {
    mds.associate { md -> md.name to readMdHead(context, md) }
  }

  val groups = stems.sortedDescending().map { stem ->
    val md = mds.firstOrNull { it.name == "$stem.md" }
    val srt = srts.firstOrNull { it.name == "$stem.srt" }
    val wav = wavs.firstOrNull { it.name == "$stem.wav" || it.name == "$stem.pcm" }
    val imgs = images.filter { it.name?.startsWith("$stem-") == true }.sortedBy { it.name ?: "" }
    val text = mdHeads[md?.name].orEmpty()
    val title = text.lineSequence()
      .firstOrNull { it.trimStart().startsWith("# ") }
      ?.removePrefix("# ")?.trim()
      ?.takeIf { it.isNotBlank() } ?: stem
    val preview = text.lineSequence()
      .filterNot {
        it.isBlank() || it.trimStart().startsWith("#") || it.trimStart().startsWith(">") ||
          it.trimStart().startsWith("<!--") || it.trimStart().startsWith("![")
      }
      .take(3).joinToString("　")
      .ifBlank { "（空笔记）" }
    ResGroup(stem, md, srt, wav, imgs, title, preview)
  }
  LibraryCache.fingerprint = fp
  LibraryCache.groups = groups
  return groups
}

// ---------- 小组件 ----------

/** 归纳进行中的阶段进度（概览/分段/审核/问题与思考）。 */
@Composable
private fun InlineLlmProgress(llm: LlmController) {
  when (val lp = llm.phase) {
    is LlmPhase.LoadingModel -> {
      Text("正在加载模型…（数秒到数十秒）", style = MaterialTheme.typography.bodySmall)
      LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
    is LlmPhase.Overview -> {
      Text("第 1 步 全局概览 · 分片 ${lp.part}/${lp.total}", style = MaterialTheme.typography.bodySmall)
      LinearProgressIndicator(
        progress = { lp.part.toFloat() / lp.total },
        modifier = Modifier.fillMaxWidth(),
      )
    }
    is LlmPhase.Sections -> {
      Text("第 2 步 分段总结 · 段落 ${lp.part}/${lp.total}", style = MaterialTheme.typography.bodySmall)
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
    else -> {}
  }
}

@Composable
private fun ResRow(
  icon: @Composable () -> Unit,
  text: String,
  onClick: () -> Unit,
  leading: (@Composable () -> Unit)? = null,
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clickable(onClick = onClick)
      .padding(vertical = 2.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    leading?.invoke()
    icon()
    Text(
      text,
      style = MaterialTheme.typography.bodyMedium,
      modifier = Modifier
        .weight(1f)
        .padding(start = 8.dp),
    )
  }
}

/** 只读 md 头部 8KB（标题与三行预览足够）；读满则去掉最后一行防多字节字符截半。 */
private fun readMdHead(context: Context, md: DocumentFile, bytes: Int = 8192): String =
  runCatching {
    context.contentResolver.openInputStream(md.uri)?.use { ins ->
      val buf = ByteArray(bytes)
      var off = 0
      while (off < bytes) {
        val n = ins.read(buf, off, bytes - off)
        if (n <= 0) break
        off += n
      }
      var s = if (off > 0) String(buf, 0, off, Charsets.UTF_8) else ""
      if (off == bytes) {
        val lastNl = s.lastIndexOf('\n')
        if (lastNl > 0) s = s.substring(0, lastNl)
      }
      s
    }.orEmpty()
  }.getOrDefault("")

/** 缩略图解码（带缓存；按目标尺寸取样，避免 7MB 原图整载）。 */
private suspend fun decodeThumbCached(context: Context, uri: Uri): Bitmap? {
  thumbCache.get(uri.toString())?.let { return it }
  val bmp = decodeThumb(context, uri) ?: return null
  thumbCache.put(uri.toString(), bmp)
  return bmp
}

private suspend fun decodeThumb(context: Context, uri: Uri): Bitmap? =
  withContext(Dispatchers.IO) {
    runCatching {
      val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, bounds)
      }
      var sample = 1
      while (bounds.outWidth / sample > 288) sample *= 2
      val opts = BitmapFactory.Options().apply { inSampleSize = sample }
      context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, opts)
      }
    }.getOrNull()
  }
