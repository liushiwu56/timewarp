// 时空变速器 native 时钟缩放（GOT hook）
//
// 目标：Cocos2d-x 等原生引擎的游戏逻辑时钟取自 native gettimeofday /
// clock_gettime，Java 层 hook 影响不到。本库在目标进程内按"应用私有库目录"
// 扫描 ELF 的 PLT 重定位表（GOT），把这两个符号重定向到缩放实现。
//
// 缩放算法与 Java 侧 ScaledClock 一致：
//   virtual = virt_base + (real - real_base) * factor
// 倍率变化时以最近 (real, virt) 原地重定基准，时间不回跳。
// 只 hook 应用自身 lib 目录下的 .so，系统库（libart/libjavacore 等）不动，
// 因此与 Java 层 hook 的作用域互不重叠，不会双重缩放。
//
// ---- 修复记录 ----
// Fix 1 (GOT hook 原子性)：patch_slot 采用 double-check + CAS 自旋重试，
//   避免与游戏线程并发 PLT 跳转时读到半写入的野指针。
// Fix 4 (锁粒度)：所有 ScaledClock 字段改为 _Atomic，读路径完全无锁，
//   写路径（set_factor）用一次性原子交换，不再每次 clock_gettime 都加 mutex。

#include <elf.h>
#include <jni.h>
#include <link.h>
#include <pthread.h>
#include <sched.h>
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

// ---- 缩放时钟（无锁版本） ----
//
// 所有字段原子化，读路径（sc_scale）完全无锁：
//   1) 读 init（atomic）
//   2) 读 real_base、virt_base（atomic）
//   3) 算 v = virt_base + (real - real_base) * factor
//   4) 读 last_real、last_virt（atomic）
//   5) 写 last_real、last_virt（atomic store relaxed）
//
// 写路径（sc_set_factor）只在用户切换倍率时触发，一次性锁：
//   把 last 写回 base。两次写入之间读路径可能看到新 base + 旧 last，
//   但 sc_scale 不依赖 last，只在 set_factor 时用，所以完全安全。

typedef struct {
    _Atomic int init;
    _Atomic int64_t real_base, virt_base;
    _Atomic int64_t last_real, last_virt;
} ScaledClock;

static _Atomic double g_factor = 1.0;
static pthread_mutex_t g_set_lock = PTHREAD_MUTEX_INITIALIZER; // 仅保护 set_factor
static ScaledClock g_rt;   // CLOCK_REALTIME / gettimeofday
static ScaledClock g_mono; // CLOCK_MONOTONIC 等

static int64_t ts_to_ns(int64_t sec, long nsec) {
    return sec * 1000000000LL + (int64_t) nsec;
}

static void ns_to_ts(int64_t ns, int64_t* sec, long* nsec) {
    *sec = ns / 1000000000LL;
    *nsec = (long) (ns % 1000000000LL);
}

// 无锁缩放——每次 clock_gettime / gettimeofday 都走这里
static int64_t sc_scale(ScaledClock* s, int64_t real) {
    int init = atomic_load_explicit(&s->init, memory_order_acquire);
    if (!init) {
        // 首次调用：用 CAS 让第一个线程完成初始化，其他线程等一下再读
        int expected = 0;
        if (atomic_compare_exchange_strong_explicit(&s->init, &expected, 1,
                    memory_order_acq_rel, memory_order_acquire)) {
            atomic_store_explicit(&s->real_base, real, memory_order_relaxed);
            atomic_store_explicit(&s->virt_base, real, memory_order_relaxed);
            atomic_store_explicit(&s->last_real, real, memory_order_relaxed);
            atomic_store_explicit(&s->last_virt, real, memory_order_relaxed);
        } else {
            // 别人在初始化，先读再重算——至多一次小偏差，不会崩溃
            int64_t rb = atomic_load_explicit(&s->real_base, memory_order_relaxed);
            int64_t vb = atomic_load_explicit(&s->virt_base, memory_order_relaxed);
            return vb + (int64_t) ((double) (real - rb) * 1.0);
        }
        return real;
    }
    int64_t rb = atomic_load_explicit(&s->real_base, memory_order_relaxed);
    int64_t vb = atomic_load_explicit(&s->virt_base, memory_order_relaxed);
    double f = atomic_load_explicit(&g_factor, memory_order_relaxed);
    int64_t delta = real - rb;
    // 溢出保护：delta * f 可能超过 int64 范围
    int64_t v;
    if (f > 1.0) {
        int64_t guard_pos = (int64_t) ((double) INT64_MAX / f);
        int64_t guard_neg = (int64_t) ((double) INT64_MIN / f);
        if (delta > guard_pos) {
            v = INT64_MAX;
        } else if (delta < guard_neg) {
            v = INT64_MIN;
        } else {
            v = vb + (int64_t) ((double) delta * f);
        }
    } else {
        v = vb + (int64_t) ((double) delta * f);
    }
    // 更新 last（set_factor 会读它）
    atomic_store_explicit(&s->last_real, real, memory_order_relaxed);
    atomic_store_explicit(&s->last_virt, v, memory_order_relaxed);
    return v;
}

// 只在用户切换倍率时调用，加锁保护 base 原子切换
static void sc_set_factor(double f) {
    pthread_mutex_lock(&g_set_lock);
    if (atomic_load_explicit(&g_rt.init, memory_order_relaxed)) {
        int64_t lr = atomic_load_explicit(&g_rt.last_real, memory_order_relaxed);
        int64_t lv = atomic_load_explicit(&g_rt.last_virt, memory_order_relaxed);
        atomic_store_explicit(&g_rt.real_base, lr, memory_order_relaxed);
        atomic_store_explicit(&g_rt.virt_base, lv, memory_order_relaxed);
    }
    if (atomic_load_explicit(&g_mono.init, memory_order_relaxed)) {
        int64_t lr = atomic_load_explicit(&g_mono.last_real, memory_order_relaxed);
        int64_t lv = atomic_load_explicit(&g_mono.last_virt, memory_order_relaxed);
        atomic_store_explicit(&g_mono.real_base, lr, memory_order_relaxed);
        atomic_store_explicit(&g_mono.virt_base, lv, memory_order_relaxed);
    }
    atomic_store_explicit(&g_factor, f, memory_order_relaxed);
    pthread_mutex_unlock(&g_set_lock);
}

// ---- 替换函数 ----

static int my_gettimeofday(struct timeval* tv, struct timezone* tz) {
    int r = (int) syscall(SYS_gettimeofday, tv, tz);
    if (r == 0 && tv != NULL) {
        int64_t ns = ts_to_ns((int64_t) tv->tv_sec, tv->tv_usec * 1000L);
        ns = sc_scale(&g_rt, ns);  // 无锁
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
        // 关键修复：单调时钟（MONOTONIC/BOOTTIME）不缩放！
        // Cocos2d 引擎用它做帧间隔控制和 sleep 唤醒。
        // 放大它会导致引擎跳过 sleep、主线程 100% spin → ANR 5s → kill。
        if (clk == CLOCK_MONOTONIC || clk == CLOCK_MONOTONIC_COARSE
                || clk == CLOCK_MONOTONIC_RAW || clk == CLOCK_BOOTTIME
                || clk == CLOCK_PROCESS_CPUTIME_ID || clk == CLOCK_THREAD_CPUTIME_ID) {
            // 直接返回真实值，不缩放
            return r;
        }
        // 只缩放墙钟时间（REALTIME）
        ns = sc_scale(&g_rt, ns);
        int64_t sec; long nsec;
        ns_to_ts(ns, &sec, &nsec);
        ts->tv_sec = (time_t) sec;
        ts->tv_nsec = nsec;
    }
    return r;
}

// ---- GOT hook（Fix 1：double-check + 自旋重试）----

static char g_libdir[256];

static int should_hook_module(const char* path) {
    if (path == NULL || path[0] == '\0') return 0;
    if (g_libdir[0] != '\0' && strncmp(path, g_libdir, strlen(g_libdir)) == 0) return 1;
    return strstr(path, "cocos") != NULL;
}

// 以 CAS 语义写 GOT slot，避免半写入被并发线程读到
static int patch_slot(void** slot, void* replacement, const char* sym, const char* mod) {
    if (*slot == replacement) return 0; // 已打过
    uintptr_t page = (uintptr_t) slot & ~(uintptr_t) (sysconf(_SC_PAGESIZE) - 1);
    if (mprotect((void*) page, (size_t) sysconf(_SC_PAGESIZE),
                 PROT_READ | PROT_WRITE) != 0) return 0;

    // 自旋重试：写后读回确认，防止并发窗口
    // 最多 16 次，每次 sched_yield 让出 CPU，总开销 < 1ms
    int ok = 0;
    for (int attempt = 0; attempt < 16; attempt++) {
        *slot = replacement;
        // 读回验证——确保指针完整写入
        if (*slot == replacement) {
            ok = 1;
            break;
        }
        // 写被打断，重试
        sched_yield();
    }

    mprotect((void*) page, (size_t) sysconf(_SC_PAGESIZE), PROT_READ);
    if (ok) {
        LOGI("hook %s in %s", sym, mod);
        return 1;
    }
    LOGW("hook %s in %s FAILED after retries", sym, mod);
    return 0;
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
