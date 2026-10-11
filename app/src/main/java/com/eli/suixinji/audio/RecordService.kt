package com.eli.suixinji.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.documentfile.provider.DocumentFile
import com.eli.suixinji.MainActivity
import com.eli.suixinji.R
import com.eli.suixinji.data.SettingsStore
import com.eli.suixinji.util.AppLog
import com.eli.suixinji.workspace.WorkspaceStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 前台录音服务：持有 [SafeRecorder]，屏幕关闭/切后台仍持续录音，
 * 通知栏展示录音状态与停止按钮。
 */
class RecordService : Service() {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private var recorder: SafeRecorder? = null
  private var lastNotifyText: String? = null

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_START -> startRecording()
      ACTION_STOP -> stopRecording()
      else -> if (recorder == null) stopSelf()
    }
    return START_NOT_STICKY
  }

  private fun startRecording() {
    if (recorder?.isRunning() == true) return
    AppLog.i("RecordService: 开始录音请求")
    startForegroundInternal("正在录音…")

    scope.launch {
      val ws = WorkspaceStore(applicationContext, SettingsStore(applicationContext))
      val dir = ws.audioDir()
      if (dir == null) {
        AppLog.e("RecordService: audio 目录不可用（workplace 未授权或创建失败）")
        RecordingSession.fail("工作目录不可用，请先在设置中授权")
        stopSelf()
        return@launch
      }
      val name = CrashRecovery.defaultName()
      val pcm = dir.createFile("application/octet-stream", "$name.pcm")
      if (pcm == null) {
        AppLog.e("RecordService: 创建 .pcm 失败 dir=$dir")
        RecordingSession.fail("无法创建录音文件")
        stopSelf()
        return@launch
      }
      AppLog.i("RecordService: 录音开始 pcm=$name.pcm")
      val rec = SafeRecorder(pcm, applicationContext.contentResolver)
      if (!rec.start()) {
        AppLog.e("RecordService: AudioRecord 启动失败（权限或麦克风被占用）")
        RecordingSession.fail("录音启动失败（权限或麦克风被占用）")
        stopSelf()
        return@launch
      }
      recorder = rec
      RecordingSession.begin(pcm, name)

      // 轮询电平/时长，驱动 UI 与通知
      while (rec.isRunning()) {
        val stats = rec.currentStats()
        RecordingSession.tick(stats)
        updateNotification("正在录音 · ${NoteDur(stats.durationMs)}")
        kotlinx.coroutines.delay(250)
      }
    }
  }

  private fun stopRecording() {
    val rec = recorder
    if (rec == null) {
      stopSelf()
      return
    }
    RecordingSession.finalizing()
    AppLog.i("RecordService: 停止录音请求")
    scope.launch {
      val stats = rec.stop()
      recorder = null
      val pcm = RecordingSession.pcmDoc
      val dir = WorkspaceStore(applicationContext, SettingsStore(applicationContext)).audioDir()
      var wavName: String? = null
      if (pcm != null && dir != null) {
        val base = RecordingSession.sessionName.ifBlank { CrashRecovery.defaultName() }
        val ok = runCatching { convert(pcm, dir, "$base.wav") }
          .onFailure { AppLog.e("RecordService: WAV 转换异常 file=$base.wav", it) }
          .getOrDefault(false)
        if (ok) {
          runCatching { pcm.delete() }
          wavName = "$base.wav"
          AppLog.i("RecordService: 录音完成 file=$base.wav pcmBytes=${pcm.length()} 时长=${stats.durationMs}ms")
        } else {
          AppLog.w("RecordService: WAV 转换失败，保留 .pcm 交给崩溃恢复")
        }
      } else {
        AppLog.w("RecordService: 停止时 pcm/dir 缺失 pcm!=null=${pcm != null} dir!=null=${dir != null}")
      }
      RecordingSession.finish(wavName, stats.durationMs)
      stopSelf()
    }
  }

  private fun convert(pcm: DocumentFile, dir: DocumentFile, wavName: String): Boolean {
    val input = contentResolver.openInputStream(pcm.uri) ?: return false
    val out = contentResolver.openOutputStream(
      dir.findFile(wavName)?.takeIf { it.isFile }?.uri
        ?: dir.createFile("audio/wav", wavName)?.uri ?: return false,
      "w",
    ) ?: return false
    input.use { ins ->
      out.use { os ->
        os.write(CrashRecovery.wavHeader(pcm.length()))
        val buf = ByteArray(64 * 1024)
        while (true) {
          val n = ins.read(buf)
          if (n <= 0) break
          os.write(buf, 0, n)
        }
        os.flush()
      }
    }
    return true
  }

  private fun NoteDur(ms: Long): String = com.eli.suixinji.note.NoteComposer.formatDur(ms)

  private fun startForegroundInternal(text: String) {
    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (Build.VERSION.SDK_INT >= 26) {
      nm.createNotificationChannel(
        NotificationChannel(CHANNEL_ID, "录音", NotificationManager.IMPORTANCE_LOW),
      )
    }
    val pi = PendingIntent.getActivity(
      this, 0, Intent(this, MainActivity::class.java),
      PendingIntent.FLAG_IMMUTABLE,
    )
    val stopPi = PendingIntent.getService(
      this, 1,
      Intent(this, RecordService::class.java).setAction(ACTION_STOP),
      PendingIntent.FLAG_IMMUTABLE,
    )
    val notification: Notification = if (Build.VERSION.SDK_INT >= 26) {
      Notification.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_stat_record)
        .setContentTitle("随心记")
        .setContentText(text)
        .setContentIntent(pi)
        .addAction(Notification.Action.Builder(null, "停止", stopPi).build())
        .setOngoing(true)
        .build()
    } else {
      @Suppress("DEPRECATION")
      Notification.Builder(this)
        .setSmallIcon(R.drawable.ic_stat_record)
        .setContentTitle("随心记")
        .setContentText(text)
        .setContentIntent(pi)
        .setOngoing(true)
        .build()
    }
    if (Build.VERSION.SDK_INT >= 29) {
      startForeground(NOTIFY_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
    } else {
      startForeground(NOTIFY_ID, notification)
    }
  }

  private fun updateNotification(text: String) {
    // 250ms 轮询里文本多数时候没变；内容相同就跳过，避免每秒 4 次重建通知
    if (text == lastNotifyText) return
    lastNotifyText = text
    startForegroundInternal(text)
  }

  override fun onDestroy() {
    // 系统强杀前的最后兜底：尽力落盘
    if (recorder?.isRunning() == true) {
      AppLog.w("RecordService: onDestroy 时仍在录音，兜底停止")
      runCatching { recorder?.stop() }
    }
    recorder = null
    scope.cancel()
    super.onDestroy()
  }

  companion object {
    const val ACTION_START = "com.eli.suixinji.action.START_RECORD"
    const val ACTION_STOP = "com.eli.suixinji.action.STOP_RECORD"
    private const val CHANNEL_ID = "recording"
    private const val NOTIFY_ID = 42

    fun start(context: Context) {
      val i = Intent(context, RecordService::class.java).setAction(ACTION_START)
      if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
    }

    fun stop(context: Context) {
      context.startService(Intent(context, RecordService::class.java).setAction(ACTION_STOP))
    }
  }
}
