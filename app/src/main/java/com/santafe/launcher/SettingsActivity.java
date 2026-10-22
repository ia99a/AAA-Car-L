package com.santafe.launcher;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * إعدادات المشغّل: لغة الواجهة، تطبيق الخرائط لزر «موقعي»، إظهار البار السفلي أو إخفاؤه، حجب الإعلانات، إعدادات أندرويد،
 * وإضافة أدوات شريط السائق أو إزالتها (الترتيب في الشريط يتبع ترتيب القائمة).
 */
public class SettingsActivity extends Activity {

    private final List<String> chosen = new ArrayList<>();
    private int toneIndex = 1; // زر السماع: أول ضغطة تُسمعك تنبيه الأذان ثم يتنقل بين الثلاث
    private android.media.MediaPlayer player;

    /** يُسمع النغمة كما ستُسمع في الإشعار */
    private void playTone(int res) {
        if (player != null) { player.release(); player = null; }
        android.media.MediaPlayer mp = android.media.MediaPlayer.create(this, res);
        if (mp == null) return;
        player = mp;
        mp.setOnCompletionListener(done -> { done.release(); if (player == done) player = null; });
        mp.start();
    }

    /** يُسمع التنبيه كما سيُسمع فعلًا (مثال بصلاة الظهر) */
    private void preview(int style, int type) {
        if (Tones.isTone(style)) { playTone(Tones.sound(style, type)); return; }
        if (style == Tones.SILENT) { Toast.makeText(this, R.string.tone_silent_desc, Toast.LENGTH_SHORT).show(); return; }
        Runnable speak = () -> Tones.say(this, Tones.spoken(this, type, 2), () -> {
            if (isFinishing()) return;
            Toast.makeText(this, R.string.tone_voice_missing, Toast.LENGTH_LONG).show();
            if (style == Tones.VOICE) playTone(Tones.sound(style, type));
        }, null);
        if (style == Tones.BEEP_VOICE) {
            playTone(Tones.sound(style, 0));
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> { if (!isFinishing()) speak.run(); }, 1300);
        } else speak.run();
    }

    @Override
    protected void attachBaseContext(Context base) { super.attachBaseContext(Lang.wrap(base)); }

    /** تطبيق الخرائط لزر «موقعي»: "maps" (افتراضي) أو "hudhud" */
    static String navApp(Context c) {
        return "hudhud".equals(Lang.prefs(c).getString("nav_app", "maps")) ? "hudhud" : "maps";
    }


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        chosen.addAll(Tools.enabled(this));

        // اللغة: نحفظ ثم نعيد رسم الشاشة باللغة الجديدة
        View ar = findViewById(R.id.lang_ar), en = findViewById(R.id.lang_en);
        ar.setSelected(Lang.isArabic(this));
        en.setSelected(!Lang.isArabic(this));
        ar.setOnClickListener(v -> setLang("ar"));
        en.setOnClickListener(v -> setLang("en"));

        // تطبيق الخرائط الذي يفتحه زر «موقعي»
        View navMaps = findViewById(R.id.nav_maps), navHud = findViewById(R.id.nav_hudhud);
        Runnable paintNav = () -> {
            boolean hud = "hudhud".equals(navApp(this));
            navMaps.setSelected(!hud);
            navHud.setSelected(hud);
        };
        paintNav.run();
        navMaps.setOnClickListener(v -> { Lang.prefs(this).edit().putString("nav_app", "maps").apply(); paintNav.run(); });
        navHud.setOnClickListener(v -> { Lang.prefs(this).edit().putString("nav_app", "hudhud").apply(); paintNav.run(); });

        // تنبيهات الصلاة، وزر لسماع نغمة الأذان
        Switch swP = findViewById(R.id.switch_prayer_alerts);
        swP.setChecked(PrayerAlerts.enabled(this));
        findViewById(R.id.row_prayer_alerts).setOnClickListener(v -> {
            boolean on = !PrayerAlerts.enabled(this);
            PrayerAlerts.setEnabled(this, on);
            swP.setChecked(on);
        });
        findViewById(R.id.btn_preview_tone).setOnClickListener(v -> preview(Tones.current(this), toneIndex++ % 3));

        // مجموعة النغمات: الضغط يختار المجموعة ويُسمع نغمة الأذان منها
        android.widget.LinearLayout toneRow = findViewById(R.id.tone_chips);
        float dp = getResources().getDisplayMetrics().density;
        for (int i = 0; i < Tones.STYLES.length; i++) {
            final int style = i;
            TextView chip = new TextView(this);
            chip.setText(Tones.NAMES[i]);
            chip.setTextColor(getColor(R.color.ink));
            chip.setTextSize(14);
            chip.setGravity(android.view.Gravity.CENTER);
            chip.setBackgroundResource(R.drawable.chip_bg);
            chip.setPadding((int) (14 * dp), 0, (int) (14 * dp), 0);
            chip.setClickable(true);
            chip.setFocusable(true);
            android.widget.LinearLayout.LayoutParams lp =
                    new android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, (int) (38 * dp));
            if (i > 0) lp.setMarginStart((int) (6 * dp));
            chip.setLayoutParams(lp);
            chip.setSelected(i == Tones.current(this));
            chip.setOnClickListener(v -> {
                Tones.set(this, style);
                for (int k = 0; k < toneRow.getChildCount(); k++) toneRow.getChildAt(k).setSelected(k == style);
                toneIndex = 2; // الضغطة التالية على زر السماع تُسمع «قبل الإقامة»
                preview(style, 1);
            });
            toneRow.addView(chip);
        }
        // إن كان النمط المختار خارج الجزء الظاهر من الصف، يُمرَّر إليه
        toneRow.post(() -> {
            View sel = toneRow.getChildAt(Tones.current(this));
            if (sel != null) sel.requestRectangleOnScreen(new android.graphics.Rect(0, 0, sel.getWidth(), sel.getHeight()), true);
        });

        buildHomeSection();

        findViewById(R.id.row_adblock).setOnClickListener(v -> AdBlock.setup(this));
        bindWebView();

        findViewById(R.id.row_system).setOnClickListener(v -> {
            try { startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
            catch (Exception ignored) { }
        });

        // أدوات شريط السائق
        GridView grid = findViewById(R.id.tools_grid);
        BaseAdapter adapter = new BaseAdapter() {
            @Override public int getCount() { return Tools.ALL.length; }
            @Override public Object getItem(int p) { return Tools.ALL[p]; }
            @Override public long getItemId(int p) { return p; }
            @Override public View getView(int p, View v, ViewGroup parent) {
                if (v == null) v = LayoutInflater.from(SettingsActivity.this).inflate(R.layout.item_tool, parent, false);
                String k = Tools.ALL[p];
                boolean on = chosen.contains(k);
                ImageView icon = v.findViewById(R.id.t_icon);
                icon.setImageResource(Tools.icon(k));
                icon.setAlpha(on ? 1f : 0.45f);
                ((TextView) v.findViewById(R.id.t_name)).setText(Tools.label(k));
                ((TextView) v.findViewById(R.id.t_desc)).setText(Tools.desc(k));
                ImageView check = v.findViewById(R.id.t_check);
                check.setBackgroundResource(on ? R.drawable.check_on_bg : R.drawable.check_off_bg);
                check.setImageResource(on ? R.drawable.ic_check : 0);
                return v;
            }
        };
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((parent, view, p, id) -> {
            String k = Tools.ALL[p];
            if (chosen.contains(k)) {
                if (chosen.size() == 1) { Toast.makeText(this, R.string.tools_min, Toast.LENGTH_SHORT).show(); return; }
                chosen.remove(k);
            } else {
                if (chosen.size() >= Tools.MAX) { Toast.makeText(this, R.string.tools_max, Toast.LENGTH_SHORT).show(); return; }
                chosen.add(k);
            }
            adapter.notifyDataSetChanged();
            saveTools();
        });

        findViewById(R.id.btn_done).setOnClickListener(v -> finish());
    }

    @Override
    protected void onResume() {
        super.onResume();
        // نحدّث حالة الحجب عند الرجوع من إعدادات الشبكة
        boolean on = AdBlock.isOn(this);
        ((ImageView) findViewById(R.id.adblock_icon)).setColorFilter(getColor(on ? R.color.green : R.color.ink_soft));
        TextView st = findViewById(R.id.adblock_state);
        st.setText(on ? R.string.set_adblock_on : R.string.set_adblock_off);
        st.setTextColor(getColor(on ? R.color.green : R.color.ink_faint));
    }

    private void setLang(String lang) {
        if (lang.equals(Lang.get(this))) return;
        Lang.prefs(this).edit().putString("lang", lang).apply();
        recreate(); // الرئيسية تتحدث بنفسها عند الرجوع إليها
    }

    @Override
    protected void onDestroy() {
        if (player != null) { try { player.release(); } catch (Exception ignored) { } player = null; }
        super.onDestroy();
    }

    // ───────── الشاشة الرئيسية ─────────

    private TextView chip(CharSequence text, boolean sel, int heightDp, boolean fill) {
        float dp = getResources().getDisplayMetrics().density;
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(getColor(R.color.ink));
        t.setTextSize(14);
        t.setGravity(android.view.Gravity.CENTER);
        t.setBackgroundResource(R.drawable.chip_bg);
        t.setPadding((int) (12 * dp), 0, (int) (12 * dp), 0);
        t.setSingleLine(true);
        t.setClickable(true);
        t.setFocusable(true);
        t.setSelected(sel);
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                fill ? 0 : ViewGroup.LayoutParams.WRAP_CONTENT, (int) (heightDp * dp), fill ? 1f : 0f);
        t.setLayoutParams(lp);
        return t;
    }

    private void buildHomeSection() {
        float dp = getResources().getDisplayMetrics().density;
        // الخلفية
        View bgMap = findViewById(R.id.bg_map), bgPat = findViewById(R.id.bg_pattern);
        View colorsRow = findViewById(R.id.row_bg_colors);
        Runnable paintBg = () -> {
            boolean map = Home.mapBackground(this);
            bgMap.setSelected(map);
            bgPat.setSelected(!map);
            colorsRow.setAlpha(map ? 0.55f : 1f); // النقش يظهر خلف الخريطة حتى تتحمّل، فاللون مفيد في الحالتين
        };
        paintBg.run();
        bgMap.setOnClickListener(v -> { Home.setMapBackground(this, true); paintBg.run(); });
        bgPat.setOnClickListener(v -> { Home.setMapBackground(this, false); paintBg.run(); });

        // ألوان النقش: دوائر صغيرة بلونَي كل نمط
        android.widget.LinearLayout colors = findViewById(R.id.bg_colors);
        for (int i = 0; i < PatternView.KEYS.length; i++) {
            final int idx = i;
            View sw = new View(this);
            android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                    new int[]{PatternView.COLORS[i][0], PatternView.COLORS[i][1]});
            d.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            sw.setBackground(d);
            sw.setContentDescription(getString(PatternView.NAMES[i]));
            android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams((int) (34 * dp), (int) (34 * dp));
            lp.setMarginStart((int) (8 * dp));
            sw.setLayoutParams(lp);
            sw.setOnClickListener(v -> {
                PatternView.save(this, idx);
                paintColors(colors);
                Toast.makeText(this, PatternView.NAMES[idx], Toast.LENGTH_SHORT).show();
            });
            colors.addView(sw);
        }
        paintColors(colors);

        // عناصر الرئيسية: ستة أزرار في صفين، الضغط يُظهر العنصر أو يخفيه
        android.widget.LinearLayout r1 = findViewById(R.id.home_items_1), r2 = findViewById(R.id.home_items_2);
        for (int i = 0; i < Home.ITEMS.length; i++) {
            final String item = Home.ITEMS[i];
            TextView c = chip(getString(Home.NAMES[i]), Home.shown(this, item), 40, true);
            android.widget.LinearLayout row = i < 2 ? r1 : r2;
            if (row.getChildCount() > 0) ((android.widget.LinearLayout.LayoutParams) c.getLayoutParams()).setMarginStart((int) (6 * dp));
            c.setOnClickListener(v -> {
                boolean on = !Home.shown(this, item);
                Home.setShown(this, item, on);
                v.setSelected(on);
            });
            row.addView(c);
        }
        buildNavModes(R.id.nav_modes_maps, Home.MAPS);
        buildNavModes(R.id.nav_modes_hudhud, Home.HUDHUD);
        buildWidgets();
        buildOpacity();
    }

    /** الشفافية: شريط بعشر خطوات (كل خطوة ١٠٪) مع زرّي − و+ للضبط الدقيق أثناء القيادة */
    private void buildOpacity() {
        android.widget.SeekBar bar = findViewById(R.id.opacity_seek);
        TextView val = findViewById(R.id.opacity_value);
        java.util.function.IntConsumer show = v -> {
            java.text.NumberFormat nf = java.text.NumberFormat.getInstance(Lang.isArabic(this) ? new java.util.Locale("ar", "SA") : java.util.Locale.ENGLISH);
            val.setText(nf.format(v) + (Lang.isArabic(this) ? "٪" : "%"));
        };
        int cur = Looks.opacity(this);
        bar.setProgress(cur / 10);
        show.accept(cur);
        bar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar s, int p, boolean user) {
                Looks.setOpacity(SettingsActivity.this, p * 10);
                show.accept(p * 10);
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar s) { }
            @Override public void onStopTrackingTouch(android.widget.SeekBar s) { }
        });
        findViewById(R.id.opacity_minus).setOnClickListener(v -> bar.setProgress(Math.max(0, bar.getProgress() - 1)));
        findViewById(R.id.opacity_plus).setOnClickListener(v -> bar.setProgress(Math.min(10, bar.getProgress() + 1)));
    }

    // ───────── الساعة والسرعة والرادار ─────────

    /** مجموعة أزرار يُختار منها واحد؛ onPick يحفظ الاختيار */
    private void single(int containerId, CharSequence[] labels, int selected, java.util.function.IntConsumer onPick) {
        android.widget.LinearLayout box = findViewById(containerId);
        float dp = getResources().getDisplayMetrics().density;
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            TextView c = chip(labels[i], i == selected, 38, false);
            if (i > 0) ((android.widget.LinearLayout.LayoutParams) c.getLayoutParams()).setMarginStart((int) (6 * dp));
            c.setOnClickListener(v -> {
                for (int k = 0; k < box.getChildCount(); k++) box.getChildAt(k).setSelected(k == idx);
                onPick.accept(idx);
            });
            box.addView(c);
        }
    }

    /** دوائر ألوان أنماط البطاقة؛ النمط المختار بإطار برتقالي واسمه يظهر عند الضغط */
    private void styleSwatches(int containerId, String key) {
        android.widget.LinearLayout box = findViewById(containerId);
        float dp = getResources().getDisplayMetrics().density;
        Runnable paint = () -> {
            int cur = Looks.style(this, key);
            for (int k = 0; k < box.getChildCount(); k++) {
                int[] col = Looks.colors(this, k);
                android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable(
                        android.graphics.drawable.GradientDrawable.Orientation.TL_BR, new int[]{col[0] | 0xFF000000, col[1] | 0xFF000000});
                d.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                d.setStroke((int) ((k == cur ? 3 : 1) * dp), k == cur ? getColor(R.color.orange) : 0x33FFFFFF);
                box.getChildAt(k).setBackground(d);
            }
        };
        for (int i = 0; i < Looks.STYLES.length; i++) {
            final int idx = i;
            TextView sw = new TextView(this);
            sw.setGravity(android.view.Gravity.CENTER);
            sw.setTextSize(11);
            sw.setTextColor(Looks.colors(this, i)[3]);
            sw.setText(i == 0 ? "A" : ""); // «متناسق» يتبع لون الخلفية
            sw.setContentDescription(getString(Looks.STYLE_NAMES[i]));
            android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams((int) (34 * dp), (int) (34 * dp));
            if (i > 0) lp.setMarginStart((int) (8 * dp));
            sw.setLayoutParams(lp);
            sw.setOnClickListener(v -> {
                Looks.setStyle(this, key, idx);
                paint.run();
                Toast.makeText(this, Looks.STYLE_NAMES[idx], Toast.LENGTH_SHORT).show();
            });
            box.addView(sw);
        }
        paint.run();
    }

    private void buildWidgets() {
        android.content.SharedPreferences p = Lang.prefs(this);
        styleSwatches(R.id.clock_styles, "clock_style");
        styleSwatches(R.id.speed_styles, "speed_style");

        // الوقت: ١٢ أو ٢٤ ساعة، والتاريخ: ميلادي أو هجري أو بدون (مجموعتان في صف واحد)
        String[] dates = {"greg", "hijri", "none"};
        CharSequence[] fmt = {getString(R.string.fmt_12), getString(R.string.fmt_24),
                getString(R.string.date_greg), getString(R.string.date_hijri), getString(R.string.date_none)};
        android.widget.LinearLayout opts = findViewById(R.id.clock_opts);
        float dp = getResources().getDisplayMetrics().density;
        String dm = Looks.dateMode(this);
        for (int i = 0; i < fmt.length; i++) {
            final int idx = i;
            boolean sel = i < 2 ? (i == 1) == Looks.clock24(this) : dates[i - 2].equals(dm);
            TextView c = chip(fmt[i], sel, 38, false);
            android.widget.LinearLayout.LayoutParams lp = (android.widget.LinearLayout.LayoutParams) c.getLayoutParams();
            if (i > 0) lp.setMarginStart((int) ((i == 2 ? 18 : 6) * dp)); // فاصل بين المجموعتين
            c.setOnClickListener(v -> {
                if (idx < 2) p.edit().putBoolean("clock_24h", idx == 1).apply();
                else p.edit().putString("date_mode", dates[idx - 2]).apply();
                for (int k = 0; k < opts.getChildCount(); k++)
                    if ((k < 2) == (idx < 2)) opts.getChildAt(k).setSelected(k == idx);
            });
            opts.addView(c);
        }

        // تنبيه تجاوز السرعة: النص يصير أحمر فوق الحد
        int[] limits = {0, 100, 120, 140};
        int curLimit = Looks.speedLimit(this), sel = 0;
        CharSequence[] limitLabels = new CharSequence[limits.length];
        for (int i = 0; i < limits.length; i++) {
            limitLabels[i] = limits[i] == 0 ? getString(R.string.limit_off) : Lang.speed(this, limits[i]);
            if (limits[i] == curLimit) sel = i;
        }
        single(R.id.speed_limit_opts, limitLabels, sel, i -> p.edit().putInt("speed_limit", limits[i]).apply());

        // رادار الكاميرات: ثلاثة مفاتيح مستقلة
        String[] keys = {"radar_cam", "radar_bump", "radar_sound"};
        int[] names = {R.string.radar_cams, R.string.radar_bumps, R.string.radar_sound};
        android.widget.LinearLayout radar = findViewById(R.id.radar_opts);
        for (int i = 0; i < keys.length; i++) {
            final String key = keys[i];
            TextView c = chip(getString(names[i]), p.getBoolean(key, true), 38, false);
            if (i > 0) ((android.widget.LinearLayout.LayoutParams) c.getLayoutParams()).setMarginStart((int) (6 * dp));
            c.setOnClickListener(v -> {
                boolean on = !p.getBoolean(key, true);
                p.edit().putBoolean(key, on).apply();
                v.setSelected(on);
            });
            radar.addView(c);
        }

        int[] dists = {300, 500, 800};
        int curDist = Looks.radarDistance(this), dsel = 1;
        CharSequence[] distLabels = new CharSequence[dists.length];
        for (int i = 0; i < dists.length; i++) { distLabels[i] = Lang.meters(this, dists[i]); if (dists[i] == curDist) dsel = i; }
        single(R.id.radar_dist_opts, distLabels, dsel, i -> p.edit().putInt("radar_dist", dists[i]).apply());
    }

    /** خرائط قوقل أو هدهد: أربعة أوضاع، واحد منها مختار */
    private void buildNavModes(int containerId, String app) {
        android.widget.LinearLayout box = findViewById(containerId);
        float dp = getResources().getDisplayMetrics().density;
        String cur = Home.navMode(this, app);
        for (int i = 0; i < Home.NAV_MODES.length; i++) {
            final String mode = Home.NAV_MODES[i];
            TextView c = chip(getString(Home.NAV_MODE_NAMES[i]), mode.equals(cur), 38, false);
            if (i > 0) ((android.widget.LinearLayout.LayoutParams) c.getLayoutParams()).setMarginStart((int) (6 * dp));
            c.setOnClickListener(v -> {
                Home.setNavMode(this, app, mode);
                for (int k = 0; k < box.getChildCount(); k++) box.getChildAt(k).setSelected(box.getChildAt(k) == v);
            });
            box.addView(c);
        }
    }

    /** الدائرة المختارة بإطار برتقالي */
    private void paintColors(android.widget.LinearLayout colors) {
        int cur = PatternView.current(this);
        float dp = getResources().getDisplayMetrics().density;
        for (int k = 0; k < colors.getChildCount(); k++) {
            android.graphics.drawable.GradientDrawable d = (android.graphics.drawable.GradientDrawable) colors.getChildAt(k).getBackground();
            d.setStroke((int) ((k == cur ? 3 : 1) * dp), k == cur ? getColor(R.color.orange) : 0x33FFFFFF);
        }
    }

    /** نسخة متصفح النظام (WebView): الخريطة والفيديو يعتمدان عليه، والقديم جدًا لا يشغّل يوتيوب */
    private void bindWebView() {
        TextView st = findViewById(R.id.webview_state);
        String pkg = null, ver = null;
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            android.content.pm.PackageInfo pi = android.webkit.WebView.getCurrentWebViewPackage();
            if (pi != null) { pkg = pi.packageName; ver = pi.versionName; }
        }
        int major = 0;
        try { if (ver != null) major = Integer.parseInt(ver.split("\\.")[0]); } catch (NumberFormatException ignored) { }
        boolean old = major > 0 && major < 100;
        st.setText(ver == null ? getString(R.string.webview_unknown)
                : getString(old ? R.string.webview_old : R.string.webview_ok, ver));
        st.setTextColor(getColor(old ? R.color.orange : R.color.ink_faint));
        final String target = pkg != null ? pkg : "com.google.android.webview";
        findViewById(R.id.row_webview).setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + target)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception e) {
                Toast.makeText(this, R.string.no_store, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void saveTools() {
        List<String> ordered = new ArrayList<>();
        for (String k : Tools.ALL) if (chosen.contains(k)) ordered.add(k);
        Tools.save(this, ordered);
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveTools();
    }
}
