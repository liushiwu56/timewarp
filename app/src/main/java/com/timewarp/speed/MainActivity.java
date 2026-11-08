package com.timewarp.speed;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.AdapterView;
import java.text.Collator;
import java.util.Locale;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

/**
 * 控制界面：勾选要变速的应用并设置倍率。
 * 配置经 libxposed service（XposedProvider 接收 LSPosed 下发的 binder）
 * 写入 LSPosed 守护进程管理的远程 SharedPreferences（组名 "speed"），
 * 模块在目标应用进程内经 getRemotePreferences 实时读取同一份数据。
 * 写本地 shared_prefs/files 对 hooked 进程不可见，故不再使用。
 */
public class MainActivity extends Activity {

    /** 倍率允许范围：0.1x（慢放）~ 100x */
    private static final float MIN_FACTOR = 0.1f;
    private static final float MAX_FACTOR = 100f;

    /** 下拉倍率档位 */
    private static final float[] FACTORS = {0.1f, 0.5f, 1f, 2f, 5f, 10f, 50f, 100f};
    private static final String[] FACTOR_LABELS = {"×0.1", "×0.5", "×1", "×2", "×5", "×10", "×50", "×100"};

    // 配色：取自背景插画（暖阳、粉橙）
    private static final int INK = 0xFF3A2A2A;
    private static final int INK_SUB = 0xFF7A6666;
    private static final int ACCENT = 0xFFE0643F;
    private static final int ACCENT_PINK = 0xFFE0648E;
    private static final int CARD = 0xBFFFFFFF;
    private static final int FIELD = 0xFFFFF3EE;
    private static final int ERR = 0xFFD32F2F;

    /** LSPosed 远程配置（守护进程存储，hooked 进程实时同步）；未连接框架时为 null */
    private static volatile SharedPreferences sp;
    private static boolean sListenerRegistered;

    private AppAdapter adapter;
    private TextView countTv;
    private Switch masterBox;
    private SwipeRefreshLayout refreshLayout;
    private boolean suppress;
    private final List<String[]> apps = new ArrayList<>(); // {label, pkg}
    /** 中文拼音排序器，JDK 自带，零依赖 */
    private final Collator zhCollator = Collator.getInstance(Locale.CHINA);

    private void registerService() {
        if (sListenerRegistered) return;
        sListenerRegistered = true;
        XposedServiceHelper.registerListener(new XposedServiceHelper.OnServiceListener() {
            @Override
            public void onServiceBind(XposedService service) {
                sp = service.getRemotePreferences("speed");
                SpeedReceiver.sWritablePrefs = sp;
                runOnUiThread(() -> refreshUi());
            }

            @Override
            public void onServiceDied(XposedService service) {
                sp = null;
                SpeedReceiver.sWritablePrefs = null;
                runOnUiThread(() -> refreshUi());
            }
        });
    }

    // ---- 配置读写（空安全）----

    private boolean getMaster() {
        SharedPreferences p = sp;
        return p == null || p.getBoolean("master", true);
    }

    private boolean getFloatBall() {
        SharedPreferences p = sp;
        return p == null || p.getBoolean("floatball", true);
    }

    private boolean getEn(String pkg) {
        SharedPreferences p = sp;
        return p != null && p.getBoolean("en_" + pkg, false);
    }

    private float getF(String pkg) {
        SharedPreferences p = sp;
        return p == null ? 2f : p.getFloat("f_" + pkg, 2f);
    }

    private boolean getFav(String pkg) {
        SharedPreferences p = sp;
        return p != null && p.getBoolean("fav_" + pkg, false);
    }

    /** 写配置；未连接 LSPosed 时提示并返回 false（调用方负责还原 UI） */
    private boolean putCfg(SharedPreferences.Editor e) {
        SharedPreferences p = sp;
        if (p == null) {
            toast("LSPosed 服务未连接，配置未保存");
            return false;
        }
        e.apply();
        return true;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                        | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            // 背景是浅色插画：状态栏/导航栏图标用深色
            android.view.WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                int mask = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                c.setSystemBarsAppearance(mask, mask);
            }
        }
        registerService();

        FrameLayout root = new FrameLayout(this);

        // 背景插画（铺满整屏，居中裁切）
        ImageView bg = new ImageView(this);
        bg.setScaleType(ImageView.ScaleType.CENTER_CROP);
        bg.setImageResource(R.drawable.bg_after_school);
        root.addView(bg, new FrameLayout.LayoutParams(-1, -1));

        // 薄纱：提高文字可读性，不遮挡画面
        View veil = new View(this);
        veil.setBackgroundColor(0x1FFFFFFF);
        root.addView(veil, new FrameLayout.LayoutParams(-1, -1));

        // 顶部白色渐隐：压住插画上的手写字，让标题/状态栏更清晰
        View topFade = new View(this);
        topFade.setBackground(new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xF7FFFFFF, 0x00FFFFFF}));
        FrameLayout.LayoutParams fadeLp = new FrameLayout.LayoutParams(-1, dp(170));
        fadeLp.gravity = Gravity.TOP;
        root.addView(topFade, fadeLp);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        final int p = dp(12);
        content.setPadding(p, dp(8), p, dp(8));
        content.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(p, insets.getSystemWindowInsetTop() + dp(8),
                    p, insets.getSystemWindowInsetBottom() + dp(8));
            return insets.consumeSystemWindowInsets();
        });
        root.addView(content, new FrameLayout.LayoutParams(-1, -1));

        // 标题 + 悬浮球开关 + 总开关
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = label("时空变速器", 22, ACCENT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setShadowLayer(dp(4), 0, 0, 0xFFFFFFFF); // 白晕：压在插画上仍清晰
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1));

        final Switch floatBallBox = new Switch(this);
        floatBallBox.setText("悬浮球");
        floatBallBox.setTextColor(INK);
        floatBallBox.setShadowLayer(dp(3), 0, 0, 0xFFFFFFFF);
        tintSwitch(floatBallBox);
        floatBallBox.setChecked(getFloatBall());
        floatBallBox.setOnCheckedChangeListener((v, on) -> {
            if (suppress) return;
            SharedPreferences prefs = sp;
            if (prefs == null || !putCfg(prefs.edit().putBoolean("floatball", on))) {
                suppress = true;
                floatBallBox.setChecked(!on);
                suppress = false;
            }
        });
        head.addView(floatBallBox);

        masterBox = new Switch(this);
        masterBox.setText("总开关");
        masterBox.setTextColor(INK);
        masterBox.setShadowLayer(dp(3), 0, 0, 0xFFFFFFFF);
        tintSwitch(masterBox);
        masterBox.setChecked(getMaster());
        masterBox.setOnCheckedChangeListener((v, on) -> {
            if (suppress) return;
            SharedPreferences prefs = sp;
            if (prefs == null || !putCfg(prefs.edit().putBoolean("master", on))) {
                suppress = true;
                masterBox.setChecked(!on);
                suppress = false;
            }
        });
        head.addView(masterBox);
        content.addView(head);

        // 搜索框（右侧带清空叉号）
        FrameLayout searchWrap = new FrameLayout(this);
        searchWrap.setBackground(card(CARD));
        EditText search = new EditText(this);
        search.setHint("搜索应用名或包名…");
        search.setTextColor(INK);
        search.setHintTextColor(0xFFAA9A9A);
        search.setSingleLine(true);
        search.setTextSize(13);
        search.setBackground(null);
        int spp = dp(12);
        search.setPadding(spp, spp, dp(40), spp);
        searchWrap.addView(search, new FrameLayout.LayoutParams(-1, -2));

        TextView clearBtn = new TextView(this);
        clearBtn.setText("×");
        clearBtn.setTextSize(20);
        clearBtn.setTextColor(0xFFAAAAAA);
        clearBtn.setGravity(Gravity.CENTER);
        clearBtn.setPadding(dp(12), 0, dp(12), 0);
        clearBtn.setVisibility(View.GONE);
        clearBtn.setOnClickListener(v -> search.setText(""));
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(-2, -1);
        clp.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        searchWrap.addView(clearBtn, clp);

        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.topMargin = dp(10);
        content.addView(searchWrap, slp);

        countTv = label("", 11, 0xFF4A3636);
        countTv.setShadowLayer(dp(3), 0, 0, 0xFFFFFFFF);
        countTv.setPadding(dp(2), dp(6), 0, dp(2));
        content.addView(countTv);

        ListView list = new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setBackground(null);
        list.setCacheColorHint(0);

        refreshLayout = new SwipeRefreshLayout(this);
        refreshLayout.setColorSchemeColors(ACCENT);
        refreshLayout.addView(list, new SwipeRefreshLayout.LayoutParams(-1, -1));
        refreshLayout.setOnRefreshListener(() -> {
            // 重新加载应用列表 + 刷新配置显示
            loadApps();
            if (adapter != null) adapter.resort();
            updateCount();
            refreshLayout.setRefreshing(false);
        });
        content.addView(refreshLayout, new LinearLayout.LayoutParams(-1, 0, 1));

        setContentView(root);

        loadApps();
        adapter = new AppAdapter();
        list.setAdapter(adapter);
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {
                adapter.filter(s.toString());
                clearBtn.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE);
            }
            public void afterTextChanged(Editable s) {}
        });
        updateCount();
    }

    /** 服务绑定/断开后刷新界面（读取最新远程配置） */
    private void refreshUi() {
        if (masterBox != null) {
            suppress = true;
            masterBox.setChecked(getMaster());
            suppress = false;
        }
        if (adapter != null) adapter.resort();
        updateCount();
    }

    private void loadApps() {
        apps.clear();
        PackageManager pm = getPackageManager();
        Intent it = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> ris = pm.queryIntentActivities(it, 0);
        for (ResolveInfo ri : ris) {
            String pkg = ri.activityInfo.packageName;
            if (getPackageName().equals(pkg)) continue;
            String label = String.valueOf(ri.loadLabel(pm));
            apps.add(new String[]{label, pkg});
        }
        Collections.sort(apps, (a, b) -> zhCollator.compare(a[0], b[0]));
    }

    private void updateCount() {
        if (countTv == null) return;
        int enabled = 0;
        for (String[] a : apps) if (getEn(a[1])) enabled++;
        countTv.setText("共 " + apps.size() + " 个应用，已启用变速 " + enabled + " 个");
    }

    private class AppAdapter extends BaseAdapter {
        final List<Integer> show = new ArrayList<>();
        private String query = "";

        AppAdapter() { filter(""); }

        void filter(String q) {
            query = q;
            show.clear();
            String ql = q.toLowerCase();
            for (int i = 0; i < apps.size(); i++) {
                if (q.isEmpty()
                        || apps.get(i)[0].toLowerCase().contains(ql)
                        || apps.get(i)[1].toLowerCase().contains(ql)) show.add(i);
            }
            // 排序优先级：收藏组(按名称) | 未收藏组(启用变速优先 > 按名称)
            Collections.sort(show, (i, j) -> {
                String pi = apps.get(i)[1];
                String pj = apps.get(j)[1];
                boolean fi = getFav(pi);
                boolean fj = getFav(pj);
                if (fi != fj) return fi ? -1 : 1;          // 收藏组整体置顶
                if (fi) {
                    // 都收藏：按名称
                    return zhCollator.compare(apps.get(i)[0], apps.get(j)[0]);
                }
                // 都未收藏：启用变速优先 → 名称
                boolean ei = getEn(pi);
                boolean ej = getEn(pj);
                if (ei != ej) return ei ? -1 : 1;
                return zhCollator.compare(apps.get(i)[0], apps.get(j)[0]);
            });
            notifyDataSetChanged();
        }

        /** 开关状态变化后重新排序（保持当前搜索词） */
        void resort() { filter(query); }

        @Override public int getCount() { return show.size(); }
        @Override public Object getItem(int p) { return apps.get(show.get(p)); }
        @Override public long getItemId(int p) { return p; }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            String[] app = apps.get(show.get(pos));
            String pkg = app[1];

            FrameLayout wrap = new FrameLayout(MainActivity.this);
            wrap.setPadding(0, dp(3), 0, dp(3));

            LinearLayout row = new LinearLayout(MainActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            int rp = dp(10);
            row.setPadding(dp(8), rp, dp(8), rp);
            row.setBackground(card(CARD));
            wrap.addView(row, new FrameLayout.LayoutParams(-1, -2));

            ImageView icon = new ImageView(MainActivity.this);
            try {
                Drawable d = getPackageManager().getApplicationIcon(pkg);
                icon.setImageDrawable(d);
            } catch (Throwable ignore) {}
            int is = dp(42);
            icon.setPadding(dp(1), dp(1), dp(1), dp(1));
            icon.setOnClickListener(v -> launchApp(pkg, app[0]));
            row.addView(icon, new LinearLayout.LayoutParams(is, is));

            LinearLayout texts = new LinearLayout(MainActivity.this);
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.setPadding(dp(10), 0, dp(4), 0);
            texts.addView(label(app[0], 14, INK));
            texts.addView(label(pkg, 10, INK_SUB));
            row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));

            float curF = getF(pkg);
            int selIdx = 0;
            for (int i = 0; i < FACTORS.length; i++) {
                if (Math.abs(FACTORS[i] - curF) < 0.001f) { selIdx = i; break; }
            }
            final int finalSelIdx = selIdx;
            // 换成 TextView + PopupWindow，彻底绕开 Spinner 默认 popup 的锚点偏移问题
            final TextView factorBtn = new TextView(MainActivity.this);
            factorBtn.setText(FACTOR_LABELS[selIdx]);
            factorBtn.setTextSize(12);
            factorBtn.setTextColor(getEn(pkg) ? INK : 0xFFB0A0A0);
            factorBtn.setGravity(Gravity.CENTER);
            factorBtn.setBackground(factorBg());
            factorBtn.setPadding(dp(4), 0, dp(28), 0);
            factorBtn.setClickable(true);
            factorBtn.setEnabled(getEn(pkg));

            // 存每个 popup 行的引用，用于刷新选中态
            final java.util.ArrayList<LinearLayout> popupRows = new java.util.ArrayList<>();
            // 前向引用 holder：pw 在循环之后才构造，但 itemRow.onClick 里需要 dismiss
            final android.widget.PopupWindow[] pwHolder = new android.widget.PopupWindow[1];

            // 构建 PopupWindow 下拉内容
            LinearLayout popupContent = new LinearLayout(MainActivity.this);
            popupContent.setOrientation(LinearLayout.VERTICAL);
            popupContent.setBackground(spinnerPopupBg());
            popupContent.setPadding(0, dp(4), 0, dp(4));

            for (int i = 0; i < FACTOR_LABELS.length; i++) {
                final int idx = i;
                LinearLayout itemRow = new LinearLayout(MainActivity.this);
                itemRow.setOrientation(LinearLayout.HORIZONTAL);
                itemRow.setGravity(Gravity.CENTER_VERTICAL);
                itemRow.setPadding(dp(10), dp(9), dp(10), dp(9));
                itemRow.setBackgroundColor(android.graphics.Color.TRANSPARENT);
                popupRows.add(itemRow);

                TextView tv = new TextView(MainActivity.this);
                tv.setText(FACTOR_LABELS[i]);
                tv.setTextSize(12);
                itemRow.addView(tv, new LinearLayout.LayoutParams(0, -2, 1));

                final TextView check = new TextView(MainActivity.this);
                check.setText("✓");
                check.setTextSize(12);
                check.setTextColor(ACCENT);
                itemRow.addView(check);

                itemRow.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        float f = FACTORS[idx];
                        if (Math.abs(f - getF(pkg)) > 0.001f) {
                            SharedPreferences prefs = sp;
                            if (prefs != null && putCfg(prefs.edit().putFloat("f_" + pkg, f))) {
                                factorBtn.setText(FACTOR_LABELS[idx]);
                            }
                        }
                        // 立即关闭 popup
                        if (pwHolder[0] != null) pwHolder[0].dismiss();
                    }
                });
                popupContent.addView(itemRow);
            }

            /** 根据 factorBtn 当前显示的值，刷新 popup 所有行的选中态 */
            final Runnable refreshPopupSel = () -> {
                String cur = factorBtn.getText().toString();
                for (int i = 0; i < popupRows.size(); i++) {
                    LinearLayout popupRow = popupRows.get(i);
                    TextView tv = (TextView) popupRow.getChildAt(0);
                    TextView check = (TextView) popupRow.getChildAt(1);
                    boolean sel = FACTOR_LABELS[i].equals(cur);
                    if (sel) { tv.setTextColor(ACCENT); tv.setTypeface(null, Typeface.BOLD); check.setVisibility(View.VISIBLE); }
                    else { tv.setTextColor(INK); tv.setTypeface(null, Typeface.NORMAL); check.setVisibility(View.GONE); }
                }
            };

            final android.widget.PopupWindow pw = new android.widget.PopupWindow(
                    popupContent, dp(72), android.view.ViewGroup.LayoutParams.WRAP_CONTENT, true);
            pwHolder[0] = pw;
            pw.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(
                    android.graphics.Color.TRANSPARENT));
            pw.setOutsideTouchable(true);
            pw.setTouchable(true);

            factorBtn.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (pw.isShowing()) return;
                    refreshPopupSel.run();                    // ← 每次打开前刷选中态
                    pw.showAsDropDown(v, 0, 0);
                }
            });

            LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(dp(72), dp(28));
            flp.setMargins(dp(2), 0, dp(2), 0);
            row.addView(factorBtn, flp);

            Switch en = new Switch(MainActivity.this);
            tintSwitch(en);
            en.setChecked(getEn(pkg));
            en.setOnCheckedChangeListener((v, on) -> {
                if (suppress) return;
                SharedPreferences prefs = sp;
                if (prefs == null || !putCfg(prefs.edit().putBoolean("en_" + pkg, on))) {
                    suppress = true;
                    en.setChecked(!on);
                    suppress = false;
                    return;
                }
                factorBtn.setEnabled(on);
                updateCount();
                adapter.resort(); // 已开启的置顶
                if (on) toast("已对 " + app[0] + " 启用变速");
            });
            row.addView(en);

            // 收藏五角星
            TextView star = new TextView(MainActivity.this);
            star.setTextSize(22);
            star.setGravity(Gravity.CENTER);
            star.setPadding(dp(6), 0, dp(2), 0);
            star.setOnClickListener(v -> {
                boolean cur = getFav(pkg);
                SharedPreferences prefs = sp;
                if (prefs == null || !putCfg(prefs.edit().putBoolean("fav_" + pkg, !cur))) {
                    return;
                }
                updateStar(star, !cur);
                adapter.resort(); // 收藏变化时重排
            });
            updateStar(star, getFav(pkg));
            row.addView(star);
            return wrap;
        }
    }

    /** 点击应用图标：直接启动对应应用 */
    private void launchApp(String pkg, String name) {
        try {
            Intent it = getPackageManager().getLaunchIntentForPackage(pkg);
            if (it == null) {
                toast(name + " 没有可启动的界面");
                return;
            }
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(it);
        } catch (Throwable t) {
            toast("无法启动 " + name);
        }
    }

    private TextView label(String text, int sp_, int color) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(sp_);
        t.setTextColor(color);
        return t;
    }

    /** iOS 风格开关：开启蓝色轨道、关闭浅灰轨道，白色圆形滑块 */
    private void tintSwitch(Switch s) {
        int tw = dp(46), th = dp(22);
        GradientDrawable onTrack = new GradientDrawable();
        onTrack.setColor(0xFF4A86E8);
        onTrack.setCornerRadius(dp(11));
        onTrack.setSize(tw, th);
        GradientDrawable offTrack = new GradientDrawable();
        offTrack.setColor(0xFFD0D0D0);
        offTrack.setCornerRadius(dp(11));
        offTrack.setSize(tw, th);
        StateListDrawable track = new StateListDrawable();
        track.addState(new int[]{android.R.attr.state_checked}, onTrack);
        track.addState(new int[]{}, offTrack);
        s.setTrackDrawable(track);

        GradientDrawable thumb = new GradientDrawable();
        thumb.setShape(GradientDrawable.OVAL);
        thumb.setColor(Color.WHITE);
        thumb.setStroke(dp(1), 0xFFE0E0E0);
        thumb.setSize(dp(20), dp(20));
        s.setThumbDrawable(thumb);
        s.setSwitchMinWidth(tw);
    }

    private GradientDrawable card(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(14));
        return g;
    }

    /** 封闭态胶囊：磨砂白 + 细描边 + 右侧 chevron（∨）指示，三态切换 */
    private Drawable factorBg() {
        GradientDrawable normal = new GradientDrawable();
        normal.setColor(CARD);
        normal.setStroke(dp(0.8f), 0x33E0643F);
        normal.setCornerRadius(dp(10));

        GradientDrawable disabled = new GradientDrawable();
        disabled.setColor(0x80FFFFFF);
        disabled.setStroke(dp(0.6f), 0x22C0B0B0);
        disabled.setCornerRadius(dp(10));

        GradientDrawable pressed = new GradientDrawable();
        pressed.setColor(0xDFFFFFFF);
        pressed.setStroke(dp(1), ACCENT);
        pressed.setCornerRadius(dp(10));

        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed}, pressed);
        states.addState(new int[]{-android.R.attr.state_enabled}, disabled);
        states.addState(new int[]{}, normal);

        // chevron（∨ 形）：全正坐标，在 8×8 layer 内完美居中
        int w = dp(8);
        int h = dp(8);
        float x1 = dp(1.5f);   // 左上端点 x
        float y1 = dp(2.5f);   // 左上端点 y
        float x2 = dp(4);      // 底部尖 x（居中）
        float y2 = dp(5.5f);   // 底部尖 y
        float x3 = dp(6.5f);   // 右上端点 x
        android.graphics.Path path = new android.graphics.Path();
        path.moveTo(x1, y1);
        path.lineTo(x2, y2);
        path.lineTo(x3, y1);
        // 第二条线其实就是同一折线，画成单线但更优雅的做法是 Stroke 做圆角
        ShapeDrawable arrow = new ShapeDrawable(
                new android.graphics.drawable.shapes.PathShape(path, w, h));
        android.graphics.Paint p = arrow.getPaint();
        p.setStyle(android.graphics.Paint.Style.STROKE);
        p.setStrokeWidth(dp(1.2f));
        p.setStrokeCap(android.graphics.Paint.Cap.ROUND);
        p.setStrokeJoin(android.graphics.Paint.Join.ROUND);
        p.setColor(INK_SUB);

        // 关键：只给箭头单独加 6dp 右边距（公开 API InsetDrawable 100% 生效）
        // 胶囊背景层保持 FILL 整个 spinner → popup 左右完美对齐 spinner
        Drawable arrowWithMargin = new android.graphics.drawable.InsetDrawable(arrow, 0, 0, dp(6), 0);

        LayerDrawable out = new LayerDrawable(new Drawable[]{states, arrowWithMargin});
        out.setLayerGravity(0, Gravity.FILL);                              // 胶囊填满 spinner
        out.setLayerGravity(1, Gravity.END | Gravity.CENTER_VERTICAL);      // 箭头（已带右边距）靠右
        out.setLayerWidth(1, w + dp(6));                                   // 箭头层宽 = 实际箭头 + 右边距
        out.setLayerHeight(1, h);
        return out;
    }

    /** 下拉 popup 窗口：磨砂白 + 12dp 圆角 + 轻阴影描边 */
    private Drawable spinnerPopupBg() {
        GradientDrawable g = new GradientDrawable();
        g.setColor(CARD);
        g.setStroke(dp(0.6f), 0x22000000);
        g.setCornerRadius(dp(12));
        return g;
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    /** 根据收藏状态刷新五角星显示：空心灰色 / 实心橙色 */
    private void updateStar(TextView star, boolean fav) {
        star.setText(fav ? "\u2605" : "\u2606");
        star.setTextColor(fav ? ACCENT : 0xFFC8B8B8);
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
