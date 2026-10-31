package com.timewarp.speed;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

/**
 * 配置写入接收器：目标进程（被 hook 的游戏）里的远程 prefs 是只读的，
 * 悬浮球通过 sendBroadcast 把要写入的 key/value 发到本 Receiver，
 * 由本进程（控制端 app）用可写的远程 prefs 落盘。
 *
 * 广播是异步的，不会阻塞游戏主线程，避免 ANR。
 *
 * Intent extra:
 *   "method" : "putBoolean" | "putFloat" | "putInt"
 *   "key"    : 配置键名
 *   "value"  : boolean | float | int
 */
public class SpeedReceiver extends BroadcastReceiver {

    static volatile SharedPreferences sWritablePrefs;
    private static boolean sListenerRegistered;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        ensureService();
        SharedPreferences prefs = sWritablePrefs;
        if (prefs == null) {
            android.util.Log.w("TimeWarp", "SpeedReceiver: writable prefs not ready, drop " + intent.getStringExtra("key"));
            return;
        }
        try {
            String method = intent.getStringExtra("method");
            String key = intent.getStringExtra("key");
            if (method == null || key == null) return;
            SharedPreferences.Editor editor = prefs.edit();
            if ("putBoolean".equals(method)) {
                editor.putBoolean(key, intent.getBooleanExtra("value", false));
            } else if ("putFloat".equals(method)) {
                editor.putFloat(key, intent.getFloatExtra("value", 1f));
            } else if ("putInt".equals(method)) {
                editor.putInt(key, intent.getIntExtra("value", 0));
            } else {
                return;
            }
            editor.apply();
            android.util.Log.i("TimeWarp", "SpeedReceiver " + method + " " + key);
        } catch (Throwable t) {
            android.util.Log.e("TimeWarp", "SpeedReceiver onReceive failed", t);
        }
    }

    private static void ensureService() {
        if (sListenerRegistered) return;
        sListenerRegistered = true;
        try {
            XposedServiceHelper.registerListener(new XposedServiceHelper.OnServiceListener() {
                @Override
                public void onServiceBind(XposedService service) {
                    sWritablePrefs = service.getRemotePreferences("speed");
                }

                @Override
                public void onServiceDied(XposedService service) {
                    sWritablePrefs = null;
                }
            });
        } catch (Throwable t) {
            android.util.Log.e("TimeWarp", "SpeedReceiver registerListener failed", t);
        }
    }
}
