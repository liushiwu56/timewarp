package com.timewarp.speed;

import android.content.SharedPreferences;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * 时空变速器（libxposed API 102）
 *
 * 设计原则（节省资源 + 职责清晰）：
 *   1. 启动时从控制端配置**只读一次**（en_<pkg>, f_<pkg>, master）作为会话默认值，
 *      之后**不再监听控制端配置变化**——配置端改动只在游戏下次启动时生效。
 *   2. 游戏运行期的加速效果**只受悬浮球开关控制**：悬浮球点击翻转内存变量
 *      sSessionEn → 立即应用缓存倍率，不触发任何远程配置读取。
 *   3. native 引擎 .so 重扫独立线程，限次（NATIVE_SCAN_MAX）后自动退出。
 *
 * 缩放算法：virtual = virtBase + (real - realBase) * factor
 * 倍率变化时以最近一次 (real, virt) 为基准重设，保证时间不回跳。
 */
public class MainHook extends XposedModule {

    private static final String TAG = "TimeWarp";
    private static final String SELF_PKG = "com.timewarp.speed";

    /**
     * 单调缩放时钟：factor 变化时在原地重定基准，避免时间跳变。
     *
     * 关键设计（无锁读路径）：
     *   所有字段 volatile，scale() 被 System.currentTimeMillis/nanoTime
     *   等高频调用时**完全无锁**，解决多线程并发锁竞争导致的阻塞/ANR。
     *   setFactor() 仅在用户切换倍率时调用，保留 synchronized 保护
     *   "读 last → 写 base"的复合操作（低频，不是瓶颈）。
     *
     * 并发安全：
     *   setFactor 先写 base 后写 factor，读路径即使在中间态也只会看到
     *   "新 base + 旧 factor"，算出的值 ≈ lastVirt（时间不回跳）。
     *
     * 溢出保护：
     *   factor > 1.0 时若 real - realBase 接近 Long.MAX_VALUE/factor，
     *   循环重定基准直到安全范围；最终 clamp 兜底。
     */
    static final class ScaledClock {
        private volatile boolean init;
        private final java.util.concurrent.atomic.AtomicBoolean initLatch =
                new java.util.concurrent.atomic.AtomicBoolean();
        private volatile double factor = 1.0;
        private volatile long realBase, virtBase;
        private volatile long lastReal, lastVirt;

        /** 无锁缩放——每秒可能被调用成千上万次 */
        long scale(long real) {
            if (!init) {
                if (initLatch.compareAndSet(false, true)) {
                    realBase = real; virtBase = real;
                    lastReal = real; lastVirt = real;
                    init = true;
                    return real;
                }
                // 自旋等待其他线程初始化完成（极短暂）
                while (!init) { Thread.onSpinWait(); }
                // 别人已经初始化好，继续走无锁路径
            }
            long rb = realBase;
            long vb = virtBase;
            double f = factor;
            long delta = real - rb;
            // 溢出预防：factor > 1.0 时单调时钟的差值持续增长
            if (f > 1.0) {
                long guard = (long) ((double) Long.MAX_VALUE / f * 0.8);
                if (delta > guard || delta < 0) {
                    // 重定基准——把 base 移到最近一次的 (real, virt)
                    long lr = lastReal;
                    long lv = lastVirt;
                    if (lr != real) {
                        realBase = lr;
                        virtBase = lv;
                        delta = real - lr;
                    }
                    // 如果重定后还是超限（极端情况），再重定一轮
                    int spins = 0;
                    while (f > 1.0 && (delta > (long) ((double) Long.MAX_VALUE / f * 0.8) || delta < 0)
                            && spins < 3) {
                        lr = lastReal;
                        lv = lastVirt;
                        if (lr == real) break;
                        realBase = lr;
                        virtBase = lv;
                        delta = real - lr;
                        spins++;
                    }
                }
            }
            // 最终兜底：delta * factor 超 long 范围则 clamp
            long v;
            if (f > 1.0 && delta > 0 && delta > (long) ((double) Long.MAX_VALUE / f)) {
                v = Long.MAX_VALUE;
            } else if (f > 1.0 && delta < 0 && delta < (long) ((double) Long.MIN_VALUE / f)) {
                v = Long.MIN_VALUE;
            } else {
                v = vb + (long) ((double) delta * f);
            }
            lastReal = real;
            lastVirt = v;
            return v;
        }

        /** 只在用户切换倍率时调用，保留 synchronized 保护"读last→写base" */
        synchronized void setFactor(double f) {
            if (init) {
                virtBase = lastVirt;   // 先写 base
                realBase = lastReal;
            }
            factor = f;                 // 再写 factor
        }
    }

    private static final ScaledClock C_MILLIS = new ScaledClock();
    // C_NANO / C_UPTIME / C_ELAPSED / C_ELAPSED_NANO 已移除：
    // 单调时钟不 hook，让游戏引擎帧间隔控制正常工作

    private static volatile double sFactor = 1.0;
    private static XposedModule sMod;
    private static volatile boolean sNativeTried;
    private static volatile boolean sNativeOk;
    private static volatile int sNativePatched;
    /** nativeHook 已尝试次数，超过上限后不再扫描（节省资源） */
    private static volatile int sNativeScanCount;
    private static final int NATIVE_SCAN_MAX = 20; // 20 * 1s = 20s，扫到 GOT 槽就提前退出
    /** 远程配置（LSPosed 守护进程存储），控制端与本进程共享 */
    private static volatile SharedPreferences sPrefs;
    /** 悬浮入口（每个目标进程一份） */
    private static volatile FloatEntry sFloat;
    /** 会话级加速开关：启动时从控制端 en_<pkg> 读取默认值，
     *  游戏内悬浮球切换只改这个变量，不写回控制端预设 */
    private static volatile boolean sSessionEn;
    /** 启动时从控制端 master 一次性读取的缓存值（悬浮球切换加速时用，不再读 prefs） */
    private static volatile boolean sMaster;
    /** 启动时从控制端 f_<pkg> 一次性读取的缓存值（悬浮球切换加速时用，不再读 prefs） */
    private static volatile float sFactorDefault;
    /** 当前目标包名（用于 readAndApply 读取对应配置） */
    private static volatile String sPkg;
    /** 保存所有 HookHandle 的强引用，防止被 GC 回收导致 hook 失效 */
    private static final java.util.List<Object> sHookHandles = new java.util.ArrayList<>();
    /** 每进程只初始化一次：进程内可能注入额外包上下文（如 MIUI contentcatcher
     *  注入游戏进程），重复安装 hook 会叠加拦截（f 变 f²），多个配置 watcher
     *  也会读写同一组静态时钟互相覆盖 */
    private static final java.util.concurrent.atomic.AtomicBoolean sInstalled =
            new java.util.concurrent.atomic.AtomicBoolean();

    /** twclock.c：GOT hook 应用私有库的 clock_gettime/gettimeofday（Cocos2d 等原生引擎） */
    private static native int nativeHook(String libDir);
    private static native void nativeSetFactor(double f);

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        String pkg = param.getPackageName();
        android.util.Log.i("TimeWarp", "onPackageReady pkg=" + pkg + " first=" + param.isFirstPackage());
        if (pkg == null || SELF_PKG.equals(pkg) || "android".equals(pkg)) return;
        if (!param.isFirstPackage()) return;
        if (!sInstalled.compareAndSet(false, true)) return;
        sMod = this;
        try {
            sPrefs = getRemotePreferences("speed");
        } catch (Throwable t) {
            android.util.Log.e("TimeWarp", "getRemotePreferences failed for " + pkg, t);
            sPrefs = null;
        }
        try {
            installHooks();
            android.util.Log.i("TimeWarp", "time hooks installed in " + pkg);
        } catch (Throwable t) {
            android.util.Log.e("TimeWarp", "installHooks failed in " + pkg, t);
        }
        String libDir = null;
        try {
            android.content.pm.ApplicationInfo ai = param.getApplicationInfo();
            if (ai != null) libDir = ai.nativeLibraryDir;
        } catch (Throwable ignore) {
        }
        sPkg = pkg;
        startConfigWatcher(pkg, libDir);
        injectEntry(pkg);
    }

    /** 悬浮球调用：切换本进程会话级加速开关（只改内存变量，不读远程配置） */
    public static void toggleSessionEn() {
        sSessionEn = !sSessionEn;
        android.util.Log.i("TimeWarp", "toggleSessionEn -> " + sSessionEn);
        // 直接用启动时缓存的 master + f_<pkg>，不再读 prefs
        double f = (sMaster && sSessionEn) ? sFactorDefault : 1.0;
        applyFactor(f);
        // 通知悬浮球刷新外观
        FloatEntry fe = sFloat;
        if (fe != null) fe.syncUi();
    }

    /** 悬浮球读取当前会话级加速开关状态 */
    public static boolean getSessionEn() {
        return sSessionEn;
    }

    /** 悬浮球读取启动时缓存的 master / f_<pkg>（不再读远程配置） */
    public static boolean getSessionMaster() { return sMaster; }
    public static float getSessionFactorDefault() { return sFactorDefault; }

    private void installHooks() throws Exception {
        // 只 hook 墙钟时间：System.currentTimeMillis()
        // 原因：Cocos2d/Unity 等游戏引擎的帧间隔控制、sleep 唤醒、
        // 动画进度都依赖单调时钟（nanoTime/uptimeMillis/elapsedRealtime）。
        // 放大单调时钟会导致引擎跳过 sleep、主线程 100% spin →
        // InputDispatcher 5s ANR → 系统 kill。
        // 墙钟时间（currentTimeMillis）用于：游戏内 Date.now()、
        // 服务器时间戳逻辑、基于"真实时间"的计时——放大它不会影响帧控。
        hookLong(System.class.getMethod("currentTimeMillis"), C_MILLIS);

        // 以下单调时钟全部跳过 hook（让它们返回真实值）：
        //   System.nanoTime
        //   SystemClock.uptimeMillis
        //   SystemClock.elapsedRealtime
        //   SystemClock.elapsedRealtimeNanos
        //   Choreographer.getFrameTimeNanos（之前已移除）
    }

    private void hookLong(Method m, ScaledClock clock) {
        hook(m).intercept(chain -> {
            long real = (Long) chain.proceed();
            return clock.scale(real);
        });
    }

    /** 应用新倍率到所有时钟（无变化则跳过） */
    private static void applyFactor(double f) {
        if (f <= 0) f = 1.0;
        if (f == sFactor) return;
        sFactor = f;
        android.util.Log.i("TimeWarp", "applyFactor -> " + f);
        C_MILLIS.setFactor(f);
        // C_NANO / C_UPTIME / C_ELAPSED / C_ELAPSED_NANO 不再 setFactor
        // （单调时钟不缩放，让游戏引擎帧间隔控制正常工作）
        if (sNativeOk) {
            try {
                nativeSetFactor(f);
            } catch (Throwable ignore) {
            }
        }
    }

    /** 防止远程 prefs 被 GC（监听器已移除，仅留 prefs 自身供 FloatEntry 读取位置/可见性） */
    private static final java.util.List<Object> sKeepAlive = new java.util.ArrayList<>();

    /**
     * 启动时**一次性**读取控制端配置作为会话默认值并应用，之后不再监听 prefs 变化。
     * 仅保留 native 引擎 .so 重扫线程（限次后自动退出，不耗资源）。
     */
    private void startConfigWatcher(String pkg, String libDir) {
        final SharedPreferences prefs = sPrefs;
        if (prefs == null) return;

        // 一次性缓存三个配置值，之后整个会话期不再读取变速相关配置
        sMaster = prefs.getBoolean("master", true);
        sFactorDefault = prefs.getFloat("f_" + pkg, 2f);
        sSessionEn = prefs.getBoolean("en_" + pkg, false);
        synchronized (sKeepAlive) { sKeepAlive.add(prefs); }

        // 立即应用默认配置
        double f = (sMaster && sSessionEn) ? sFactorDefault : 1.0;
        applyFactor(f);
        android.util.Log.i("TimeWarp", "init: master=" + sMaster
                + " en=" + sSessionEn + " f=" + f);

        // native .so 重扫线程：限 NATIVE_SCAN_MAX 次后自动退出
        // 游戏引擎（Cocos2d/Unity）的 .so 可能晚于模块加载 dlopen，需要定时扫 GOT 表
        Thread t = new Thread(() -> {
            while (true) {
                tryNative(libDir);
                synchronized (MainHook.class) {
                    // 扫到 GOT 槽（引擎已 dlopen）或达上限 → 退出线程
                    if (sNativePatched > 0 || sNativeScanCount >= NATIVE_SCAN_MAX) break;
                }
                try { Thread.sleep(1000); } catch (Throwable ignore) {}
            }
            android.util.Log.i("TimeWarp", "native-scan done, patched=" + sNativePatched
                    + " scans=" + sNativeScanCount);
        }, "timewarp-native-scan");
        t.setDaemon(true);
        t.start();
    }

    /** 加载 native 库并周期性重扫（游戏的引擎 .so 可能晚于模块加载） */
    private void tryNative(String libDir) {
        if (!sNativeTried) {
            sNativeTried = true;
            try {
                System.loadLibrary("twclock");
                sNativeOk = true;
                android.util.Log.i("TimeWarp", "native lib loaded");
                if (sFactor != 1.0) {
                    try {
                        nativeSetFactor(sFactor);
                    } catch (Throwable ignore) {
                    }
                }
            } catch (Throwable t) {
                android.util.Log.w("TimeWarp", "native lib unavailable: " + t);
                return;
            }
        }
        if (!sNativeOk || libDir == null) return;
        if (sNativePatched > 0 || sNativeScanCount >= NATIVE_SCAN_MAX) return;
        sNativeScanCount++;
        try {
            int n = nativeHook(libDir);
            if (n > 0) {
                sNativePatched += n;
                android.util.Log.i("TimeWarp", "native clock slots hooked: +" + n
                        + " (total " + sNativePatched + ")");
            }
        } catch (Throwable ignore) {
        }
    }

    /**
     * 在目标 app 内注入悬浮入口（FloatEntry）：点击切换加速启停，拖动移动位置。
     *
     * 用 Application.registerActivityLifecycleCallbacks 拿前台 Activity——
     * 这比 hook Activity.onResume() 更可靠（游戏 Activity 可能重写 onResume
     * 且不调 super，导致 hook 不触发）。
     * 视图挂到 Activity 的 decorView 上，无需悬浮窗权限。
     */
    private void injectEntry(final String pkg) {
        if (sPrefs == null) {
            android.util.Log.e("TimeWarp", "injectEntry skip: sPrefs is null for " + pkg);
            return;
        }
        if (sFloat != null) return;
        sFloat = new FloatEntry(pkg, sPrefs);
        android.util.Log.i("TimeWarp", "injectEntry: FloatEntry created for " + pkg);

        boolean registered = false;
        // 方式一：Application.registerActivityLifecycleCallbacks（最可靠）
        try {
            android.app.Application app = (android.app.Application) Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication").invoke(null);
            if (app != null) {
                app.registerActivityLifecycleCallbacks(new android.app.Application.ActivityLifecycleCallbacks() {
                    @Override public void onActivityCreated(android.app.Activity a, android.os.Bundle b) {}
                    @Override public void onActivityStarted(android.app.Activity a) {}
                    @Override public void onActivityResumed(android.app.Activity a) {
                        try { sFloat.attach(a); } catch (Throwable t) { android.util.Log.e("TimeWarp", "lifecycle attach failed", t); }
                    }
                    @Override public void onActivityPaused(android.app.Activity a) {}
                    @Override public void onActivityStopped(android.app.Activity a) {}
                    @Override public void onActivitySaveInstanceState(android.app.Activity a, android.os.Bundle b) {}
                    @Override public void onActivityDestroyed(android.app.Activity a) {}
                });
                registered = true;
                android.util.Log.i("TimeWarp", "injectEntry: ActivityLifecycleCallbacks registered");
            }
        } catch (Throwable t) {
            android.util.Log.w("TimeWarp", "injectEntry: lifecycle callbacks failed", t);
        }

        // 方式二：hook Instrumentation.callActivityOnResume（兜底，覆盖所有 Activity）
        if (!registered) {
            try {
                Object h = hook(android.app.Instrumentation.class.getMethod("callActivityOnResume", android.app.Activity.class))
                        .intercept(chain -> {
                            chain.proceed();
                            try {
                                android.app.Activity act = (android.app.Activity) chain.getArgs().get(0);
                                sFloat.attach(act);
                            } catch (Throwable ignore) {}
                            return null;
                        });
                synchronized (sHookHandles) { sHookHandles.add(h); }
                android.util.Log.i("TimeWarp", "injectEntry: Instrumentation hook installed");
            } catch (Throwable t) {
                android.util.Log.e("TimeWarp", "injectEntry: Instrumentation hook failed", t);
            }
        }
    }
}
