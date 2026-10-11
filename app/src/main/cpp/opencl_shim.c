/*
 * OpenCL 链接级 shim v2（M2.9 定稿方案）：命名空间穿透加载厂商 libOpenCL。
 *
 * 背景（真机取证 2026-10-02，Honor/骁龙 8 Elite）：
 * - litert 按名 dlopen("libOpenCL.so") 被应用命名空间挡死（本机 linkerconfig
 *   没把 /vendor/etc/public.libraries.txt 接进应用命名空间）；
 * - System.load 绝对路径加载 vendor/system 库同样被拒（非公开依赖解析不到）；
 * - 用户态闭包拷贝方案实测 132/234 载入成功，但预载库的 soname 不被后续
 *   按名 dlopen 命中（bionic nativeloader 深层行为，8 轮未收敛）。
 *
 * 定稿方案（BOINC/boinc#5931 验证路径，S24 Ultra/Adreno 实测成功）：
 * 通过 android_get_exported_namespace("sphal"/"vndk"/…) 拿到能看见厂商库的
 * 命名空间（无则 android_create_namespace 自建，允许 /system:/vendor:/odm），
 * android_dlopen_ext(ANDROID_DLEXT_USE_NAMESPACE) 在该命名空间里加载
 * /vendor/lib64/libOpenCL.so——其私有依赖（libOpenCL_adreno→libCB→libgsl）
 * 在 vendor 侧自洽；加载结果注册回本进程命名空间（soname=libOpenCL.so），
 * litert 随后按名 dlopen 即命中。不拷贝、不改写任何库。
 *
 * 本机实测：ld.config.txt 的 sphal 命名空间 search.paths 含 /vendor/lib64 ✓
 */
#include <jni.h>
#include <dlfcn.h>
#include <stdio.h>
#include <stdarg.h>
#include <android/log.h>
#include <android/dlext.h>
#include <stdint.h>

#define LOGI(...) do { __android_log_print(ANDROID_LOG_INFO, "OpenClShim", __VA_ARGS__); flog("I", __VA_ARGS__); } while (0)
#define LOGW(...) do { __android_log_print(ANDROID_LOG_WARN, "OpenClShim", __VA_ARGS__); flog("W", __VA_ARGS__); } while (0)

/* 双写文件日志：logcat 缓冲会滚动，命名空间穿透的结果必须留档 */
static void flog(const char* level, const char* fmt, ...) {
  FILE* f = fopen("/data/data/com.eli.suixinji/files/logs/shim.log", "a");
  if (f == NULL) return;
  va_list ap;
  va_start(ap, fmt);
  fprintf(f, "%s: ", level);
  vfprintf(f, fmt, ap);
  fprintf(f, "\n");
  va_end(ap);
  fclose(f);
}

struct android_namespace_t;  // 平台不透明类型

typedef struct android_namespace_t android_namespace_t;
typedef android_namespace_t* (*fn_get_exported_ns)(const char*);
typedef android_namespace_t* (*fn_get_current_ns)(void);
typedef android_namespace_t* (*fn_create_ns)(
    const char* name, const char* ld_library_path, const char* default_library_path,
    uint64_t type, const char* permitted_when_isolated_path, android_namespace_t* parent);

/* linker 内部 namespace type：ISOLATED=1, SHARED=2（bionic linker_defs.h） */
#define NS_TYPE (1ULL | 2ULL)

/** 依次尝试 sphal/vndk/rs 导出命名空间；全空则自建（BOINC 方案 3）。 */
static android_namespace_t* acquire_namespace(void) {
  fn_get_exported_ns get_ns =
      (fn_get_exported_ns)dlsym(RTLD_DEFAULT, "android_get_exported_namespace");
  if (get_ns == NULL) {
    get_ns = (fn_get_exported_ns)dlsym(RTLD_DEFAULT, "__loader_android_get_exported_namespace");
  }
  if (get_ns != NULL) {
    const char* names[] = {"sphal", "vndk", "rs", "vendor_default"};
    for (int i = 0; i < 4; i++) {
      android_namespace_t* ns = get_ns(names[i]);
      LOGI("exported_namespace(%s) → %p", names[i], (void*)ns);
      if (ns != NULL) return ns;
    }
  } else {
    LOGW("dlsym android_get_exported_namespace 失败");
  }

  // 自建命名空间：允许搜索 /system /vendor /odm 与应用私有目录（BOINC 方案 3）
  fn_create_ns create_ns =
      (fn_create_ns)dlsym(RTLD_DEFAULT, "android_create_namespace");
  if (create_ns == NULL) {
    create_ns = (fn_create_ns)dlsym(RTLD_DEFAULT, "__loader_android_create_namespace");
  }
  if (create_ns == NULL) {
    LOGW("dlsym android_create_namespace 失败");
    return NULL;
  }
  fn_get_current_ns get_cur =
      (fn_get_current_ns)dlsym(RTLD_DEFAULT, "android_get_current_namespace");
  android_namespace_t* parent = get_cur ? get_cur() : NULL;
  android_namespace_t* ns = create_ns(
      "suixinji_opencl", NULL,
      "/system/lib64:/vendor/lib64:/odm/lib64:/data/data/com.eli.suixinji/files/opencl_v3",
      NS_TYPE, NULL, parent);
  LOGI("android_create_namespace(suixinji_opencl) → %p", (void*)ns);
  return ns;
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
  (void)vm; (void)reserved;

  // 直连尝试：ROM 命名空间正常时直接命中
  void* h = dlopen("libOpenCL.so", RTLD_NOW | RTLD_LOCAL);
  if (h != NULL) {
    LOGI("直接 dlopen 成功（命名空间正常）句柄=%p", h);
    return JNI_VERSION_1_6;
  }
  LOGW("直接 dlopen 失败 → %s", dlerror());

  // 命名空间穿透：vendor 侧解析依赖，soname 注册回本进程
  android_namespace_t* ns = acquire_namespace();
  if (ns == NULL) {
    LOGW("无可用命名空间——GPU 不可用，litert 将降级 CPU");
    return JNI_VERSION_1_6;
  }

  android_dlextinfo info = {};
  info.flags = ANDROID_DLEXT_USE_NAMESPACE;
  info.library_namespace = ns;
  void* h2 = android_dlopen_ext("libOpenCL.so", RTLD_NOW | RTLD_LOCAL, &info);
  if (h2 != NULL) {
    LOGI("android_dlopen_ext(vendor ns) 成功 → %p：libOpenCL 已注册（按名命中通道打通）", h2);
  } else {
    LOGW("android_dlopen_ext 失败 → %s", dlerror());
  }
  return JNI_VERSION_1_6;
}
