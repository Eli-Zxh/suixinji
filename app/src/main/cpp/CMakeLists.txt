# 随心记 原生构建（M2.9）：OpenCL 链接级 shim
# 详见 opencl_shim.c 头注释。仅 arm64-v8a（gradle abiFilters 已裁其余）。
cmake_minimum_required(VERSION 3.22)
project(suixinji_native C)

# 构建期空壳 libOpenCL.so（IMPORTED 目标不打包进 APK；运行期解析到厂商真库）
add_library(OpenCL SHARED IMPORTED)
set_target_properties(OpenCL PROPERTIES
  IMPORTED_LOCATION ${CMAKE_CURRENT_SOURCE_DIR}/stub/${ANDROID_ABI}/libOpenCL.so)

# shim v2：不做任何 OpenCL 调用，只做命名空间穿透（android_dlopen_ext +
# exported/create namespace）——绝不能链接 OpenCL stub：DT_NEEDED libOpenCL.so
# 在本 ROM 的应用命名空间解析不到，会导致 shim 本体 dlopen 失败、JNI_OnLoad
# 永远不执行（M2.9 最后一轮真机定位的根因）。
add_library(opencl_shim SHARED opencl_shim.c)
target_link_libraries(opencl_shim PRIVATE log)

# 原生崩溃捕获：信号处理器 → files/logs/native_crash.log（signo/addr/回溯地址）。
# App.onCreate 尽早 System.loadLibrary 安装——litertlm/ONNX 的 SIGSEGV 也要有现场。
add_library(suixinji_native SHARED suixinji_native.c)
target_link_libraries(suixinji_native PRIVATE log)

# libvndksupport 的应用内替身（API 兼容；内部用普通 dlopen——sphal 目标已预载）。
# 拷贝库的 dynstr 会被改写指向 libvndkstub.so，见 OpenClLoader.patchDynstr。
add_library(vndkstub SHARED vndkstub.c)

# OpenCL 探针（排障工具）：载入时自动 dlopen+枚举平台，结果打 logcat(tag=OpenClProbe)
add_library(opencl_probe SHARED opencl_probe.c)
target_link_libraries(opencl_probe PRIVATE log)
