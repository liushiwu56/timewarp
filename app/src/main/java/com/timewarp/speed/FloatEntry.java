package com.timewarp.speed;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Path;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;

/**
 * 悬浮入口：注入到目标应用进程内。
 *
 * 视觉：磨砂玻璃圆形（半透明底 + 顶部高光 + 细白描边 + elevation 阴影）
 *
 * 功能：
 *   - 点击悬浮球：切换本进程会话级加速开关（不影响控制端预设）
 *   - 拖动悬浮球：移动位置，可贴到屏幕四边
 *   - 球上显示当前倍率（加速开启时）
 *   - 总开关 master 关闭时，球显示"关"且加速不生效
 *
 * 注意：UI 刷新完全由 toggleSessionEn() 调用 syncUi() 驱动，
 * 不再注册 SharedPreferences 监听器——避免与 cfg 轮询线程
 * 同时触发 syncUi() 造成 View 重建竞态。
 */
public class FloatEntry {

    /** 项目蓝色主色调 */
    private static final int BLUE_ACCENT = 0xFF4A86E8;

    private Context appCtx;
    private final String pkg;
    private final SharedPreferences prefs;

    private FrameLayout root;
    private ImageView ball;
    private int ballSize;
    private int touchSlop;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    public FloatEntry(String pkg, SharedPreferences prefs) {
        this.pkg = pkg;
        this.prefs = prefs;
        // 不注册 SharedPreferences 监听器：
        //   toggleSessionEn() 直接调 fe.syncUi()，已覆盖所有 UI 刷新场景
        //   监听 prefs 会导致 cfg 轮询/控制端写入时意外重建 background → 短暂消失
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
            // Android 12+ 真磨砂：让系统对悬浮窗背后的游戏画面做高斯模糊
            // root 全屏但只有球体区域有不透明背景，效果 = 球体背后的游戏画面被模糊
            if (Build.VERSION.SDK_INT >= 31) {
                try {
                    lp.getClass().getMethod("setBlurBehindRadius", int.class).invoke(lp, dp(30));
                } catch (Throwable ignore) {
                    try {
                        java.lang.reflect.Field f = lp.getClass().getField("blurBehindRadius");
                        f.setInt(lp, dp(30));
                    } catch (Throwable ignore2) {}
                }
            }
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
            syncUi();
        }
    }

    /** 根据控制端 floatball 开关设置悬浮球可见性 */
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

    /** 确保在主线程执行 View 操作 */
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

    // ---- 悬浮球：磨砂玻璃圆形 ----

    /**
     * 用 Canvas 直接画图标（绕开 Resources id 映射问题——
     * createPackageContext 在部分游戏进程里会拿到错误的 id 空间，
     * 导致红/绿占位方块）。
     *
     * 开启态：蓝色闪电 ⚡
     * 关闭态：深灰暂停符 ⏸
     */
    private Bitmap makeBallIcon(boolean on, int size) {
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint();
        p.setAntiAlias(true);
        p.setDither(true);

        if (on) {
            // 闪电：蓝色填充
            p.setColor(BLUE_ACCENT);
            Path bolt = new Path();
            // 按 24dp 图标比例缩放到 size
            float s = size / 24f;
            bolt.moveTo(s * 13, s * 2);
            bolt.lineTo(s * 3, s * 14);
            bolt.lineTo(s * 10, s * 14);
            bolt.lineTo(s * 8, s * 22);
            bolt.lineTo(s * 21, s * 9);
            bolt.lineTo(s * 14, s * 9);
            bolt.close();
            c.drawPath(bolt, p);
        } else {
            // 暂停符：两个深灰竖条
            p.setColor(0xFF666666);
            float s = size / 24f;
            // 左竖条：x=6..10, y=4..20
            c.drawRect(s * 6, s * 4, s * 10, s * 20, p);
            // 右竖条：x=14..18, y=4..20
            c.drawRect(s * 14, s * 4, s * 18, s * 20, p);
        }
        return b;
    }

    private void buildBall() {
        ballSize = dp(40);
        touchSlop = ViewConfiguration.get(appCtx).getScaledTouchSlop();

        ball = new ImageView(appCtx);
        ball.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        // 柔和外阴影
        ball.setElevation(dp(8));
        if (Build.VERSION.SDK_INT >= 31) {
            ball.setClipToOutline(false);
        }
        ball.setBackground(buildGlassBackground(false));
        // 用 Canvas 直接画图标——不依赖 Resources，任何进程都能正常显示
        ball.setImageBitmap(makeBallIcon(false, ballSize - dp(12)));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ballSize, ballSize);
        lp.gravity = Gravity.TOP | Gravity.START;

        // 默认位置：右侧偏上
        int screenW = appCtx.getResources().getDisplayMetrics().widthPixels;
        int defaultL = screenW - ballSize - dp(16);
        int defaultT = dp(120);
        // 从游戏本地沙箱读取位置——每个应用天然独立，无需远程 prefs
        android.content.SharedPreferences localPos = appCtx.getSharedPreferences(
                "timewarp_float_pos", android.content.Context.MODE_PRIVATE);
        int savedL = localPos.getInt("posL", -1);
        int savedT = localPos.getInt("posT", -1);
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

    /**
     * 磨砂玻璃背景（配合 Android 12+ blurBehindRadius 真磨砂）：
     *   - 半透明白色叠层（40-50%），让背后的高斯模糊能透出来
     *   - 纵向渐变（上亮下暗）模拟玻璃表面高光
     *   - 系统模糊 + 半透明叠层 = 真正的磨砂玻璃质感
     */
    private GradientDrawable buildGlassBackground(boolean en) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setOrientation(GradientDrawable.Orientation.TOP_BOTTOM);
        g.setColors(new int[]{
                0x80FFFFFF,   // 50% alpha 纯白（顶部高光）
                0x66F0F0F0    // 40% alpha 浅灰（底部）
        });
        // 细白描边：玻璃边缘反光
        g.setStroke(dp(1), 0x55FFFFFF);
        return g;
    }

    private void updateBallAppearance(boolean en, float f) {
        runOnUi(() -> {
            if (ball == null) return;
            ball.setBackground(buildGlassBackground(en));
            // 用 Canvas 直接画图标（绕开 Resources id 映射）
            ball.setImageBitmap(makeBallIcon(en, ballSize - dp(12)));
        });
    }

    private void toggleEnable() {
        try {
            MainHook.toggleSessionEn();
        } catch (Throwable t) {
            android.util.Log.e("TimeWarp", "FloatEntry toggleEnable failed", t);
        }
    }

    /**
     * 拖动结束后保存悬浮球位置。
     *
     * 存到游戏进程自己的沙箱 SharedPreferences——每个应用天然独立，
     * 立即生效，不依赖 LSPosed 远程 prefs（hook 进程的远程 prefs 只读）。
     */
    private void savePosition(int left, int top) {
        try {
            if (appCtx == null) return;
            android.content.SharedPreferences localPos = appCtx.getSharedPreferences(
                    "timewarp_float_pos", android.content.Context.MODE_PRIVATE);
            localPos.edit()
                    .putInt("posL", left)
                    .putInt("posT", top)
                    .apply();
            android.util.Log.i("TimeWarp", "FloatEntry savePosition: left=" + left + " top=" + top);
        } catch (Throwable t) {
            android.util.Log.e("TimeWarp", "FloatEntry savePosition failed", t);
        }
    }

    /** 由 MainHook.toggleSessionEn() 在 UI 线程直接调用，无竞态 */
    void syncUi() {
        runOnUi(() -> {
            if (root == null) return;
            try {
                // 用启动时缓存值，不再读远程 prefs（配置端改动下次启动才生效）
                boolean master = MainHook.getSessionMaster();
                boolean en = master && MainHook.getSessionEn();
                float f = MainHook.getSessionFactorDefault();
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
