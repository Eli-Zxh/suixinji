package com.eli.suixinji.util

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.Process
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 统一日志出口（debug 阶段全程可用）。双通道输出：
 *
 * 1. logcat（tag=SuixinJi，`adb logcat -s SuixinJi`）——注意部分荣耀/MagicOS
 *    设备会加密拦截 adb logcat（缓冲区只见 HKS/HKE 标记），不能依赖；
 * 2. 应用私有文件 filesDir/logs/app.log（超过 2MB 轮转为 app.log.old）——
 *    用 `adb shell run-as com.eli.suixinji cat files/logs/app.log` 拉取，
 *    这是真机排障的主通道。
 *
 * 隐私约束：只允许记录文件名、字节数、耗时、计数等指标，
 * 禁止记录转写文本、笔记内容等用户数据。
 */
object AppLog {
  const val TAG = "SuixinJi"

  private const val MAX_BYTES = 2L * 1024 * 1024

  @Volatile private var logFile: File? = null
  @Volatile private var appContext: Context? = null
  private val lock = Any()
  private val ts = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

  /** 最近日志的内存环形缓冲：崩溃瞬间文件写入可能来不及/失败，用它兜底带出最后现场。 */
  private val ring = ArrayDeque<String>(RING_SIZE)

  private const val RING_SIZE = 300

  /** 在 Application.onCreate 调用一次；未调用时仅输出 logcat。 */
  fun init(context: Context) {
    try {
      val dir = File(context.filesDir, "logs").apply { mkdirs() }
      logFile = File(dir, "app.log")
      appContext = context.applicationContext
      // 会话头：让任何一段被用户截屏/复制的日志都能自识别环境（开源反馈排障的第一现场）
      val v = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0)
      }.getOrNull()
      val ver = "${v?.versionName ?: "?"}(${v?.let { if (android.os.Build.VERSION.SDK_INT >= 28) it.longVersionCode else @Suppress("DEPRECATION") it.versionCode } ?: 0})"
      i(
        "==== 会话开始 app=$ver pid=${Process.myPid()} " +
          "android=${Build.VERSION.RELEASE}(SDK ${Build.VERSION.SDK_INT}) " +
          "device=${Build.BRAND} ${Build.MODEL} soc=${Build.SOC_MANUFACTURER}/${Build.HARDWARE} " +
          "board=${Build.BOARD} abis=${Build.SUPPORTED_ABIS.take(1).joinToString()} " +
          "rom=${Build.DISPLAY.take(40)} cores=${Runtime.getRuntime().availableProcessors()} ====",
      )
    } catch (_: Throwable) {
      logFile = null
    }
  }

  /**
   * 内存快照：原生堆 / Java 堆 / 系统可用内存 / lowMemory 标记。
   * 在关键节点调用（引擎加载前后、批量逐篇前后、崩溃记录里），
   * 用于区分「内存超限被系统杀」与逻辑崩溃——开源反馈里最常见的模糊描述。
   */
  fun mem(label: String) {
    try {
      val rt = Runtime.getRuntime()
      val nativeMb = Debug.getNativeHeapAllocatedSize() / (1024 * 1024)
      val javaMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
      var availMb = -1L
      var low = "?"
      appContext?.let { c ->
        val am = c.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        am?.let {
          val mi = ActivityManager.MemoryInfo()
          it.getMemoryInfo(mi)
          availMb = mi.availMem / (1024 * 1024)
          low = mi.lowMemory.toString()
        }
      }
      i("MEM[$label] native=${nativeMb}MB java=${javaMb}MB avail=${availMb}MB lowMem=$low")
    } catch (_: Throwable) {}
  }

  fun d(msg: String) = log("D", msg, null)

  fun i(msg: String) = log("I", msg, null)

  fun w(msg: String, t: Throwable? = null) = log("W", msg, t)

  fun e(msg: String, t: Throwable? = null) = log("E", msg, t)

  private fun log(level: String, msg: String, t: Throwable?) {
    when (level) {
      "D" -> Log.d(TAG, msg, t)
      "I" -> Log.i(TAG, msg, t)
      "W" -> Log.w(TAG, msg, t)
      else -> Log.e(TAG, msg, t)
    }
    val line = "${ts.format(Date())} $level/$msg"
    synchronized(lock) {
      if (ring.size >= RING_SIZE) ring.removeFirst()
      ring.addLast(line)
    }
    val f = logFile ?: return
    try {
      synchronized(lock) {
        rotateIfNeeded(f)
        val stack = t?.let {
          val head = it.stackTraceToString().lineSequence().take(12).joinToString("\n  ")
          "\n  $it\n  $head"
        } ?: ""
        f.appendText("$line$stack\n")
      }
    } catch (_: Throwable) {
      // 文件日志失败不影响应用运行
    }
  }

  /**
   * 崩溃落盘：把未捕获异常的完整堆栈 + 内存环形缓冲里的最后现场写入 files/logs/crash.log，
   * 由「设置 → 日志导出」打包带走（荣耀设备 logcat 被加密，这是崩溃排障的唯一通道）。
   * 在 UncaughtExceptionHandler 里调用——必须同步、必须不抛异常。
   */
  fun writeCrashLog(thread: Thread, t: Throwable) {
    try {
      val f = logFile ?: return
      val dir = f.parentFile ?: return
      val out = File(dir, "crash.log")
      val full = buildString {
        appendLine("==== CRASH ${ts.format(Date())} thread=${thread.name} ====")
        appendLine("${t.javaClass.name}: ${t.message}")
        appendLine(t.stackTraceToString())
        t.cause?.let { c ->
          appendLine("---- caused by ----")
          appendLine("${c.javaClass.name}: ${c.message}")
          appendLine(c.stackTraceToString())
        }
        appendLine()
        appendLine("---- 最后 $RING_SIZE 条日志 ----")
        synchronized(lock) { ring.forEach { appendLine(it) } }
        appendLine()
      }
      // 追加而不是覆盖：连续两次崩溃都留档（每次启动导出时一起打包）
      out.appendText(full)
    } catch (_: Throwable) {
      // 崩溃处理绝不能再抛
    }
  }

  private fun rotateIfNeeded(f: File) {
    if (f.length() <= MAX_BYTES) return
    val old = File(f.parentFile, "app.log.old")
    if (old.exists()) old.delete()
    f.renameTo(old)
  }
}
