package com.santafe.launcher;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.Locale;

/**
 * المشغّل الرئيسي لشاشة سنتافي بتصميم الخريطة الداكنة:
 * بطاقتا خرائط قوقل وهدهد، مربع Brave للفيديو يسار الشاشة، بار أيقونات صغير يُخفى من الإعدادات،
 * وزر درع يضبط حجب الإعلانات والمحتوى غير اللائق لكل التطبيقات عبر DNS خاص.
 */
public class MainActivity extends Activity {

    static final String MAPS_PKG = "com.google.android.apps.maps";
    static final String BRAVE_PKG = "com.brave.browser";
    static final int DOCK_SLOTS = 5;

    private MapBackdropView backdrop;
    private View cardMaps, cardHudhud;
    private VideoPanel video;
    private LiveMap liveMap;
    private TextView speedView;
    private static final int REQ_LOCATION = 7;
    private String createdLang;
    private LinearLayout railItems;
    private LinearLayout dock;
    private SharedPreferences prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override public void run() { refreshPrayer(); ui.postDelayed(this, 5000); }
    };
    private TextView prayerName, prayerTime;
    private long[] todayTimes, tomorrowTimes;
    private int timesDay = -1;
    private String timesCity;

    private String hudhudPkg; // يُكتشف تلقائيًا من التطبيقات المثبتة
    private boolean introPlayed;

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(Lang.wrap(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences("launcher", MODE_PRIVATE);
        createdLang = Lang.get(this);

        backdrop = findViewById(R.id.backdrop);
        cardMaps = findViewById(R.id.card_maps);
        cardHudhud = findViewById(R.id.card_hudhud);
        railItems = findViewById(R.id.rail_items);
        speedView = findViewById(R.id.speed);
        dock = findViewById(R.id.dock);

        ((TextView) cardMaps.findViewById(R.id.nav_title)).setText(R.string.maps);
        ((TextView) cardHudhud.findViewById(R.id.nav_title)).setText(R.string.hudhud);

        cardMaps.setOnClickListener(v -> openOrInstall(MAPS_PKG,
                "market://details?id=" + MAPS_PKG,
                "https://play.google.com/store/apps/details?id=" + MAPS_PKG));
        cardHudhud.setOnClickListener(v -> {
            if (hudhudPkg == null) hudhudPkg = findHudhud();
            openOrInstall(hudhudPkg,
                    "market://search?q=Hudhud%20maps&c=apps",
                    "https://play.google.com/store/search?q=Hudhud%20maps&c=apps");
        });

        // زر «الإعدادات» ثابت في أسفل شريط السائق: اللغة، البار السفلي، الأدوات
        View edit = findViewById(R.id.rail_edit);
        ImageView editIcon = edit.findViewById(R.id.rail_icon);
        editIcon.setImageResource(R.drawable.ic_settings);
        editIcon.setColorFilter(getColor(R.color.ink_soft));
        edit.setContentDescription(getString(R.string.settings_title));
        edit.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));

        // لوحة الفيديو المثبتة يسار الشاشة
        View card = findViewById(R.id.card_video);
        View slot = card.findViewById(R.id.video_slot);
        slot.setClipToOutline(true);
        video = new VideoPanel(this, card.findViewById(R.id.video_web), (android.view.ViewGroup) slot,
                findViewById(R.id.video_full), card.findViewById(R.id.video_drive_cover),
                card.findViewById(R.id.video_toggle_label));
        card.findViewById(R.id.video_toggle).setOnClickListener(v -> video.toggle());
        findViewById(R.id.video_full_close).setOnClickListener(v -> video.toggle());
        card.findViewById(R.id.video_home).setOnClickListener(v -> video.home());
        card.findViewById(R.id.video_brave).setOnClickListener(v -> openBrave());

        // الخريطة الحية خلف الشاشة، مع تنبيهات الكاميرات والمطبات
        View alert = findViewById(R.id.alert_box);
        liveMap = new LiveMap(this, findViewById(R.id.map_web), backdrop, alert,
                findViewById(R.id.alert_text), findViewById(R.id.alert_dist), (kmh, moving) -> {
                    speedView.setVisibility(View.VISIBLE);
                    speedView.setText(Lang.speed(this, kmh));
                    video.setMoving(moving);
                });
        if (!LiveMap.hasPermission(this) && !prefs.getBoolean("asked_location", false)) {
            prefs.edit().putBoolean("asked_location", true).apply();
            requestPermissions(new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // تغيّرت اللغة من الإعدادات: نعيد بناء الشاشة باللغة الجديدة
        if (!Lang.get(this).equals(createdLang)) { recreate(); return; }
        hudhudPkg = findHudhud();
        bindCard(cardMaps, MAPS_PKG, R.string.maps_sub);
        bindCard(cardHudhud, hudhudPkg, R.string.hudhud_sub);
        dock.setVisibility(SettingsActivity.dockVisible(this) ? View.VISIBLE : View.GONE);
        buildDock();
        buildRail();
        ui.post(ticker);
        backdrop.start();
        liveMap.start();
        if (!introPlayed) { introPlayed = true; playIntro(); }
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(ticker);
        backdrop.stop();
        liveMap.stop();
        video.pause();
    }

    /** زر الرجوع يصغّر الفيديو أو يرجع صفحة فيه؛ والمشغّل نفسه لا يُغلق */
    @Override
    public void onBackPressed() { video.back(); }

    @Override
    protected void onDestroy() {
        liveMap.destroy();
        video.destroy();
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == REQ_LOCATION && LiveMap.hasPermission(this)) liveMap.start();
    }

    // ───────── الحركة ─────────

    /** دخول متتابع: البطاقة العلوية، ثم الشريط، ثم البطاقات من الأسفل، ثم البار */
    private void playIntro() {
        float rise = 28 * getResources().getDisplayMetrics().density;
        View[] order = {
                findViewById(R.id.top_card), findViewById(R.id.rail),
                findViewById(R.id.card_video), cardMaps, cardHudhud, dock
        };
        for (int i = 0; i < order.length; i++) {
            View v = order[i];
            boolean top = i < 1;
            v.setAlpha(0f);
            v.setTranslationY(top ? -rise : rise);
            v.animate().alpha(1f).translationY(0f)
                    .setStartDelay(250 + i * 90L).setDuration(550)
                    .setInterpolator(new DecelerateInterpolator(2f)).start();
        }
    }

    private void pop(View v) {
        v.animate().cancel();
        v.setScaleX(0.88f);
        v.setScaleY(0.88f);
        v.animate().scaleX(1f).scaleY(1f).setDuration(260)
                .setInterpolator(new OvershootInterpolator(3f)).start();
    }

    // ───────── شريط السائق ─────────

    private void buildRail() {
        railItems.removeAllViews();
        prayerName = prayerTime = null;
        LayoutInflater inf = LayoutInflater.from(this);
        for (String k : Tools.enabled(this)) {
            View item = inf.inflate(R.layout.item_rail, railItems, false);
            ImageView icon = item.findViewById(R.id.rail_icon);
            TextView label = item.findViewById(R.id.rail_label);
            icon.setImageResource(Tools.icon(k));
            item.setContentDescription(getString(Tools.label(k)) + ": " + getString(Tools.desc(k)));
            switch (k) {
                case Tools.LOCATION:
                    item.setOnClickListener(v -> openMyLocation());
                    break;
                case Tools.PRAYER:
                    prayerName = label;
                    prayerName.setVisibility(View.VISIBLE);
                    prayerTime = item.findViewById(R.id.rail_extra);
                    prayerTime.setVisibility(View.VISIBLE);
                    item.setOnClickListener(v -> startActivity(new Intent(this, PrayerActivity.class)));
                    break;
                case Tools.APPS:
                    item.setOnClickListener(v -> startActivity(new Intent(this, AppsActivity.class)));
                    break;
                default: // Brave
                    item.setOnClickListener(v -> openBrave());
            }
            railItems.addView(item);
        }
        timesDay = -1; // نعيد حساب الصلاة للمدينة الحالية
        refreshPrayer();
    }

    /** يحدّث أداة الصلاة: اسم الصلاة القادمة ووقتها */
    private void refreshPrayer() {
        if (prayerName == null) return;
        java.util.Calendar now = java.util.Calendar.getInstance();
        String city = PrayerActivity.cityKey(this);
        if (timesDay != now.get(java.util.Calendar.DAY_OF_YEAR) || !city.equals(timesCity)) {
            timesDay = now.get(java.util.Calendar.DAY_OF_YEAR);
            timesCity = city;
            PrayerTimes.City c = PrayerTimes.city(city);
            todayTimes = PrayerTimes.forDay(now, c);
            java.util.Calendar tm = (java.util.Calendar) now.clone();
            tm.add(java.util.Calendar.DAY_OF_YEAR, 1);
            tomorrowTimes = PrayerTimes.forDay(tm, c);
        }
        long t = now.getTimeInMillis();
        int next = -1;
        for (int k : PrayerTimes.PRAYERS) if (todayTimes[k] > t) { next = k; break; }
        long at = next == -1 ? tomorrowTimes[PrayerTimes.FAJR] : todayTimes[next];
        if (next == -1) next = PrayerTimes.FAJR;
        prayerName.setText(PrayerActivity.NAMES[next]);
        // قبل الأذان بربع ساعة: نعرض الوقت الباقي بدل الساعة
        prayerTime.setText(at - t <= 15 * 60_000L ? Lang.left(this, at - t) : Lang.time(this, at, false));
    }

    /** «موقعي»: يفتح موقعك مباشرة في تطبيق الخرائط المختار من الإعدادات */
    private void openMyLocation() {
        if ("hudhud".equals(SettingsActivity.navApp(this))) cardHudhud.performClick();
        else openMyLocationInMaps();
    }

    /** يفتح خرائط قوقل على موقعك الحالي؛ وإن لم تكن مثبتة يفتح المتجر */
    private void openMyLocationInMaps() {
        if (getPackageManager().getLaunchIntentForPackage(MAPS_PKG) != null) {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?z=17")).setPackage(MAPS_PKG);
            if (tryStart(i)) return;
        }
        cardMaps.performClick();
    }

    // ───────── بطاقتا الخرائط ─────────

    private void bindCard(View card, String pkg, int subRes) {
        PackageManager pm = getPackageManager();
        ImageView icon = card.findViewById(R.id.nav_icon);
        TextView sub = card.findViewById(R.id.nav_sub);
        TextView action = card.findViewById(R.id.nav_action);
        if (pkg != null && pm.getLaunchIntentForPackage(pkg) != null) {
            try { icon.setImageDrawable(pm.getApplicationIcon(pkg)); }
            catch (PackageManager.NameNotFoundException ignored) { }
            sub.setText(subRes);
            action.setText(R.string.start_nav);
        } else {
            icon.setImageResource(R.drawable.ic_map_placeholder);
            sub.setText(R.string.not_installed);
            action.setText(R.string.opening_store);
        }
    }

    private void openOrInstall(String pkg, String marketUri, String webUri) {
        Intent launch = pkg == null ? null : getPackageManager().getLaunchIntentForPackage(pkg);
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(launch);
            return;
        }
        Toast.makeText(this, R.string.opening_store, Toast.LENGTH_SHORT).show();
        if (!tryStart(new Intent(Intent.ACTION_VIEW, Uri.parse(marketUri)))
                && !tryStart(new Intent(Intent.ACTION_VIEW, Uri.parse(webUri)))) {
            Toast.makeText(this, R.string.no_store, Toast.LENGTH_LONG).show();
        }
    }

    /**
     * يبحث عن هدهد باسم الحزمة فقط (لا باسم العرض، لأن أي تطبيق يستطيع أن يسمّي نفسه «هدهد»).
     * إن وُجد أكثر من تطبيق، نفضّل المثبّت من متجر قوقل.
     */
    private String findHudhud() {
        PackageManager pm = getPackageManager();
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        String found = null;
        for (ResolveInfo r : pm.queryIntentActivities(i, 0)) {
            String pkg = r.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || !pkg.toLowerCase(Locale.ROOT).contains("hudhud")) continue;
            if (Safe.fromPlayStore(this, pkg)) return pkg;
            if (found == null) found = pkg;
        }
        return found;
    }

    // ───────── Brave ─────────

    private boolean braveInstalled() {
        return getPackageManager().getLaunchIntentForPackage(BRAVE_PKG) != null;
    }

    /** يفتح Brave نفسه بملء الشاشة (للتصفح الكامل بلا إعلانات) */
    private void openBrave() {
        if (!braveInstalled()) {
            openOrInstall(null, "market://details?id=" + BRAVE_PKG,
                    "https://play.google.com/store/apps/details?id=" + BRAVE_PKG);
            return;
        }
        Intent i = getPackageManager().getLaunchIntentForPackage(BRAVE_PKG);
        // نفتح الصفحة الحالية من لوحة الفيديو في Brave إن وُجدت
        String u = video.url();
        if (u != null && u.startsWith("https://")) i = new Intent(Intent.ACTION_VIEW, Uri.parse(u)).setPackage(BRAVE_PKG);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (!tryStart(i)) tryStart(getPackageManager().getLaunchIntentForPackage(BRAVE_PKG));
    }

    // ───────── بار الأيقونات ─────────

    private void buildDock() {
        dock.removeAllViews();
        PackageManager pm = getPackageManager();
        LayoutInflater inf = LayoutInflater.from(this);

        // أول أيقونة: الرئيسية، نشطة بالبرتقالي كما في التصميم
        View home = inf.inflate(R.layout.item_dock, dock, false);
        home.setBackgroundResource(R.drawable.dock_active_bg);
        ((ImageView) home.findViewById(R.id.dock_icon)).setImageResource(R.drawable.ic_pin);
        home.setContentDescription(getString(R.string.home));
        home.setOnClickListener(this::pop);
        dock.addView(home);

        for (int slot = 0; slot < DOCK_SLOTS; slot++) {
            final int s = slot;
            View item = inf.inflate(R.layout.item_dock, dock, false);
            ImageView icon = item.findViewById(R.id.dock_icon);

            final ComponentName target = slotComponent(s);
            boolean filled = false;
            if (target != null && !Safe.isLaunchable(this, target)) {
                prefs.edit().remove("dock_" + s).apply(); // حُذف التطبيق أو لم يعد له أيقونة
            } else if (target != null) {
                try {
                    icon.setImageDrawable(pm.getActivityIcon(target));
                    item.setContentDescription(pm.getActivityInfo(target, 0).loadLabel(pm));
                    filled = true;
                    item.setOnClickListener(v -> {
                        pop(v);
                        if (!Safe.isLaunchable(this, target)) { buildDock(); return; }
                        tryStart(new Intent(Intent.ACTION_MAIN)
                                .addCategory(Intent.CATEGORY_LAUNCHER)
                                .setComponent(target)
                                .addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
                    });
                    // ضغطة طويلة: تغيير التطبيق أو إفراغ الخانة
                    item.setOnLongClickListener(v -> { pickForSlot(s); return true; });
                } catch (PackageManager.NameNotFoundException gone) {
                    prefs.edit().remove("dock_" + s).apply(); // حُذف التطبيق
                }
            }
            if (!filled) {
                icon.setImageResource(R.drawable.ic_add);
                item.setContentDescription(getString(R.string.dock_add));
                item.setOnClickListener(v -> pickForSlot(s));
            }
            dock.addView(item);
        }
    }

    private ComponentName slotComponent(int slot) {
        String v = prefs.getString("dock_" + slot, null);
        return v == null ? null : ComponentName.unflattenFromString(v);
    }

    private void pickForSlot(int slot) {
        startActivityForResult(new Intent(this, AppsActivity.class)
                .putExtra(AppsActivity.EXTRA_PICK_SLOT, slot), 100 + slot);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        int slot = requestCode - 100;
        if (resultCode != RESULT_OK || slot < 0 || slot >= DOCK_SLOTS || data == null) return;
        String comp = data.getStringExtra(AppsActivity.EXTRA_COMPONENT);
        ComponentName cn = comp == null ? null : ComponentName.unflattenFromString(comp);
        if (cn == null) prefs.edit().remove("dock_" + slot).apply();
        else if (Safe.isLaunchable(this, cn)) prefs.edit().putString("dock_" + slot, cn.flattenToString()).apply();
        buildDock();
    }

    private boolean tryStart(Intent i) {
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            return false;
        }
    }
}
