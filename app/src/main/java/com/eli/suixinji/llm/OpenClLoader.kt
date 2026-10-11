package com.eli.suixinji.llm

import android.content.Context
import com.eli.suixinji.util.AppLog
import java.io.File

/**
 * OpenCL 加载器（M2.9）：让 litert 的 GPU 后端能在 ROM 屏蔽厂商库的应用进程里
 * 拿到可用的 libOpenCL。
 *
 * 背景（真机取证 2026-10-02，Honor/骁龙 8 Elite）：
 * - litert 按名 dlopen("libOpenCL.so") 被 ROM 的链接器命名空间挡死（linkerconfig
 *   根本没把厂商公共库接进应用命名空间，public.libraries.txt 形同虚设）；
 * - System.load("/vendor/...") 与 "/system/lib64/..." 的绝对路径加载同样被
 *   "not accessible for namespace clns-*" 拒绝——只有应用私有目录可行；
 * - 但应用域可读 /vendor 文件（SELinux 放行），且依赖闭包完全可枚举。
 *
 * 策略：
 * 1. shim 优先（链接级 DT_NEEDED，命名空间配置正常的 ROM 直接命中）；
 * 2. 依赖闭包拷贝：从 /vendor/lib64/libOpenCL.so 出发，解析 ELF DT_NEEDED +
 *    扫描库内按名 dlopen 的 "lib*.so" 引用，BFS 出完整闭包（vendor+system 库）
 *    全部拷进应用私有目录，按「依赖在前」拓扑序 System.load 注册 soname；
 * 3. dynstr 改写：拷贝库里 "libvndksupport.so"→"libvndkstub.so"（应用内 API
 *    兼容替身，sphal 目标已预载，普通 dlopen 即可命中）、"ld-android.so"→
 *    "libdl.so"（链接器内部库，文件系统不存在，只能换绑到 bionic）。
 *
 * 任何一步失败都只记日志不抛出（GPU 探活自然失败降级 CPU）。
 */
object OpenClLoader {

  private const val TAG = "OpenClLoader"
  private val bionic = setOf(
    "libc.so", "libm.so", "libdl.so",
    // 注意：libdl_android.so / ld-android.so 不在此列——前者需拷贝预载（clns 里
    // 按名解析不到），后者文件系统不存在、由 dynstr 改写换绑到 libdl（真机踩坑）
  )

  /** dynstr 改写表（目标名必须不长于源名，尾部以 \0 填充）。 */
  private val dynstrRewrites = mapOf(
    "libvndksupport.so" to "libvndkstub.so",
    "ld-android.so" to "libdl.so",
  )

  @Volatile private var tried = false
  @Volatile private var loaded = false

  /** 尝试把 OpenCL 装进进程；返回是否有希望（已载入或闭包加载过）。 */
  fun preload(context: Context): Boolean {
    if (tried) return loaded
    tried = true
    try {
      loaded = load(context)
    } catch (t: Throwable) {
      AppLog.w("$TAG：加载流程异常", t)
      loaded = false
    }
    return loaded
  }

  private fun load(context: Context): Boolean {
    // —— 策略 1：链接级 shim（ROM 命名空间正常时一步到位）——
    runCatching { System.loadLibrary("opencl_shim") }
      .onSuccess {
        AppLog.i("$TAG：opencl_shim 已载入（厂商 libOpenCL 随之进进程）")
        return true
      }
      .onFailure { AppLog.w("$TAG：opencl_shim 加载失败 → ${it.message ?: it.javaClass.simpleName}") }

    // —— 策略 2：依赖闭包拷贝（vendor 公共库机制未接线的 ROM）——
    return loadByClosure(context)
  }

  private fun loadByClosure(context: Context): Boolean {
    // v3：换目录避开旧版遗留的拷贝缓存（旧版没有 dynstr 改写，文件不可复用）
    val dir = File(context.filesDir, "opencl_v3").apply { mkdirs() }
    // 真机实测：clns-* 里 /system/lib64 与 /vendor/lib64 绝对路径加载统统被拒，
    // 只有应用私有目录可行；公共库（NDK 稳定 API）自动可按名解析，无需预载。
    val publicBySoname = setOf(
      "liblog.so", "libz.so", "libc++_shared.so",
      "libEGL.so", "libGLESv1_CM.so", "libGLESv2.so", "libGLESv3.so",
      "libvulkan.so", "libandroid.so", "libjnigraphics.so",
      "libmediandk.so", "libnativehelper.so", "libOpenSLES.so",
    )
    val state = HashMap<String, String>()        // name → visiting / copied / public / missing / stub
    val order = ArrayList<Pair<String, File>>()  // 拓扑序（依赖在前）：加载清单

    fun classify(name: String): String? = when {
      File("/vendor/lib64/$name").isFile -> "vendor"
      File("/system/lib64/$name").isFile -> "system"
      name in publicBySoname -> "public"
      else -> null
    }

    fun visit(name: String) {
      if (state.containsKey(name) || name in bionic) return
      // libvndksupport 用应用内替身（vndkstub，随 APK 打包）；其 dynstr 已改写
      val isVndkSupport = name == "libvndksupport.so" || name == "libvndkstub.so"
      val kind = if (isVndkSupport) "stub" else classify(name)
      if (kind == null) {
        state[name] = "missing"
        AppLog.w("$TAG：闭包组件缺失（跳过）：$name")
        return
      }
      if (kind == "public") {
        state[name] = "public"   // 公共库：clns 内自动可按名解析，不预载
        return
      }
      state[name] = "visiting"
      if (kind == "stub") {
        runCatching { System.loadLibrary("vndkstub") }
          .onSuccess { AppLog.i("$TAG：vndkstub（libvndksupport 替身）已载入") }
          .onFailure { AppLog.w("$TAG：vndkstub 载入失败 → ${it.message ?: it.javaClass.simpleName}") }
        state[name] = "stub"
        return
      }
      val dst = File(dir, name)
      if (!dst.isFile || dst.length() == 0L) {
        val src = if (kind == "vendor") "/vendor/lib64/$name" else "/system/lib64/$name"
        File(src).inputStream().use { ins ->
          dst.outputStream().use { os -> ins.copyTo(os, 1 shl 16) }
        }
      }
      patchDynstr(dst)
      val bytes = dst.readBytes()
      // 静态 DT_NEEDED + 库内按名 dlopen 名单都跟进（libOpenCL 运行时才
      // dlopen libOpenCL_adreno；adreno 再拉 libCB/libgsl——静态 NEEDED 里没有，
      // 只靠 strings 扫描才能进闭包）。strings 会带进一些 framework 边缘库
      // （libhwui/libincident 等），加载失败亦无碍——不在 libCB 的依赖链上。
      val deps = (parseDyn(bytes)?.needed ?: emptySet()).toMutableSet()
      deps += soNames(bytes)
      deps.removeAll(bionic)
      deps.forEach { visit(it) }
      state[name] = "copied"
      order.add(name to dst)
    }

    visit("libOpenCL.so")
    // 探针：预载完成后验证 clns 内按名 dlopen + OpenCL 平台枚举（logcat: OpenClProbe）
    runCatching { System.loadLibrary("opencl_probe") }
      .onFailure { AppLog.w("$TAG：opencl_probe 载入失败 → ${it.message}") }
    if (order.none { it.first == "libOpenCL.so" }) {
      AppLog.w("$TAG：libOpenCL.so 本体不在闭包内，放弃")
      return false
    }

    var ok = 0
    var fail = 0
    var mainLoaded = false
    for ((name, file) in order) {
      try {
        System.load(file.absolutePath)
        ok++
        if (name == "libOpenCL.so") mainLoaded = true
      } catch (t: Throwable) {
        fail++
        AppLog.w("$TAG：闭包载入失败 $name → ${t.message ?: t.javaClass.simpleName}")
      }
    }
    AppLog.i("$TAG：OpenCL 依赖闭包预载完成 ok=$ok fail=$fail（共 ${order.size}，缺失见上）")
    return mainLoaded
  }

  // ---------- dynstr 改写 ----------

  /**
   * 把拷贝库 .dynstr 里命中的依赖名原地改写（新名 + \0 填充至等长）。
   * 只改写 dynstr 区间内、以完整 "\0…\0" 边界存在的字符串。
   */
  private fun patchDynstr(file: File) {
    runCatching {
      val bytes = file.readBytes()
      val info = parseDyn(bytes) ?: return@runCatching
      var changed = false
      for ((old, new) in dynstrRewrites) {
        val pat = old.toByteArray(Charsets.US_ASCII)
        if (new.length > old.length) continue
        val rep = new.toByteArray(Charsets.US_ASCII) + ByteArray(pat.size - new.length)
        var i = info.strOff
        val end = info.strOff + info.strSize
        while (i + pat.size + 1 <= end && i + pat.size < bytes.size) {
          var match = true
          for (j in pat.indices) {
            if (bytes[i + j] != pat[j]) { match = false; break }
          }
          if (match && bytes[i + pat.size] != 0.toByte()) match = false
          if (match) {
            for (j in rep.indices) bytes[i + j] = rep[j]
            changed = true
            i += pat.size
          } else {
            i++
          }
        }
      }
      if (changed) file.writeBytes(bytes)
    }
  }

  // ---------- 轻量 ELF64 解析（小端 aarch64） ----------

  private class DynInfo(val needed: Set<String>, val strOff: Int, val strSize: Int)

  /** 解析 DT_NEEDED 名单与 .dynstr 区间（文件偏移）。 */
  private fun parseDyn(bytes: ByteArray): DynInfo? = runCatching {
    fun u16(o: Int) = (bytes[o].toInt() and 0xFF) or ((bytes[o + 1].toInt() and 0xFF) shl 8)
    fun u32(o: Int) = (u16(o).toLong() and 0xFFFF) or ((u16(o + 2).toLong() and 0xFFFF) shl 16)
    fun u64(o: Int) = (u32(o) and 0xFFFFFFFFL) or ((u32(o + 4) and 0xFFFFFFFFL) shl 32)

    if (bytes.size < 64 || bytes[4] != 2.toByte()) return null  // ELF64 小端
    val phOff = u64(0x20).toInt()
    val phEnt = u16(0x36)
    val phNum = u16(0x38)

    var dynOff = 0L; var dynSize = 0L
    val loads = ArrayList<Triple<Long, Long, Long>>() // vaddr, offset, filesz
    for (i in 0 until phNum) {
      val p = phOff + i * phEnt
      when (u32(p).toInt()) {
        1 -> loads.add(Triple(u64(p + 0x10), u64(p + 0x08), u64(p + 0x20)))  // PT_LOAD: vaddr/offset/filesz
        2 -> { dynOff = u64(p + 0x08); dynSize = u64(p + 0x20) }             // PT_DYNAMIC
      }
    }
    if (dynOff == 0L) return null
    fun v2o(v: Long): Int {
      for ((va, off, fsz) in loads) {
        if (v >= va && v < va + fsz) return (off + (v - va)).toInt()
      }
      return v.toInt()
    }

    var strTabV = 0L
    val entries = ArrayList<Pair<Long, Long>>()
    var o = dynOff.toInt()
    val end = (dynOff + dynSize).toInt()
    while (o + 16 <= end && o + 16 <= bytes.size) {
      val tag = u64(o); val v = u64(o + 8)
      if (tag == 0L) break
      if (tag == 5L) strTabV = v
      entries.add(tag to v)
      o += 16
    }
    if (strTabV == 0L) return null
    val strOff = v2o(strTabV)
    // dynstr 上界取「到文件尾」：改写只在其中做精确边界匹配，安全
    val strSize = bytes.size - strOff

    fun cstr(off: Int): String {
      val sb = StringBuilder()
      var i = off
      while (i < bytes.size && bytes[i] != 0.toByte()) { sb.append(bytes[i].toInt().toChar()); i++ }
      return sb.toString()
    }

    val needed = entries.filter { it.first == 1L }
      .map { cstr(strOff + it.second.toInt()) }
      .filter { it.startsWith("lib") && it.endsWith(".so") }
      .toSet()
    DynInfo(needed, strOff, strSize)
  }.getOrNull()

  /** 扫描库文件里出现的按名 dlopen 引用（lib*.so 模式）。 */
  private fun soNames(bytes: ByteArray): Set<String> = runCatching {
    val text = StringBuilder(bytes.size / 8)
    var i = 0
    val n = bytes.size
    while (i < n) {
      val b = bytes[i].toInt() and 0xFF
      text.append(if (b in 0x20..0x7E) b.toChar() else ' ')
      i++
    }
    Regex("lib[A-Za-z0-9_+]+\\.so").findAll(text).map { it.value }.toSet()
  }.getOrDefault(emptySet())
}
