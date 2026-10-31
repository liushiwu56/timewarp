package com.timewarp.speed;

import android.content.SharedPreferences;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * 时空变速器（libxposed API 102）
 *
 * 在被勾选应用的进程内 hook 以下时间源，把"时间流逝速度"乘以倍率：
 *   系统时间：System.currentTimeMillis / System.nanoTime
 *   系统时钟：SystemClock.uptimeMillis / elapsedRealtime / elapsedRealtimeNanos
 *   帧时间  ：Choreographer.getFrameTimeNanos
 *
 * 倍率来自控制 app 经 XposedService 写入的远程 SharedPreferences
 * （LSPosed 守护进程存储，组名 "speed"），变更实时推送到本进程，
 * 另有低频轮询兜底，改配置即时生效。
 *
 * 缩放算法：virtual = virtBase + (real - realBase) * factor
 * 倍率变化时以最近一次 (real, virt) 为基准重设，保证时间不回跳。
 */
public class MainHook extends XposedModule {

    private static final String TAG = "TimeWarp";
    private static final String SELF_PKG = "com.timewarp.speed";

    /** 单调缩放时钟：factor 变化时在原地重定基准，避免时间跳变 */
    static final class ScaledClock {
        private double factor = 1.0;
        private long realBase, virtBase;
        private long lastReal, lastVirt;
        private boolean init;

        synchronized long scale(long real) {
            if (!init) {
                realBase = real; virtBase = real;
                lastReal = real; lastVirt = real;
                init = true;
                return real;
            }
            long v = virtBase + (long) ((real - realBase) * factor);
            lastReal = real; lastVirt = v;
            return v;
        }

        synchronized void setFactor(double f) {
            if (init) {
                virtBase = lastVirt;
                realBase = lastReal;
            }
            factor = f;
        }
    }

    private static final ScaledClock C_MILLIS = new ScaledClock();
    private static final ScaledClock C_NANO = new ScaledClock();
    private static final ScaledClock C_UPTIME = new ScaledClock();
    private static final ScaledClock C_ELAPSED = new ScaledClock();
    private static final ScaledClock C_ELAPSED_NANO = new ScaledClock();
    private static final ScaledClock C_FRAME = new ScaledClock();

    private static volatile double sFactor = 1.0;
    private static XposedModule sMod;
    private static volatile boolean sCfgLogged;
    private static volatile boolean sNativeTried;
    private static volatile boolean sNativeOk;
    private static volatile int sNativePatched;
    /** nativeHook 已尝试次数，超过上限后不再扫描（节省资源） */
    private static volatile int sNativeScanCount;
    private static final int NATIVE_SCAN_MAX = 30; // 30 * 2s = 60s
    /** 远程配置（LSPosed 守护进程存储），控制端与本进程共享 */
    private static volatile SharedPreferences sPrefs;
    /** 悬浮入口（每个目标进程一份） */
    private static volatile FloatEntry sFloat;
    /** 会话级加速开关：启动时从控制端 en_<pkg> 读取默认值，
     *  游戏内悬浮球切换只改这个变量，不写回控制端预设 */
    private static volatile boolean sSessionEn;
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
        // 启动时从控制端 en_<pkg> 读取默认开关值，作为本进程会话开关的初始状态
        if (sPrefs != null) {
            sSessionEn = sPrefs.getBoolean("en_" + pkg, false);
            android.util.Log.i("TimeWarp", "session en init from prefs: en_" + pkg + "=" + sSessionEn);
        }
        injectEntry(pkg);
    }

    /** 悬浮球调用：切换本进程会话级加速开关（不写回控制端预设） */
    public static void toggleSessionEn() {
        sSessionEn = !sSessionEn;
        android.util.Log.i("TimeWarp", "toggleSessionEn -> " + sSessionEn);
        // 立即重新应用倍率
        SharedPreferences prefs = sPrefs;
        String pkg = sPkg;
        if (prefs != null && pkg != null) {
            boolean master = prefs.getBoolean("master", true);
            double f = (master && sSessionEn) ? prefs.getFloat("f_" + pkg, 2f) : 1.0;
            applyFactor(f);
        }
        // 通知悬浮球刷新外观
        FloatEntry fe = sFloat;
        if (fe != null) fe.syncUi();
    }

    /** 悬浮球读取当前会话级加速开关状态 */
    public static boolean getSessionEn() {
        return sSessionEn;
    }

    private void installHooks() throws Exception {
        hookLong(System.class.getMethod("currentTimeMillis"), C_MILLIS);
        hookLong(System.class.getMethod("nanoTime"), C_NANO);
        hookLong(android.os.SystemClock.class.getMethod("uptimeMillis"), C_UPTIME);
        hookLong(android.os.SystemClock.class.getMethod("elapsedRealtime"), C_ELAPSED);
        hookLong(android.os.SystemClock.class.getMethod("elapsedRealtimeNanos"), C_ELAPSED_NANO);
        try {
            hookLong(android.view.Choreographer.class.getMethod("getFrameTimeNanos"), C_FRAME);
        } catch (Throwable t) {
            android.util.Log.w("TimeWarp", "Choreographer hook unavailable: " + t);
        }
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
        C_NANO.setFactor(f);
        C_UPTIME.setFactor(f);
        C_ELAPSED.setFactor(f);
        C_ELAPSED_NANO.setFactor(f);
        C_FRAME.setFactor(f);
        if (sNativeOk) {
            try {
                nativeSetFactor(f);
            } catch (Throwable ignore) {
            }
        }
    }

    /** 防止远程 prefs / 监听器被 GC 的强引用 */
    private static final java.util.List<Object> sKeepAlive = new java.util.ArrayList<>();

    /** 远程配置：en_<pkg> 开关 + f_<pkg> 倍率 + master 总开关。
     *  数据由 LSPosed 守护进程存储并与控制 app 写入端实时同步：
     *  变更监听器即时生效，低频轮询兜底。 */
    private void startConfigWatcher(String pkg, String libDir) {
        final SharedPreferences prefs = sPrefs;
        if (prefs == null) return;
        SharedPreferences.OnSharedPreferenceChangeListener l =
                (p, key) -> readAndApply(p, pkg);
        prefs.registerOnSharedPreferenceChangeListener(l);
        synchronized (sKeepAlive) {
            sKeepAlive.add(prefs);
            sKeepAlive.add(l);
        }
        readAndApply(prefs, pkg);
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    readAndApply(prefs, pkg);
                } catch (Throwable ignore) {
                }
                tryNative(libDir);
                try {
                    Thread.sleep(2000);
                } catch (Throwable ignore) {
                }
            }
        }, "timewarp-cfg");
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
        if (sNativeScanCount >= NATIVE_SCAN_MAX) return;
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

    private void readAndApply(SharedPreferences prefs, String pkg) {
        boolean master = prefs.getBoolean("master", true);
        // 用会话级开关 sSessionEn，不再从 prefs 读 en_<pkg>（控制端的 en_<pkg> 仅作默认值）
        boolean en = master && sSessionEn;
        double f = en ? prefs.getFloat("f_" + pkg, 2f) : 1.0;
        if (!sCfgLogged) {
            sCfgLogged = true;
            android.util.Log.i("TimeWarp", "cfg: " + pkg + " master=" + master
                    + " sessionEn=" + sSessionEn + " f=" + f);
        }
        applyFactor(f);
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
