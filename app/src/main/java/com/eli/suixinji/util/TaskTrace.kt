package com.eli.suixinji.util

import android.content.Context
import android.os.Process
import java.io.File

/**
 * 任务心跳：长任务（批量归纳/转写/对话推理）开始时把「任务类型+当前环节+PID」
 * 写进 files/logs/running.json，结束/失败时删除。应用冷启动时若该文件还在，
 * 说明上次进程没走到 endTask——即被系统杀（LMK/后台限制）、用户杀或原生崩溃
 * （原生崩溃另有 native_crash.log 可对照），据此在日志里留下推断结论。
 *
 * 这是诊断「后台进程被杀」「内存超限被系统回收」的关键证据：用户只反馈
 * 「用着用着就没了」时，running.json + app.log 最后一行即可定位到具体环节。
 */
object TaskTrace {
  private var file: File? = null

  fun init(context: Context) {
    file = File(File(context.filesDir, "logs").apply { mkdirs() }, "running.json")
  }

  /** 启动时检查：上次是否有未正常结束的任务（有则留下诊断日志）。返回描述或 null。 */
  fun checkOnStartup(): String? {
    val f = file ?: return null
    val text = try { f.takeIf { it.isFile }?.readText() } catch (_: Throwable) { null } ?: return null
    val kind = Regex("\"kind\"\\s*:\\s*\"([^\"]*)\"").find(text)?.groupValues?.get(1) ?: "?"
    val stage = Regex("\"stage\"\\s*:\\s*\"([^\"]*)\"").find(text)?.groupValues?.get(1) ?: "?"
    val ts = Regex("\"ts\"\\s*:\\s*([0-9]+)").find(text)?.groupValues?.get(1) ?: "?"
    val detail = "上次进程未正常退出：任务「$kind」停在「$stage」" +
      "（开始于 ts=$ts，PID 非 ${Process.myPid()}）——进程被杀（后台回收/LMK/用户清理）或原生崩溃"
    AppLog.w(detail)
    return detail
  }

  @Synchronized
  fun begin(kind: String) = write(kind, "开始")

  @Synchronized
  fun update(stage: String) = write(currentKind(), stage)

  @Synchronized
  fun end() {
    try { file?.delete() } catch (_: Throwable) {}
  }

  private fun currentKind(): String {
    val text = try { file?.takeIf { it.isFile }?.readText() } catch (_: Throwable) { null } ?: return "?"
    return Regex("\"kind\"\\s*:\\s*\"([^\"]*)\"").find(text)?.groupValues?.get(1) ?: "?"
  }

  private fun write(kind: String, stage: String) {
    try {
      file?.writeText(
        "{\"kind\":\"$kind\",\"stage\":\"$stage\",\"pid\":${Process.myPid()},\"ts\":${System.currentTimeMillis()}}",
      )
    } catch (_: Throwable) {}
  }
}
