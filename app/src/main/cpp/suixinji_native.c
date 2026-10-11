/*
 * suixinji_native.c — 原生崩溃捕获（开源反馈诊断体系的一部分）。
 *
 * 背景：LLM（litertlm）与 ASR（ONNX Runtime）都是 JNI 原生引擎，真机闪退大多是
 * SIGSEGV/SIGABRT 类原生崩溃——Java 层 UncaughtExceptionHandler 完全收不到，
 * 荣耀等 ROM 又加密 logcat，用户侧没有任何现场可提供。
 *
 * 方案：安装信号处理器（SIGSEGV/SIGBUS/SIGILL/SIGFPE/SIGABRT/SIGTRAP），
 * 在处理器里用 async-signal-safe 手段往 files/logs/native_crash.log 追加：
 *   信号编号/来源、出错地址、线程名、最多 32 帧回溯地址（可用 addr2line 符号化）
 * 然后恢复默认处理并 re-raise，让系统照常生成 tombstone 并终止进程。
 *
 * 约束：处理器内只用 open/write 与手工进制转换，不做内存分配、不走 stdio。
 * 局限：栈内存耗尽（SIGSEGV on altstack 边界）等极端场景可能写不出来——属已知取舍。
 */
#include <jni.h>
#include <signal.h>
#include <string.h>
#include <stdio.h>
#include <unistd.h>
#include <fcntl.h>
#include <pthread.h>
#include <sys/prctl.h>
#include <unwind.h>
#include <sys/time.h>

#define MAX_FRAMES 32

static char g_log_path[512];
static struct sigaction g_old[6];

static const int k_sigs[6] = { SIGSEGV, SIGBUS, SIGILL, SIGFPE, SIGABRT, SIGTRAP };

/* 无缓冲整数格式化（async-signal-safe）：支持有符号十进制与无符号十六进制 */
static void fmt_dec(char **p, long v) {
  char buf[24];
  int i = 0;
  unsigned long u = (v < 0) ? (unsigned long)(-(v + 1)) + 1UL : (unsigned long)v;
  if (v < 0) *(*p)++ = '-';
  do { buf[i++] = (char)('0' + (u % 10)); u /= 10; } while (u);
  while (i) *(*p)++ = buf[--i];
}

static void fmt_hex(char **p, unsigned long v) {
  const char *d = "0123456789abcdef";
  char buf[20];
  int i = 0;
  do { buf[i++] = d[v & 0xF]; v >>= 4; } while (v);
  *(*p)++ = '0'; *(*p)++ = 'x';
  while (i) *(*p)++ = buf[--i];
}

struct unwind_ctx { char **p; int n; };

static _Unwind_Reason_Code unwind_cb(struct _Unwind_Context *ctx, void *arg) {
  struct unwind_ctx *u = (struct unwind_ctx *)arg;
  uintptr_t pc = _Unwind_GetIP(ctx);
  if (pc == 0) return _URC_END_OF_STACK;
  if (u->n >= MAX_FRAMES) return _URC_END_OF_STACK;
  *(*u->p)++ = ' ';
  fmt_hex(u->p, (unsigned long)pc);
  u->n++;
  return _URC_CONTINUE_UNWIND;
}

static void handler(int sig, siginfo_t *info, void *ctx) {
  /* 找到对应槽位，恢复默认处置（防递归），写日志，re-raise */
  int slot = -1;
  for (int i = 0; i < 6; i++) if (k_sigs[i] == sig) { slot = i; break; }
  if (slot >= 0) sigaction(sig, &g_old[slot], NULL);

  char buf[2048];
  char *p = buf;
#define APPEND(s) do { memcpy(p, s, sizeof(s) - 1); p += sizeof(s) - 1; } while (0)
  APPEND("==== NATIVE CRASH sig=");
  fmt_dec(&p, sig);
  APPEND(" code=");
  fmt_dec(&p, info ? (long)info->si_code : 0);
  APPEND(" addr=");
  fmt_hex(&p, info ? (unsigned long)info->si_addr : 0UL);
  APPEND(" ts=");
  struct timeval tv; gettimeofday(&tv, NULL);
  fmt_dec(&p, (long)tv.tv_sec);
  APPEND("\nthread=");
  char tname[16] = {0};
  prctl(PR_GET_NAME, tname, 0, 0, 0);
  memcpy(p, tname, strnlen(tname, 15)); p += strnlen(tname, 15);
  APPEND("\nbacktrace:");
  struct unwind_ctx u = { &p, 0 };
  _Unwind_Backtrace(unwind_cb, &u);
  APPEND("\n\n");
#undef APPEND

  int fd = open(g_log_path, O_WRONLY | O_CREAT | O_APPEND, 0600);
  if (fd >= 0) {
    ssize_t total = p - buf;
    ssize_t off = 0;
    while (off < total) {
      ssize_t n = write(fd, buf + off, (size_t)(total - off));
      if (n <= 0) break;
      off += n;
    }
    close(fd);
  }
  raise(sig); /* 恢复默认处置后重发，进程按系统预期终止 */
}

JNIEXPORT void JNICALL
Java_com_eli_suixinji_util_CrashSignal_install(JNIEnv *env, jclass clazz, jstring dir) {
  (void)clazz;
  if (dir == NULL) return;
  const char *d = (*env)->GetStringUTFChars(env, dir, NULL);
  if (d == NULL) return;
  snprintf(g_log_path, sizeof(g_log_path), "%s/native_crash.log", d);
  (*env)->ReleaseStringUTFChars(env, dir, d);

  struct sigaction sa;
  memset(&sa, 0, sizeof(sa));
  sa.sa_sigaction = handler;
  sa.sa_flags = SA_SIGINFO;
  sigemptyset(&sa.sa_mask);
  for (int i = 0; i < 6; i++) sigaction(k_sigs[i], &sa, &g_old[i]);
}
