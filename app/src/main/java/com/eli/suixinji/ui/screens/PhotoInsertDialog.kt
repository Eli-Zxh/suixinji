package com.eli.suixinji.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.eli.suixinji.util.AppLog
import com.eli.suixinji.workspace.PhotoMatcher
import com.eli.suixinji.workspace.WorkspaceStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 独立插图步骤的确认弹窗：
 * 1. 按 srt 记录的录音时段扫出候选照片，缩略图逐行过目，默认全选可勾掉；
 * 2. 手动兜底：扫不到或都不满意时直接从相册选照片；
 * 3. 确认后复制进 picture/，按录音时间定位插入（时间锚命中/最近的小节标题前，
 *    时间对不上统一追加文末）。
 */
@Composable
fun PhotoInsertDialog(
  stem: String,
  ws: WorkspaceStore,
  onDismiss: () -> Unit,
  onInserted: (count: Int) -> Unit,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var loading by remember { mutableStateOf(true) }
  var inserting by remember { mutableStateOf(false) }
  var errMsg by remember { mutableStateOf<String?>(null) }
  var scan by remember { mutableStateOf(PhotoMatcher.ScanResult()) }
  var selected by remember { mutableStateOf(setOf<Int>()) }

  LaunchedEffect(stem) {
    loading = true
    val result = runCatching { PhotoMatcher.findCandidates(context.applicationContext, ws, stem) }
      .onFailure { AppLog.e("照片插图：扫描候选失败", it) }
      .getOrDefault(PhotoMatcher.ScanResult())
    scan = result
    selected = result.photos.indices.toSet()
    loading = false
  }

  fun insertPicked() {
    if (inserting) return
    inserting = true
    scope.launch {
      val picked = selected.map { scan.photos[it] }.sortedBy { it.takenAt }
      val n = runCatching {
        PhotoMatcher.insertPhotos(
          context.applicationContext, ws, "$stem.md", picked,
          scan.windowStart, scan.windowEnd,
        )
      }.onFailure { AppLog.e("照片插图：插入失败", it) }.getOrDefault(0)
      inserting = false
      onInserted(n)
    }
  }

  // 手动兜底：从系统文件选择器选图片（读取权限由 picker 授予，复制完即失效）
  val manualPicker = rememberLauncherForActivityResult(
    ActivityResultContracts.OpenMultipleDocuments(),
  ) { uris: List<Uri> ->
    if (uris.isEmpty() || inserting) return@rememberLauncherForActivityResult
    inserting = true
    scope.launch {
      val n = runCatching {
        PhotoMatcher.insertManualPhotos(
          context.applicationContext, ws, "$stem.md", uris,
          scan.windowStart, scan.windowEnd,
        )
      }.onFailure { AppLog.e("照片插图：手动插入失败", it) }.getOrDefault(0)
      inserting = false
      onInserted(n)
    }
  }

  AlertDialog(
    onDismissRequest = { if (!inserting) onDismiss() },
    title = { Text("插入图片 · $stem") },
    text = {
      when {
        loading -> Row(
          modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
          horizontalArrangement = Arrangement.Center,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          CircularProgressIndicator(modifier = Modifier.size(28.dp))
          Text("  正在按录音时段扫描相册…", style = MaterialTheme.typography.bodySmall)
        }
        errMsg != null -> Text(errMsg!!, color = MaterialTheme.colorScheme.error)
        scan.photos.isEmpty() -> Column {
          Text(
            "录音时段内没有匹配到照片。\n\n" +
              "需要「设置 → 照片自动插图」已授权，且相册里有录音时段内拍摄的照片。" +
              "若录音是导入的音频、拍摄时间与录音时间对不上，请直接手动选择：",
            style = MaterialTheme.typography.bodySmall,
          )
          TextButton(
            enabled = !inserting,
            onClick = { manualPicker.launch(arrayOf("image/*")) },
          ) { Text("手动选择照片…") }
        }
        else -> Column {
          Text(
            "匹配到 ${scan.photos.size} 张（按拍摄时间排序），取消勾选即不插入；" +
              "插入位置按拍摄时刻对应到笔记小节：",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          LazyColumn(modifier = Modifier.heightIn(max = 340.dp)) {
            itemsIndexed(scan.photos) { i, photo ->
              Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                val bmp by produceState<Bitmap?>(null, photo.file.absolutePath) {
                  value = withContext(Dispatchers.IO) { decodeThumb(context, photo.file) }
                }
                bmp?.let {
                  Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = photo.file.name,
                    modifier = Modifier.size(56.dp).padding(end = 4.dp),
                  )
                }
                Column(modifier = Modifier.weight(1f)) {
                  Text(
                    SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
                      .format(Date(photo.takenAt)),
                    style = MaterialTheme.typography.bodyMedium,
                  )
                  Text(
                    photo.file.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                  )
                }
                Checkbox(
                  checked = i in selected,
                  onCheckedChange = { on ->
                    selected = if (on) selected + i else selected - i
                  },
                )
              }
            }
          }
          TextButton(
            enabled = !inserting,
            onClick = { manualPicker.launch(arrayOf("image/*")) },
          ) { Text("没有想要的？手动选择照片…") }
        }
      }
    },
    confirmButton = {
      TextButton(
        enabled = !loading && !inserting && selected.isNotEmpty(),
        onClick = { insertPicked() },
      ) {
        Text(if (inserting) "插入中…" else "插入所选（${selected.size}）")
      }
    },
    dismissButton = {
      TextButton(enabled = !inserting, onClick = onDismiss) { Text("取消") }
    },
  )
}

/** 缩略图解码（按目标尺寸取样，避免原图整载）。 */
private suspend fun decodeThumb(context: Context, file: java.io.File): Bitmap? =
  withContext(Dispatchers.IO) {
    runCatching {
      val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      BitmapFactory.decodeFile(file.absolutePath, bounds)
      var sample = 1
      while (bounds.outWidth / sample > 224) sample *= 2
      val opts = BitmapFactory.Options().apply { inSampleSize = sample }
      BitmapFactory.decodeFile(file.absolutePath, opts)
    }.getOrNull()
  }
