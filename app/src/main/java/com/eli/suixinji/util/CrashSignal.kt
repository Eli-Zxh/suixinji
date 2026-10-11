package com.eli.suixinji.util

/**
 * 原生崩溃捕获的 Kotlin 入口：App.onCreate 尽早调用 [install]。
 * 原生侧安装 SIGSEGV/SIGABRT 等信号处理器，崩溃现场写入 files/logs/native_crash.log
 * （信号、出错地址、线程名、32 帧回溯地址），随「设置 → 日志导出」打包。
 * 这一层兜住 litertlm / ONNX 等 JNI 引擎的原生闪退——Java 的
 * UncaughtExceptionHandler 收不到，荣耀 ROM 的 logcat 又被加密。
 */
object CrashSignal {
  init {
    System.loadLibrary("suixinji_native")
  }

  external fun install(logsDirPath: String)

  fun installAt(context: android.content.Context) {
    try {
      val dir = java.io.File(context.filesDir, "logs").apply { mkdirs() }
      install(dir.absolutePath)
      AppLog.i("CrashSignal：原生崩溃捕获已安装")
    } catch (t: Throwable) {
      AppLog.w("CrashSignal：安装失败", t)
    }
  }
}
