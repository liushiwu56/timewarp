package com.timewarp.speed;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * 悬浮入口：注入到目标应用进程内。
 *
 * 功能：
 *   - 点击悬浮球：切换本进程会话级加速开关（不影响控制端预设）
 *   - 拖动悬浮球：移动位置
 *   - 球上显示当前倍率（加速开启时），倍率在控制端 app 设置
 *   - 总开关 master 关闭时，球显示"关"且加速不生效
 *
 * 控制端 en_<pkg> 仅作为游戏启动时的默认开关值；悬浮球切换只改会话级状态。
 */
public class FloatEntry {

    private Context appCtx;
    private final String pkg;
    private final SharedPreferences prefs;
    private final SharedPreferences.OnSharedPreferenceChangeListener listener;

    private FrameLayout root;
    private TextView ball;
    private int ballSize;
    private int touchSlop;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    public FloatEntry(String pkg, SharedPreferences prefs) {
        this.pkg = pkg;
        this.prefs = prefs;
        this.listener = (p, key) -> {
            // master 和 f_<pkg> 由控制端实时控制；floatball 仅在启动时读取，不实时切换
            if (key == null || "master".equals(key)
                    || ("f_" + pkg).equals(key)) {
                syncUi();
            }
        };
        try {
            prefs.registerOnSharedPreferenceChangeListener(listener);
        } catch (Throwable ignore) {
        }
    }

    public void attach(Activity activity) {
        if (appCtx == null) {
            try {
                appCtx = activity.getApplicationContext();
            } catch (Throwable ignore) {
                appCtx = activity;
            }
        }
        ensureRoot();

        ViewParent parent = root.getParent();
        if (parent != null) {
            try {
                if (parent instanceof WindowManager) {
                    ((WindowManager) parent).removeView(root);
                } else if (parent instanceof ViewGroup) {
                    ((ViewGroup) parent).removeView(root);
                }
            } catch (Throwable ignore) {
            }
        }

        boolean added = false;
        try {
            WindowManager wm = (WindowManager) activity.getSystemService(Context.WINDOW_SERVICE);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                            | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.token = activity.getWindow().getAttributes().token;
            wm.addView(root, lp);
            added = true;
        } catch (Throwable ignore) {
        }

        if (!added) {
            try {
                ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
                decor.addView(root, new FrameLayout.LayoutParams(-1, -1));
                root.bringToFront();
                added = true;
            } catch (Throwable ignore) {
            }
        }

        if (added) {
            root.setVisibility(View.VISIBLE);
            syncVisibility();
        }
        syncUi();
    }

    /** 根据控制端 floatball 开关设置悬浮球可见性（只切 ball，root 始终可见避免窗口重绘问题） */
    void syncVisibility() {
        runOnUi(() -> {
            if (ball == null) return;
            try {
                boolean show = prefs.getBoolean("floatball", true);
                ball.setVisibility(show ? View.VISIBLE : View.GONE);
            } catch (Throwable t) {
                android.util.Log.e("TimeWarp", "FloatEntry syncVisibility failed", t);
            }
        });
    }

    /** 确保在主线程执行 View 操作（prefs 监听器可能在 Binder 线程回调） */
    private void runOnUi(Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            r.run();
        } else {
            uiHandler.post(r);
        }
    }

    private void ensureRoot() {
        if (root != null) return;
        root = new FrameLayout(appCtx);
        root.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));
        buildBall();
    }

    // ---- 悬浮球：点击切换启停，拖动移动 ----

    private void buildBall() {
        ball = new TextView(appCtx);
        ball.setTextColor(Color.WHITE);
        ball.setTextSize(11);
        ball.setGravity(Gravity.CENTER);
        ball.setTypeface(Typeface.DEFAULT_BOLD);
        ball.setElevation(dp(6));
        GradientDrawable defaultBg = new GradientDrawable();
        defaultBg.setShape(GradientDrawable.OVAL);
        defaultBg.setColor(0x999E9E9E);
        defaultBg.setStroke(dp(2), 0x99FFFFFF);
        ball.setBackground(defaultBg);
        ball.setText("关");
        ballSize = dp(48);
        touchSlop = android.view.ViewConfiguration.get(appCtx).getScaledTouchSlop();
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ballSize, ballSize);
        lp.gravity = Gravity.TOP | Gravity.START;
        // 默认位置：右侧偏上
        int screenW = appCtx.getResources().getDisplayMetrics().widthPixels;
        int defaultL = screenW - ballSize - dp(16);
        int defaultT = dp(120);
        // 读取上次保存的位置（按包名独立存储）
        int savedL = prefs.getInt("posL_" + pkg, -1);
        int savedT = prefs.getInt("posT_" + pkg, -1);
        lp.leftMargin = (savedL >= 0) ? savedL : defaultL;
        lp.topMargin = (savedT >= 0) ? savedT : defaultT;
        root.addView(ball, lp);

        ball.setOnTouchListener(new View.OnTouchListener() {
            private float downX, downY;
            private int startL, startT;
            private long downTime;
            private boolean dragging;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) ball.getLayoutParams();
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX();
                        downY = e.getRawY();
                        startL = lp.leftMargin;
                        startT = lp.topMargin;
                        downTime = System.currentTimeMillis();
                        dragging = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX() - downX;
                        float dy = e.getRawY() - downY;
                        if (!dragging) {
                            if (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop) {
                                dragging = true;
                            }
                        }
                        if (dragging) {
                            int rootW = root.getWidth();
                            int rootH = root.getHeight();
                            if (rootW <= 0) rootW = appCtx.getResources().getDisplayMetrics().widthPixels;
                            if (rootH <= 0) rootH = appCtx.getResources().getDisplayMetrics().heightPixels;
                            int maxL = rootW - ballSize;
                            int maxT = rootH - ballSize;
                            if (maxL < 0) maxL = 0;
                            if (maxT < 0) maxT = 0;
                            lp.leftMargin = Math.max(0, Math.min(maxL, startL + (int) dx));
                            lp.topMargin = Math.max(0, Math.min(maxT, startT + (int) dy));
                            ball.setLayoutParams(lp);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (dragging) {
                            // 拖动结束：保存当前位置
                            savePosition(lp.leftMargin, lp.topMargin);
                        } else {
                            long dur = System.currentTimeMillis() - downTime;
                            float totalDx = e.getRawX() - downX;
                            float totalDy = e.getRawY() - downY;
                            float moved = (float) Math.hypot(totalDx, totalDy);
                            if (moved < touchSlop * 2 && dur < 500) {
                                toggleEnable();
                            }
                        }
                        return true;
                }
                return false;
            }
        });
    }

    // ---- 切换加速：改会话级开关，不写回控制端预设 ----

    private void updateBallAppearance(boolean en, float f) {
        if (ball == null) return;
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        if (en) {
            bg.setColor(0x99E0643F);
            ball.setText(String.format(java.util.Locale.US, "%.1fx", f));
        } else {
            bg.setColor(0x999E9E9E);
            ball.setText("关");
        }
        bg.setStroke(dp(2), 0x99FFFFFF);
        ball.setBackground(bg);
    }

    private void toggleEnable() {
        try {
            MainHook.toggleSessionEn();
        } catch (Throwable t) {
            android.util.Log.e("TimeWarp", "FloatEntry toggleEnable failed", t);
        }
    }

    /** 拖动结束后保存悬浮球位置（按包名独立存储） */
    private void savePosition(int left, int top) {
        try {
            Context ctx = appCtx;
            if (ctx == null) return;
            Intent i = new Intent("com.timewarp.speed.ACTION_PUT_CONFIG");
            i.setPackage("com.timewarp.speed");
            i.putExtra("method", "putInt");
            i.putExtra("key", "posL_" + pkg);
            i.putExtra("value", left);
            ctx.sendBroadcast(i);

            Intent i2 = new Intent("com.timewarp.speed.ACTION_PUT_CONFIG");
            i2.setPackage("com.timewarp.speed");
            i2.putExtra("method", "putInt");
            i2.putExtra("key", "posT_" + pkg);
            i2.putExtra("value", top);
            ctx.sendBroadcast(i2);
        } catch (Throwable t) {
            android.util.Log.e("TimeWarp", "FloatEntry savePosition failed", t);
        }
    }

    void syncUi() {
        runOnUi(() -> {
            if (root == null) return;
            try {
                boolean master = prefs.getBoolean("master", true);
                boolean en = master && MainHook.getSessionEn();
                float f = prefs.getFloat("f_" + pkg, 2f);
                updateBallAppearance(en, f);
            } catch (Throwable t) {
                android.util.Log.e("TimeWarp", "FloatEntry syncUi failed", t);
            }
        });
    }

    private int dp(float v) {
        return (int) (v * appCtx.getResources().getDisplayMetrics().density + 0.5f);
    }
}
