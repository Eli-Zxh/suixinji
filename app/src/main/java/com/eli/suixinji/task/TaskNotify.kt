package com.eli.suixinji.task

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.eli.suixinji.MainActivity
import com.eli.suixinji.util.AppLog

/**
 * 长任务后台保活（M2.8）：批量转写 / 批量归纳挂前台服务 + 通知栏进度条——
 * 用户可以离开应用页面，进程以前台优先级运行不被回收，回来接着看。
 *
 * [begin]/[end] 由两个任务控制器在队列消化的起止调用（引用计数，
 * 转写与归纳两条管线共用一个前台服务实例）；[update] 随进度刷新通知。
 * 点通知回应用主页。通知权限被拒时服务仍前台运行，只是通知不显示。
 */
object TaskNotify {

  private const val CHANNEL_ID = "task_progress"
  internal const val NOTIF_ID = 42

  private val laneLock = Any()
  @Volatile private var lanes = 0

  /** 队列开始消化：拉起前台服务（引用计数，双管线共用）。 */
  fun begin(context: Context, title: String) {
    val first = synchronized(laneLock) {
      val f = lanes == 0
      lanes += 1
      f
    }
    if (first) {
      ensureChannel(context)
      runCatching {
        context.startForegroundService(Intent(context, TaskService::class.java))
      }.onFailure { AppLog.w("任务前台服务启动失败（系统限制或通知权限缺失）", it) }
    }
    update(context, title, "准备中…", null, null)
  }

  /** 刷新进度通知（进度条：progress/total；未知则转圈）。 */
  fun update(context: Context, title: String, text: String, progress: Int?, total: Int?) {
    if (lanes <= 0) return
    runCatching {
      val nm = context.getSystemService(NotificationManager::class.java) ?: return
      nm.notify(NOTIF_ID, build(context, title, text, progress, total))
    }
  }

  /** 一条管线结束：引用计数归零时停掉前台服务。 */
  fun end(context: Context) {
    val stop = synchronized(laneLock) {
      lanes = (lanes - 1).coerceAtLeast(0)
      lanes == 0
    }
    if (stop) {
      runCatching { context.stopService(Intent(context, TaskService::class.java)) }
    }
  }

  private fun ensureChannel(context: Context) {
    val nm = context.getSystemService(NotificationManager::class.java) ?: return
    if (nm.getNotificationChannel(CHANNEL_ID) == null) {
      nm.createNotificationChannel(
        NotificationChannel(CHANNEL_ID, "后台任务进度", NotificationManager.IMPORTANCE_LOW).apply {
          description = "转写与 AI 归纳的后台进度（长任务可离开应用等待）"
          setShowBadge(false)
        },
      )
    }
  }

  internal fun build(
    context: Context,
    title: String,
    text: String,
    progress: Int?,
    total: Int?,
  ): Notification {
    val contentIntent = PendingIntent.getActivity(
      context,
      0,
      Intent(context, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val b = NotificationCompat.Builder(context, CHANNEL_ID)
      .setSmallIcon(android.R.drawable.stat_notify_sync)
      .setContentTitle(title)
      .setContentText(text)
      .setContentIntent(contentIntent)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
    if (progress != null && total != null && total > 0) {
      b.setProgress(total, progress.coerceIn(0, total), false)
    } else {
      b.setProgress(0, 0, true)
    }
    return b.build()
  }
}

/** 空壳前台服务：只负责把进程提到前台优先级并挂出通知；进度由 TaskNotify 直接更新。 */
class TaskService : Service() {

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    startForeground(TaskNotify.NOTIF_ID, TaskNotify.build(this, "后台任务", "准备中…", null, null))
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY
}
