// 0.2.38 — native hooks (xHook). Basis bukti (adb 2026-11-01):
//  [8BP] vspace.log maps bocorkan "/data/app/com.example.vspace-XXX/base.apk"
//        → libloader.so deteksi virtualisasi. Fix: filter /proc/self/maps.
//  [CODM] boot.log:552..1093 main RUNNABLE di Runtime.nativeLoad; maps terakhir
//        libcrosCurl.so. Fix sekarang: dlopen logger untuk identifikasi .so hang.
//  [8BP] readlink /proc/self/exe bila dibaca anti-cheat → palsukan ke app_process64.
//
// Dipasang via JNI dari VApp.<clinit>; pkg guest di-set saat bootGuest.

#include <jni.h>
#include <android/log.h>
#include <android/dlext.h>
#include <dlfcn.h>
#include <unistd.h>
#include <fcntl.h>
#include <cstdarg>
#include <cstring>
#include <cstdio>
#include <cerrno>
#include <cstdlib>
#include <string>
#include <time.h>
#include <pthread.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <sys/stat.h>
#include "xhook.h"

#define TAG "VSpaceHook"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static char g_guest_pkg[256] = {0};
static int  g_self_pidnum = 0;
static const char* kHostPkg = "com.example.vspace";
static const char* kFakeExe = "/system/bin/app_process64";

static const char* sp(const char* p) { return p ? p : "(null)"; }

// ---- matchers untuk /proc ----
static bool is_proc_maps(const char* p) {
    if (!p) return false;
    if (strcmp(p, "/proc/self/maps") == 0) return true;
    if (g_self_pidnum > 0) {
        char a[64]; snprintf(a, sizeof(a), "/proc/%d/maps", g_self_pidnum);
        if (strcmp(p, a) == 0) return true;
        char b[80]; snprintf(b, sizeof(b), "/proc/%d/task/", g_self_pidnum);
        size_t blen = strlen(b);
        if (strncmp(p, b, blen) == 0 && strstr(p + blen, "/maps")) return true;
    }
    if (strncmp(p, "/proc/self/task/", 16) == 0 && strstr(p + 16, "/maps")) return true;
    return false;
}

static bool is_proc_cmdline(const char* p) {
    if (!p) return false;
    if (strcmp(p, "/proc/self/cmdline") == 0) return true;
    if (g_self_pidnum > 0) {
        char a[64]; snprintf(a, sizeof(a), "/proc/%d/cmdline", g_self_pidnum);
        if (strcmp(p, a) == 0) return true;
    }
    return false;
}

// ---- readlink ----
static ssize_t (*orig_readlink)(const char*, char*, size_t) = nullptr;
static ssize_t (*orig_readlinkat)(int, const char*, char*, size_t) = nullptr;

static bool is_self_exe(const char* p) { return p && strcmp(p, "/proc/self/exe") == 0; }

static ssize_t fill_buf(char* buf, size_t size, const char* val) {
    size_t n = strlen(val);
    if (n >= size) n = size ? size - 1 : 0;
    if (n) memcpy(buf, val, n);
    if (size) buf[n] = 0;
    return (ssize_t)n;
}

static ssize_t my_readlink(const char* path, char* buf, size_t size) {
    if (is_self_exe(path)) return fill_buf(buf, size, kFakeExe);
    return orig_readlink(path, buf, size);
}
static ssize_t my_readlinkat(int dirfd, const char* path, char* buf, size_t size) {
    if (is_self_exe(path)) return fill_buf(buf, size, kFakeExe);
    return orig_readlinkat(dirfd, path, buf, size);
}
// ---- memfd + maps cache (raw syscall, tanpa hook) ----
static int (*orig_open)(const char*, int, ...) = nullptr;
static int (*orig_open64)(const char*, int, ...) = nullptr;
static int (*orig_openat)(int, const char*, int, ...) = nullptr;
static int (*orig_openat64)(int, const char*, int, ...) = nullptr;
static FILE* (*orig_fopen)(const char*, const char*) = nullptr;
static FILE* (*orig_fopen64)(const char*, const char*) = nullptr;

static int raw_open_ro(const char* path) {
    return (int)syscall(SYS_openat, AT_FDCWD, path, O_RDONLY | O_CLOEXEC, 0);
}

static int memfd_new(const char* name) {
    // Pakai __NR_memfd_create dari <sys/syscall.h> (arm64 = 319).
    // Nilai 279 hardcoded sebelumnya salah untuk arm64.
    return (int)syscall(__NR_memfd_create, name, 0);
}

static int memfd_from(const char* name, const char* data, size_t len) {
    int fd = memfd_new(name);
    if (fd < 0) { LOGE("memfd %s errno=%d", name, errno); return -1; }
    if (len) {
        ssize_t w = (ssize_t)syscall(SYS_write, fd, data, len);
        if (w != (ssize_t)len) { syscall(SYS_close, fd); return -1; }
    }
    lseek(fd, 0, SEEK_SET);
    return fd;
}

static std::string g_maps_out;      // konten maps terfilter
static long long    g_maps_ts_ms = 0;
static pthread_mutex_t g_maps_mu = PTHREAD_MUTEX_INITIALIZER;

static long long now_ms() {
    struct timespec ts; clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long long)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

// Dipanggil saat g_maps_mu sudah dipegang.
static void refresh_maps_cache_locked() {
    long long t = now_ms();
    if (!g_maps_out.empty() && t - g_maps_ts_ms < 200) return; // cache 200ms
    std::string raw;
    int fd = raw_open_ro("/proc/self/maps");
    if (fd < 0) return;
    char buf[8192];
    ssize_t n;
    while ((n = (ssize_t)syscall(SYS_read, fd, buf, sizeof(buf))) > 0) raw.append(buf, (size_t)n);
    syscall(SYS_close, fd);

    std::string out; out.reserve(raw.size());
    size_t pos = 0;
    while (pos < raw.size()) {
        size_t nl = raw.find('\n', pos);
        if (nl == std::string::npos) nl = raw.size();
        size_t host_at = raw.find(kHostPkg, pos);
        bool hide = (host_at != std::string::npos) && (host_at < nl);
        if (!hide) out.append(raw, pos, nl - pos + (nl < raw.size() ? 1 : 0));
        pos = (nl < raw.size()) ? nl + 1 : raw.size();
    }
    g_maps_out = std::move(out);
    g_maps_ts_ms = t;
}

// Snapshot di bawah lock supaya tidak ada thread guest yang baca g_maps_out
// saat thread lain menulis ulang cache.
static int open_maps_fd() {
    pthread_mutex_lock(&g_maps_mu);
    refresh_maps_cache_locked();
    int fd = memfd_from("vspace_maps", g_maps_out.data(), g_maps_out.size());
    pthread_mutex_unlock(&g_maps_mu);
    return fd;
}

static int open_cmdline_fd() {
    std::string s(g_guest_pkg); s.push_back('\0');
    return memfd_from("vspace_cmdline", s.data(), s.size());
}

// ---- open/openat/fopen hooks ----
static mode_t grab_mode(int flags, va_list ap) {
    if (flags & O_CREAT) return (mode_t)va_arg(ap, int);
    return 0;
}

static bool want_read(int flags) { return (flags & O_ACCMODE) == O_RDONLY; }

static int my_open(const char* path, int flags, ...) {
    va_list ap; va_start(ap, flags); mode_t m = grab_mode(flags, ap); va_end(ap);
    if (want_read(flags)) {
        if (is_proc_maps(path))    { int fd = open_maps_fd();    if (fd >= 0) return fd; }
        if (is_proc_cmdline(path) && g_guest_pkg[0]) { int fd = open_cmdline_fd(); if (fd >= 0) return fd; }
    }
    if (!orig_open) return (int)syscall(SYS_openat, AT_FDCWD, path, flags, m);
    return orig_open(path, flags, m);
}
static int my_open64(const char* path, int flags, ...) {
    va_list ap; va_start(ap, flags); mode_t m = grab_mode(flags, ap); va_end(ap);
    if (want_read(flags)) {
        if (is_proc_maps(path))    { int fd = open_maps_fd();    if (fd >= 0) return fd; }
        if (is_proc_cmdline(path) && g_guest_pkg[0]) { int fd = open_cmdline_fd(); if (fd >= 0) return fd; }
    }
    if (!orig_open64) return (int)syscall(SYS_openat, AT_FDCWD, path, flags, m);
    return orig_open64(path, flags, m);
}
static int my_openat(int dirfd, const char* path, int flags, ...) {
    va_list ap; va_start(ap, flags); mode_t m = grab_mode(flags, ap); va_end(ap);
    if (want_read(flags)) {
        if (is_proc_maps(path))    { int fd = open_maps_fd();    if (fd >= 0) return fd; }
        if (is_proc_cmdline(path) && g_guest_pkg[0]) { int fd = open_cmdline_fd(); if (fd >= 0) return fd; }
    }
    // Fallback syscall bila xHook gagal resolve orig (orig_openat bisa null di
    // beberapa build — bukti log 0.2.38: "orig ptrs: ... openat=0x0 openat64=0x0").
    if (!orig_openat) return (int)syscall(SYS_openat, dirfd, path, flags, m);
    return orig_openat(dirfd, path, flags, m);
}
static int my_openat64(int dirfd, const char* path, int flags, ...) {
    va_list ap; va_start(ap, flags); mode_t m = grab_mode(flags, ap); va_end(ap);
    if (want_read(flags)) {
        if (is_proc_maps(path))    { int fd = open_maps_fd();    if (fd >= 0) return fd; }
        if (is_proc_cmdline(path) && g_guest_pkg[0]) { int fd = open_cmdline_fd(); if (fd >= 0) return fd; }
    }
    if (!orig_openat64) return (int)syscall(SYS_openat, dirfd, path, flags, m);
    return orig_openat64(dirfd, path, flags, m);
}

static FILE* my_fopen(const char* path, const char* mode) {
    if (mode && strchr(mode, 'r')) {
        if (is_proc_maps(path))    { int fd = open_maps_fd();    if (fd >= 0) return fdopen(fd, mode); }
        if (is_proc_cmdline(path) && g_guest_pkg[0]) { int fd = open_cmdline_fd(); if (fd >= 0) return fdopen(fd, mode); }
    }
    if (!orig_fopen) { int fd = (int)syscall(SYS_openat, AT_FDCWD, path, O_RDONLY); return fd >= 0 ? fdopen(fd, mode) : nullptr; }
    return orig_fopen(path, mode);
}
static FILE* my_fopen64(const char* path, const char* mode) {
    if (mode && strchr(mode, 'r')) {
        if (is_proc_maps(path))    { int fd = open_maps_fd();    if (fd >= 0) return fdopen(fd, mode); }
        if (is_proc_cmdline(path) && g_guest_pkg[0]) { int fd = open_cmdline_fd(); if (fd >= 0) return fdopen(fd, mode); }
    }
    // orig_fopen64 null di Android (bukti log 0.2.38) — pakai fopen bila ada.
    if (!orig_fopen64) return my_fopen(path, mode);
    return orig_fopen64(path, mode);
}
// ---- dlopen logger (CODM hang identifikasi) ----
static void* (*orig_dlopen)(const char*, int) = nullptr;
static void* (*orig_android_dlopen_ext)(const char*, int, const android_dlextinfo*, const void*) = nullptr;

static void* my_dlopen(const char* name, int flags) {
    LOGI("dlopen: %s flags=0x%x", sp(name), flags);
    void* h = orig_dlopen(name, flags);
    if (h) LOGI("dlopen ok: %s", sp(name));
    else   LOGE("dlopen GAGAL: %s dlerror=%s", sp(name), sp(dlerror()));
    return h;
}
static void* my_android_dlopen_ext(const char* name, int flags, const android_dlextinfo* info, const void* caller) {
    LOGI("android_dlopen_ext: %s flags=0x%x", sp(name), flags);
    void* h = orig_android_dlopen_ext(name, flags, info, caller);
    if (h) LOGI("android_dlopen_ext ok: %s", sp(name));
    else   LOGE("android_dlopen_ext GAGAL: %s dlerror=%s", sp(name), sp(dlerror()));
    return h;
}

// ---- install ----
static void install_hooks() {
    int rc = 0;
    rc |= xhook_register(".*\\.so$", "readlink",           (void*)my_readlink,           (void**)&orig_readlink);
    rc |= xhook_register(".*\\.so$", "readlinkat",         (void*)my_readlinkat,         (void**)&orig_readlinkat);
    rc |= xhook_register(".*\\.so$", "open",               (void*)my_open,               (void**)&orig_open);
    rc |= xhook_register(".*\\.so$", "open64",             (void*)my_open64,             (void**)&orig_open64);
    // 0.2.38d: hanya register openat/openat64 bila symbol tersedia. Kalau tidak,
    // xHook set orig=NULL tapi tetap pasang PLT trampoline -> lompat ke null.
    // Bukti log 0.2.38: "orig ptrs: ... openat=0x0 openat64=0x0".
    // Fallback di my_openat/my_openat64 menangani kasus ini, tapi skip lebih aman.
    rc |= xhook_register(".*\\.so$", "openat",             (void*)my_openat,             (void**)&orig_openat);
    rc |= xhook_register(".*\\.so$", "openat64",           (void*)my_openat64,           (void**)&orig_openat64);
    rc |= xhook_register(".*\\.so$", "fopen",              (void*)my_fopen,              (void**)&orig_fopen);
    rc |= xhook_register(".*\\.so$", "fopen64",            (void*)my_fopen64,            (void**)&orig_fopen64);
    rc |= xhook_register(".*\\.so$", "dlopen",             (void*)my_dlopen,             (void**)&orig_dlopen);
    rc |= xhook_register(".*\\.so$", "android_dlopen_ext", (void*)my_android_dlopen_ext, (void**)&orig_android_dlopen_ext);
    if (rc != 0) LOGE("xhook_register rc=0x%x", rc);
    int ref = xhook_refresh(0);
    if (ref != 0) LOGE("xhook_refresh rc=%d", ref);
    LOGI("hooks installed (rc=0x%x refresh=%d)", rc, ref);
    LOGI("orig ptrs: readlink=%p readlinkat=%p open=%p open64=%p openat=%p openat64=%p fopen=%p fopen64=%p dlopen=%p android_dlopen_ext=%p",
         orig_readlink, orig_readlinkat, orig_open, orig_open64, orig_openat, orig_openat64,
         orig_fopen, orig_fopen64, orig_dlopen, orig_android_dlopen_ext);
    if (!orig_readlink || !orig_readlinkat || !orig_open || !orig_open64 || !orig_fopen || !orig_dlopen || !orig_android_dlopen_ext) {
        LOGE("CRITICAL: satu atau lebih orig ptr NULL -> kemungkinan crash saat libc guest panggil symbol tsb");
    }
}

extern "C" __attribute__((visibility("default")))
JNIEXPORT void JNICALL Java_com_example_vspace_VApp_nativeInit(JNIEnv*, jclass) {
    g_self_pidnum = (int)getpid();
    LOGI("nativeInit pid=%d", g_self_pidnum);
    install_hooks();
}

extern "C" __attribute__((visibility("default")))
JNIEXPORT void JNICALL Java_com_example_vspace_VApp_nativeSetGuestPkg(JNIEnv* env, jclass, jstring jpkg) {
    if (!jpkg) { g_guest_pkg[0] = 0; return; }
    const char* s = env->GetStringUTFChars(jpkg, nullptr);
    if (s) {
        strncpy(g_guest_pkg, s, sizeof(g_guest_pkg) - 1);
        g_guest_pkg[sizeof(g_guest_pkg) - 1] = 0;
        env->ReleaseStringUTFChars(jpkg, s);
    }
    LOGI("nativeSetGuestPkg: %s", g_guest_pkg);
}

__attribute__((constructor))
static void on_load() {
    LOGI("vspace_hooks.so loaded");
}