/*
 * libvndksupport 的应用内替身（M2.9）：
 * ROM 把厂商库屏蔽在应用命名空间之外后，libOpenCL_adreno/libutils 等拷贝库的
 * DT_NEEDED "libvndksupport.so" 无法解析。这里提供同名 API 的轻量替身：
 * android_load_sphal_library 直接用普通 dlopen——sphal 目标（libCB/libgsl 等）
 * 已由 OpenClLoader 预载进 clns 命名空间，按名即可命中。
 * 注意：不能命名为 libvndksupport.so（拷贝库的 dynstr 会被改写到本库名）。
 */
#include <dlfcn.h>
#include <stddef.h>

void* android_load_sphal_library(const char* name, int flag) {
  if (name == NULL) return NULL;
  return dlopen(name, flag | RTLD_LOCAL);
}

int android_unload_sphal_library(void* handle) {
  if (handle == NULL) return 0;
  return dlclose(handle);
}
