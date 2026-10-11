package com.eli.suixinji

import android.app.Application
import android.content.ComponentCallbacks2
import com.eli.suixinji.util.AppLog
import com.eli.suixinji.util.CrashSignal
import com.eli.suixinji.util.TaskTrace

/**
 * Application 入口：尽早初始化诊断体系（logcat 在荣耀设备上被加密拦截，文件是主通道）：
 * 1. 文件日志 + 会话头（版本/机型/ROM 自识别）；
 * 2. Java 崩溃捕获（UncaughtExceptionHandler → crash.log）；
 * 3. 原生崩溃捕获（信号处理器 → native_crash.log，兜住 litertlm/ONNX 的 SIGSEGV）；
 * 4. 任务心跳（running.json，下次启动推断「上次在哪个环节被杀」）；
 * 5. 系统内存告警回调（onTrimMemory → 日志）。
 */
class App : Application() {
  override fun onCreate() {
    super.onCreate()
    AppLog.init(this)
    installCrashHandler()
    CrashSignal.installAt(this)
    TaskTrace.init(this)
    TaskTrace.checkOnStartup()
  }

  override fun onTrimMemory(level: Int) {
    super.onTrimMemory(level)
    // 40=TRIM_MEMORY_COMPLETE 级别起系统已在激进回收——进程随后被杀的高危信号
    if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
      AppLog.w("系统内存告警 onTrimMemory level=$level")
      AppLog.mem("trim$level")
    }
  }

  /** 未捕获异常 → files/logs/crash.log（堆栈 + 内存快照 + 最后 300 条日志现场），再交回系统默认处理。 */
  private fun installCrashHandler() {
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, t ->
      AppLog.mem("crash前")
      AppLog.e("未捕获异常 thread=${thread.name}", t)
      AppLog.writeCrashLog(thread, t)
      previous?.uncaughtException(thread, t)
    }
  }
}
