// 时空变速器 native 时钟缩放（GOT hook）
//
// 目标：Cocos2d-x 等原生引擎的游戏逻辑时钟取自 native gettimeofday /
// clock_gettime，Java 层 hook 影响不到。本库在目标进程内按"应用私有库目录"
// 扫描 ELF 的 PLT 重定位表（GOT），把这两个符号重定向到缩放实现。
//
// 缩放算法与 Java 侧 ScaledClock 一致：
//   virtual = virtBase + (real - realBase) * factor
// 倍率变化时以最近 (real, virt) 原地重定基准，时间不回跳。
// 只 hook 应用自身 lib 目录下的 .so，系统库（libart/libjavacore 等）不动，
// 因此与 Java 层 hook 的作用域互不重叠，不会双重缩放。

#include <elf.h>
#include <jni.h>
#include <link.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdint.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <sys/time.h>
#include <time.h>
#include <unistd.h>
#include <android/log.h>

#define TAG "TimeWarpNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

#if defined(__LP64__)
#define ELF_R_SYM(i) ELF64_R_SYM(i)
#else
#define ELF_R_SYM(i) ELF32_R_SYM(i)
#endif

// ---- 缩放时钟 ----

typedef struct {
    int init;
    int64_t real_base, virt_base;
    int64_t last_real, last_virt;
} ScaledClock;

static _Atomic double g_factor = 1.0;
static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
static ScaledClock g_rt;   // CLOCK_REALTIME / gettimeofday
static ScaledClock g_mono; // CLOCK_MONOTONIC 等

static int64_t ts_to_ns(int64_t sec, long nsec) {
    return sec * 1000000000LL + (int64_t) nsec;
}

static void ns_to_ts(int64_t ns, int64_t* sec, long* nsec) {
    *sec = ns / 1000000000LL;
    *nsec = (long) (ns % 1000000000LL);
}

// 调用方需持有 g_lock
static int64_t sc_scale(ScaledClock* s, int64_t real) {
    if (!s->init) {
        s->real_base = real; s->virt_base = real;
        s->last_real = real; s->last_virt = real;
        s->init = 1;
        return real;
    }
    double f = atomic_load_explicit(&g_factor, memory_order_relaxed);
    int64_t v = s->virt_base + (int64_t) ((double) (real - s->real_base) * f);
    s->last_real = real; s->last_virt = v;
    return v;
}

static void sc_set_factor(double f) {
    pthread_mutex_lock(&g_lock);
    if (g_rt.init) { g_rt.virt_base = g_rt.last_virt; g_rt.real_base = g_rt.last_real; }
    if (g_mono.init) { g_mono.virt_base = g_mono.last_virt; g_mono.real_base = g_mono.last_real; }
    atomic_store_explicit(&g_factor, f, memory_order_relaxed);
    pthread_mutex_unlock(&g_lock);
}

static int64_t scale_realtime(int64_t ns) {
    pthread_mutex_lock(&g_lock);
    int64_t v = sc_scale(&g_rt, ns);
    pthread_mutex_unlock(&g_lock);
    return v;
}

static int64_t scale_monotonic(int64_t ns) {
    pthread_mutex_lock(&g_lock);
    int64_t v = sc_scale(&g_mono, ns);
    pthread_mutex_unlock(&g_lock);
    return v;
}

// ---- 替换函数（经 syscall 取真实时间，避免回到被 hook 的 libc 入口） ----

static int my_gettimeofday(struct timeval* tv, struct timezone* tz) {
    int r = (int) syscall(SYS_gettimeofday, tv, tz);
    if (r == 0 && tv != NULL) {
        int64_t ns = ts_to_ns((int64_t) tv->tv_sec, tv->tv_usec * 1000L);
        ns = scale_realtime(ns);
        int64_t sec; long nsec;
        ns_to_ts(ns, &sec, &nsec);
        tv->tv_sec = (time_t) sec;
        tv->tv_usec = (suseconds_t) (nsec / 1000L);
    }
    return r;
}

static int my_clock_gettime(clockid_t clk, struct timespec* ts) {
    int r = (int) syscall(SYS_clock_gettime, clk, ts);
    if (r == 0 && ts != NULL) {
        int64_t ns = ts_to_ns((int64_t) ts->tv_sec, ts->tv_nsec);
        if (clk == CLOCK_MONOTONIC || clk == CLOCK_MONOTONIC_COARSE
                || clk == CLOCK_MONOTONIC_RAW || clk == CLOCK_BOOTTIME) {
            ns = scale_monotonic(ns);
        } else {
            ns = scale_realtime(ns);
        }
        int64_t sec; long nsec;
        ns_to_ts(ns, &sec, &nsec);
        ts->tv_sec = (time_t) sec;
        ts->tv_nsec = nsec;
    }
    return r;
}

// ---- GOT hook ----

static char g_libdir[256];

// 该模块是否需要 hook：应用私有库目录下的库，或名字含 cocos 的引擎库
static int should_hook_module(const char* path) {
    if (path == NULL || path[0] == '\0') return 0;
    if (g_libdir[0] != '\0' && strncmp(path, g_libdir, strlen(g_libdir)) == 0) return 1;
    return strstr(path, "cocos") != NULL;
}

static int patch_slot(void** slot, void* replacement, const char* sym, const char* mod) {
    if (*slot == replacement) return 0; // 已打过
    uintptr_t page = (uintptr_t) slot & ~(uintptr_t) (sysconf(_SC_PAGESIZE) - 1);
    if (mprotect((void*) page, (size_t) sysconf(_SC_PAGESIZE),
                 PROT_READ | PROT_WRITE) != 0) return 0;
    *slot = replacement;
    mprotect((void*) page, (size_t) sysconf(_SC_PAGESIZE), PROT_READ);
    LOGI("hook %s in %s", sym, mod);
    return 1;
}

static int ph_callback(struct dl_phdr_info* info, size_t size, void* data) {
    if (!should_hook_module(info->dlpi_name)) return 0;
    int patched = 0;
    for (int i = 0; i < info->dlpi_phnum; i++) {
        const ElfW(Phdr)* ph = &info->dlpi_phdr[i];
        if (ph->p_type != PT_DYNAMIC) continue;
        ElfW(Dyn)* dyn = (ElfW(Dyn)*) (info->dlpi_addr + ph->p_vaddr);
        ElfW(Addr) jmprel = 0, symtab = 0, strtab = 0;
        ElfW(Xword) pltrelsz = 0, pltrel = 0, syment = 0;
        for (ElfW(Dyn)* d = dyn; d->d_tag != DT_NULL; d++) {
            switch (d->d_tag) {
                case DT_JMPREL:   jmprel   = d->d_un.d_ptr; break;
                case DT_PLTRELSZ: pltrelsz = d->d_un.d_val; break;
                case DT_PLTREL:   pltrel   = d->d_un.d_val; break;
                case DT_SYMTAB:   symtab   = d->d_un.d_ptr; break;
                case DT_STRTAB:   strtab   = d->d_un.d_ptr; break;
                case DT_SYMENT:   syment   = d->d_un.d_val; break;
            }
        }
        if (!jmprel || !pltrelsz || !symtab || !strtab || !syment) continue;
        size_t entsz = (pltrel == DT_RELA) ? sizeof(ElfW(Rela)) : sizeof(ElfW(Rel));
        size_t n = pltrelsz / entsz;
        for (size_t j = 0; j < n; j++) {
            ElfW(Addr) r_offset;
            ElfW(Xword) r_info;
            if (pltrel == DT_RELA) {
                ElfW(Rela)* rel = (ElfW(Rela)*) (info->dlpi_addr + jmprel) + j;
                r_offset = rel->r_offset;
                r_info = rel->r_info;
            } else {
                ElfW(Rel)* rel = (ElfW(Rel)*) (info->dlpi_addr + jmprel) + j;
                r_offset = rel->r_offset;
                r_info = rel->r_info;
            }
            ElfW(Sym)* sym = (ElfW(Sym)*) (info->dlpi_addr + symtab) + ELF_R_SYM(r_info);
            const char* sname = (const char*) (info->dlpi_addr + strtab) + sym->st_name;
            void** slot = (void**) (info->dlpi_addr + r_offset);
            if (strcmp(sname, "clock_gettime") == 0) {
                patched += patch_slot(slot, (void*) my_clock_gettime, sname, info->dlpi_name);
            } else if (strcmp(sname, "gettimeofday") == 0) {
                patched += patch_slot(slot, (void*) my_gettimeofday, sname, info->dlpi_name);
            }
        }
    }
    if (patched > 0) LOGI("%s: %d slots patched", info->dlpi_name, patched);
    *(int*) data += patched;
    return 0;
}

// ---- JNI ----

JNIEXPORT jint JNICALL Java_com_timewarp_speed_MainHook_nativeHook(
        JNIEnv* env, jclass clazz, jstring libdir) {
    if (libdir != NULL) {
        const char* s = (*env)->GetStringUTFChars(env, libdir, NULL);
        if (s != NULL) {
            strncpy(g_libdir, s, sizeof(g_libdir) - 1);
            g_libdir[sizeof(g_libdir) - 1] = '\0';
            (*env)->ReleaseStringUTFChars(env, libdir, s);
        }
    }
    int patched = 0;
    dl_iterate_phdr(ph_callback, &patched);
    return patched;
}

JNIEXPORT void JNICALL Java_com_timewarp_speed_MainHook_nativeSetFactor(
        JNIEnv* env, jclass clazz, jdouble f) {
    if (f <= 0) f = 1.0;
    sc_set_factor(f);
}
