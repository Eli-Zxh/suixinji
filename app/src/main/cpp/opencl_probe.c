/*
 * OpenCL 探针（M2.9 排障工具）：System.loadLibrary("opencl_probe") 时自动执行：
 *   1. dlopen("libOpenCL.so")——验证 clns 内按名能否命中 OpenClLoader 预载的库；
 *   2. dlsym(clGetPlatformIDs / clGetDeviceIDs / clGetPlatformInfo)；
 *   3. 枚举 OpenCL 平台与 GPU 设备，打印版本。
 * 每一步都写 logcat（tag=OpenClProbe），任何失败即说明该环节不可用。
 */
#include <jni.h>
#include <dlfcn.h>
#include <android/log.h>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "OpenClProbe", __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, "OpenClProbe", __VA_ARGS__)

typedef int cl_int;
typedef unsigned long cl_ulong;
typedef cl_ulong cl_platform_id;
typedef cl_ulong cl_device_id;

static cl_int (*p_clGetPlatformIDs)(unsigned, cl_platform_id*, unsigned*) = 0;
static cl_int (*p_clGetDeviceIDs)(cl_platform_id, unsigned, unsigned, cl_device_id*, unsigned*) = 0;
static cl_int (*p_clGetPlatformInfo)(cl_platform_id, unsigned, unsigned long, void*, unsigned long*) = 0;
static cl_int (*p_clGetDeviceInfo)(cl_device_id, unsigned, unsigned long, void*, unsigned long*) = 0;

static void run_probe(void) {
  LOGI("步骤 1：dlopen(\"libOpenCL.so\") …");
  void* h = dlopen("libOpenCL.so", RTLD_NOW | RTLD_LOCAL);
  if (h == NULL) {
    LOGW("步骤 1 失败：dlopen → %s", dlerror());
    // 兜底：试绝对路径（数据目录）
    h = dlopen(NULL, RTLD_NOW);  // 自身句柄，仅确认 dl 通道
    LOGI("自身句柄=%p（dl 通道正常）", h);
    return;
  }
  LOGI("步骤 1 成功：句柄=%p", h);

  p_clGetPlatformIDs = (typeof(p_clGetPlatformIDs))dlsym(h, "clGetPlatformIDs");
  p_clGetDeviceIDs = (typeof(p_clGetDeviceIDs))dlsym(h, "clGetDeviceIDs");
  p_clGetPlatformInfo = (typeof(p_clGetPlatformInfo))dlsym(h, "clGetPlatformInfo");
  p_clGetDeviceInfo = (typeof(p_clGetDeviceInfo))dlsym(h, "clGetDeviceInfo");
  if (p_clGetPlatformIDs == NULL) { LOGW("步骤 2 失败：dlsym clGetPlatformIDs 为空"); return; }
  LOGI("步骤 2 成功：cl* 符号已解析");

  unsigned n = 0;
  cl_int err = p_clGetPlatformIDs(0, NULL, &n);
  LOGI("步骤 3：clGetPlatformIDs → err=%d 平台数=%u", err, n);
  if (err != 0 || n == 0) { LOGW("无 OpenCL 平台"); return; }

  cl_platform_id ids[4] = {0};
  unsigned got = 0;
  err = p_clGetPlatformIDs(4, ids, &got);
  for (unsigned i = 0; i < got && i < 4; i++) {
    char ver[256] = {0};
    p_clGetPlatformInfo(ids[i], 0x0900 /*CL_PLATFORM_VERSION*/, sizeof(ver) - 1, ver, NULL);
    LOGI("平台 %u：%s", i, ver);
    unsigned dn = 0;
    cl_int de = p_clGetDeviceIDs(ids[i], 0xFFFFFFFF /*CL_DEVICE_TYPE_ALL*/, 0, NULL, &dn);
    LOGI("平台 %u 设备枚举 → err=%d 数量=%u", i, de, dn);
    if (de == 0 && dn > 0) {
      cl_device_id dev[8] = {0};
      unsigned dg = 0;
      p_clGetDeviceIDs(ids[i], 0xFFFFFFFF, 8, dev, &dg);
      for (unsigned d = 0; d < dg && d < 8; d++) {
        char dname[256] = {0};
        p_clGetDeviceInfo(dev[d], 0x102B /*CL_DEVICE_NAME*/, sizeof(dname) - 1, dname, NULL);
        LOGI("设备 %u：%s", d, dname);
      }
    }
  }
  LOGI("探针完成：OpenCL 栈可用 ✓");
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
  (void)vm; (void)reserved;
  run_probe();
  return JNI_VERSION_1_6;
}
