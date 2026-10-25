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
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.Locale;

/**
 * المشغّل الرئيسي لشاشة سنتافي:
 * خلفية نقش، أو «وضع هدهد» حيث يفتح زر الرئيسية تطبيق هدهد نفسه وشريط السائق يطفو فوقه (OverlayService).
 * بطاقات خرائط قوقل وهدهد وBrave (يفتح Brave الحقيقي في نافذة صغيرة)، وبار أيقونات صغير، ومؤقت الإقامة.
 */
public class MainActivity extends Activity {

    static final String MAPS_PKG = "com.google.android.apps.maps";
    /** فتح شاشة المشغّل نفسها دون الانتقال لهدهد (من زر «الرئيسية» في الشريط العائم) */
    static final String EXTRA_STAY = "stay";
    static final int DOCK_SLOTS = 5;

    private PatternView backdrop;
    private View cardMaps, cardHudhud, cardBrave;
    private GpsSpeed gps;
    private TextView speedView;
    private static final int REQ_LOCATION = 7;
    private String createdLang;
    private LinearLayout railItems;
    private LinearLayout dock;
    private SharedPreferences prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override public void run() { refreshPrayer(); refreshDate(); updateIqama(); ui.postDelayed(this, 5000); }
    };
    private TextView prayerName, prayerTime;
    private View prayerItem;
    private IqamaBubble bubble;
    private final Runnable iqamaTick = this::updateIqama;
    private TextClock clockTime;
    private TextView clockDate;
    private ImageView clockIcon;
    private String lastSpeed, lastDate, builtSig;

    private String dockSignature() {
        StringBuilder b = new StringBuilder(Tools.enabled(this).toString());
        for (int i = 0; i < DOCK_SLOTS; i++) b.append('|').append(prefs.getString("dock_" + i, ""));
        return b.toString();
    }
    private int speedColor = 0xFFFFFFFF, speedLimit;
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
        clockTime = findViewById(R.id.clock_time);
        clockDate = findViewById(R.id.clock_date);
        clockIcon = findViewById(R.id.clock_icon);
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

        // بطاقة Brave يسار الشاشة: تفتح تطبيق Brave الحقيقي في نافذة صغيرة
        cardBrave = findViewById(R.id.card_brave);
        ((TextView) cardBrave.findViewById(R.id.nav_title)).setText(R.string.tool_brave);
        cardBrave.setOnClickListener(v -> BraveWindow.open(this));

        // فقاعة مؤقت الإقامة بجانب أداة الصلاة في الشريط
        bubble = new IqamaBubble(this);
        bubble.setLayoutDirection(Lang.isArabic(this) ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        bubble.setElevation(6 * getResources().getDisplayMetrics().density);
        bubble.setVisibility(View.GONE);
        bubble.setOnClickListener(v -> startActivity(new Intent(this, PrayerActivity.class)));
        android.widget.FrameLayout.LayoutParams blp = new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.gravity = android.view.Gravity.TOP | android.view.Gravity.LEFT;
        ((android.view.ViewGroup) findViewById(R.id.root)).addView(bubble, blp);
        gps = new GpsSpeed(this, kmh -> {
            if (Home.shown(this, Home.SPEED) && speedView.getVisibility() != View.VISIBLE) speedView.setVisibility(View.VISIBLE);
            String t = Lang.speed(this, kmh);
            if (!t.equals(lastSpeed)) { // لا نعيد رسم النص إلا إن تغيّر الرقم
                lastSpeed = t;
                speedView.setText(t);
                speedView.setTextColor(speedLimit > 0 && kmh > speedLimit ? 0xFFFF5A50 : speedColor);
            }
        });
        // أول تشغيل: نطلب الموقع (للخريطة والتنبيهات) والإشعارات (لتنبيهات الصلاة على أندرويد ١٣+) معًا
        if (!prefs.getBoolean("asked_location", false)) {
            prefs.edit().putBoolean("asked_location", true).apply();
            java.util.List<String> ask = new java.util.ArrayList<>();
            if (!GpsSpeed.hasPermission(this)) {
                ask.add(android.Manifest.permission.ACCESS_FINE_LOCATION);
                ask.add(android.Manifest.permission.ACCESS_COARSE_LOCATION);
            }
            if (android.os.Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                ask.add(android.Manifest.permission.POST_NOTIFICATIONS);
            if (!ask.isEmpty()) requestPermissions(ask.toArray(new String[0]), REQ_LOCATION);
        }
        if (savedInstanceState == null) maybeOpenHudhud(getIntent());
    }

    @Override
    protected void onResume() {
        super.onResume();
        // تغيّرت اللغة من الإعدادات: نعيد بناء الشاشة باللغة الجديدة
        if (!Lang.get(this).equals(createdLang)) { recreate(); return; }
        OverlayService.suppress(true); // للرئيسية شريطها الخاص
        OverlayService.sync(this);
        hudhudPkg = findHudhud();
        bindCard(cardMaps, MAPS_PKG, R.string.maps_sub);
        bindCard(cardHudhud, hudhudPkg, R.string.hudhud_sub);
        String brave = BraveWindow.installed(this);
        bindCard(cardBrave, brave, R.string.brave_sub);
        if (brave != null) ((TextView) cardBrave.findViewById(R.id.nav_action)).setText(R.string.brave_open);
        applyHomeItems();
        // البار والشريط يُعاد بناؤهما فقط إن تغيّرت محتوياتهما (تحميل الأيقونات بطيء نسبيًا)
        String sig = dockSignature();
        if (!sig.equals(builtSig)) { builtSig = sig; buildDock(); buildRail(); }
        else { timesDay = -1; refreshPrayer(); }
        ui.post(ticker);
        gps.start();
        PrayerAlerts.schedule(this);
        if (!introPlayed) { introPlayed = true; playIntro(); }
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(ticker);
        ui.removeCallbacks(iqamaTick);
        gps.stop();
        OverlayService.suppress(false);
    }

    /** بطاقة خرائط قوقل أو هدهد بأحد أربعة أوضاع: بطاقة، أيقونة فقط، خريطة حية، مخفية */
    private void applyNavMode(View card, String mode) {
        float dp = getResources().getDisplayMetrics().density;
        boolean hidden = Home.HIDDEN.equals(mode), icon = Home.ICON.equals(mode);
        card.setVisibility(hidden ? View.GONE : View.VISIBLE);
        int full = icon ? View.GONE : View.VISIBLE;
        card.findViewById(R.id.nav_texts).setVisibility(full);
        card.findViewById(R.id.nav_bar).setVisibility(full);
        card.findViewById(R.id.nav_action).setVisibility(full);
        android.view.ViewGroup.LayoutParams lp = card.getLayoutParams();
        lp.width = icon ? android.view.ViewGroup.LayoutParams.WRAP_CONTENT : (int) (270 * dp);
        card.setLayoutParams(lp);
        View ic = card.findViewById(R.id.nav_icon);
        android.view.ViewGroup.LayoutParams il = ic.getLayoutParams();
        il.width = il.height = (int) ((icon ? 56 : 36) * dp);
        ic.setLayoutParams(il);
        int pad = (int) ((icon ? 10 : 14) * dp);
        card.setPadding(pad, icon ? pad : (int) (12 * dp), pad, icon ? pad : (int) (11 * dp));
        card.setContentDescription(((TextView) card.findViewById(R.id.nav_title)).getText());
    }

    /** بطاقة الساعة ولوحة السرعة: النمط والألوان وشكل الوقت والتاريخ */
    private void applyLooks() {
        int cs = Looks.style(this, "clock_style");
        int[] k = Looks.colors(this, cs);
        findViewById(R.id.top_card).setBackground(Looks.background(this, cs, 14));
        clockTime.setTextColor(k[3]);
        clockDate.setTextColor(k[3]);
        clockIcon.setColorFilter(k[4]);
        String f = Looks.clock24(this) ? "HH:mm" : "h:mm a";
        clockTime.setFormat12Hour(f);
        clockTime.setFormat24Hour(f);
        clockDate.setVisibility("none".equals(Looks.dateMode(this)) ? View.GONE : View.VISIBLE);
        lastDate = null;
        refreshDate();
        int ss = Looks.style(this, "speed_style");
        speedView.setBackground(Looks.background(this, ss, 14));
        speedColor = Looks.colors(this, ss)[3];
        speedLimit = Looks.speedLimit(this);
        speedView.setTextColor(speedColor);
        lastSpeed = null;
    }

    private int dateDay = -1;

    /** التاريخ يتغيّر مرة في اليوم؛ لا نعيد تنسيقه إلا إذا تغيّر اليوم */
    private void refreshDate() {
        if (clockDate.getVisibility() != View.VISIBLE) return;
        int day = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_YEAR);
        if (day == dateDay && lastDate != null) return;
        dateDay = day;
        String d = Looks.dateText(this, System.currentTimeMillis());
        if (!d.equals(lastDate)) { lastDate = d; clockDate.setText(d); }
    }

    /** شفافية خلفيات البطاقات والبارات (النص والأيقونات لا تتأثر) */
    private void applyOpacity() {
        int op = Looks.opacity(this), alpha = Math.round(op * 2.55f);
        float dp = getResources().getDisplayMetrics().density;
        int[] ids = {R.id.top_card, R.id.speed, R.id.rail, R.id.dock, R.id.card_brave};
        for (int id : ids) fade(findViewById(id), alpha);
        fade(cardMaps, alpha);
        fade(cardHudhud, alpha);
        bubble.setBackgroundOpacity(Math.max(alpha, 140)); // الفقاعة تبقى مقروءة دائمًا
        // الظل تحت بطاقة الساعة يختفي مع الشفافية حتى لا يظهر إطار رمادي حول بطاقة شفافة
        findViewById(R.id.top_card).setElevation(4 * dp * op / 100f);
    }

    private static void fade(View v, int alpha) {
        if (v == null || v.getBackground() == null) return;
        v.setBackground(v.getBackground().mutate()); // نسخة خاصة حتى لا تتأثر شاشات أخرى بنفس الخلفية
        v.getBackground().setAlpha(alpha);
    }

    /** إظهار عناصر الرئيسية أو إخفاؤها حسب الإعدادات، ولون النقش */
    private void applyHomeItems() {
        backdrop.setPalette(PatternView.current(this));
        applyLooks();
        applyOpacity();
        findViewById(R.id.top_card).setVisibility(Home.shown(this, Home.CLOCK) ? View.VISIBLE : View.INVISIBLE);
        if (!Home.shown(this, Home.SPEED)) speedView.setVisibility(View.GONE);
        applyNavMode(cardMaps, Home.navMode(this, Home.MAPS));
        applyNavMode(cardHudhud, Home.navMode(this, Home.HUDHUD));
        dock.setVisibility(Home.shown(this, Home.DOCK) ? View.VISIBLE : View.GONE);
        cardBrave.setVisibility(Home.shown(this, Home.VIDEO) ? View.VISIBLE : View.INVISIBLE);
        // بطاقة خرائط قوقل لها هامش جهة هدهد؛ نزيله إن أُخفي هدهد
        ((android.view.ViewGroup.MarginLayoutParams) cardMaps.getLayoutParams()).setMarginEnd(
                Home.shown(this, Home.HUDHUD) ? (int) (10 * getResources().getDisplayMetrics().density) : 0);
        cardMaps.requestLayout();
    }

    /** زر الرجوع لا يغلق المشغّل */
    @Override
    public void onBackPressed() { }

    /** زر الرئيسية في وضع هدهد يفتح هدهد نفسه (إلا إن طُلبت شاشة المشغّل صراحة) */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        maybeOpenHudhud(intent);
    }

    private void maybeOpenHudhud(Intent intent) {
        if (!Home.hudhudMode(this) || intent == null || intent.getBooleanExtra(EXTRA_STAY, false)) return;
        if (!OverlayService.canDraw(this)) return; // بلا إذن العرض فوق التطبيقات نبقى في شاشة المشغّل
        OverlayService.sync(this);
        if (!OverlayService.openHudhud(this)) Toast.makeText(this, R.string.hudhud_missing, Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == REQ_LOCATION && GpsSpeed.hasPermission(this)) gps.start();
    }

    // ───────── الحركة ─────────

    /** دخول متتابع: البطاقة العلوية، ثم الشريط، ثم البطاقات من الأسفل، ثم البار */
    private void playIntro() {
        float rise = 28 * getResources().getDisplayMetrics().density;
        View[] order = {
                findViewById(R.id.top_card), findViewById(R.id.rail),
                cardBrave, cardMaps, cardHudhud, dock
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
        prayerItem = null;
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
                    prayerItem = item;
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

    /**
     * مؤقت الإقامة: يظهر من الأذان حتى الإقامة ويعدّ كل ثانية، ذهبي في آخر دقيقة،
     * ثم «أقيمت الصلاة» دقيقة واحدة ويختفي. لا يعمل مؤقت الثواني إلا وهو ظاهر.
     */
    private void updateIqama() {
        ui.removeCallbacks(iqamaTick);
        if (todayTimes == null || prayerItem == null || !Home.shown(this, Home.IQAMA)) { hideBubble(); return; }
        long t = System.currentTimeMillis();
        long[] w = AlertPlan.iqamaWindow(t, todayTimes, PrayerTimes.IQAMA);
        if (w != null) {
            long left = w[1];
            if (left > 0) {
                long sec = (left + 999) / 1000;
                bubble.set(getString(R.string.iq_in), mmss(sec), sec < 60);
            } else {
                bubble.set(getString(R.string.iq_done), "", true);
            }
            showBubble();
            ui.postDelayed(iqamaTick, 1000 - t % 1000 + 5);
            return;
        }
        hideBubble();
    }

    private java.text.NumberFormat twoDigits;

    private String mmss(long sec) {
        if (twoDigits == null) { // يُنشأ مرة واحدة (اللغة ثابتة طوال عمر الشاشة)
            twoDigits = java.text.NumberFormat.getInstance(Lang.isArabic(this) ? new Locale("ar", "SA") : Locale.ENGLISH);
            twoDigits.setMinimumIntegerDigits(2);
            twoDigits.setGroupingUsed(false);
        }
        return twoDigits.format(sec / 60) + ":" + twoDigits.format(sec % 60);
    }

    private void showBubble() {
        placeBubble();
        if (bubble.getVisibility() == View.VISIBLE) return;
        float dp = getResources().getDisplayMetrics().density;
        bubble.setAlpha(0f);
        bubble.setTranslationX(-16 * dp);
        bubble.setVisibility(View.VISIBLE);
        bubble.animate().alpha(1f).translationX(0f).setDuration(380)
                .setInterpolator(new DecelerateInterpolator(2f)).start();
    }

    private void hideBubble() {
        if (bubble.getVisibility() != View.VISIBLE) return;
        bubble.animate().alpha(0f).setDuration(250).withEndAction(() -> bubble.setVisibility(View.GONE)).start();
    }

    /** على حافة الشريط اليسار، في مستوى أداة الصلاة */
    private void placeBubble() {
        if (prayerItem == null) return;
        if (prayerItem.getHeight() == 0) { // لم يُرسم الشريط بعد: نعيد المحاولة بعد أول رسم
            if (prayerItem.isAttachedToWindow()) prayerItem.post(this::placeBubble);
            return;
        }
        View root = findViewById(R.id.root), rail = findViewById(R.id.rail);
        int[] r = new int[2], a = new int[2], rl = new int[2];
        root.getLocationInWindow(r);
        prayerItem.getLocationInWindow(a);
        rail.getLocationInWindow(rl);
        float dp = getResources().getDisplayMetrics().density;
        android.widget.FrameLayout.LayoutParams lp = (android.widget.FrameLayout.LayoutParams) bubble.getLayoutParams();
        int left = rl[0] - r[0] + rail.getWidth() + (int) (4 * dp);
        int top = a[1] - r[1] + prayerItem.getHeight() / 2 - (int) (17 * dp); // نصف ارتفاع الفقاعة
        if (lp.leftMargin != left || lp.topMargin != top) {
            lp.leftMargin = left;
            lp.topMargin = top;
            bubble.setLayoutParams(lp);
        }
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
        // بين الأذان والإقامة: اسم الصلاة مع الوقت الباقي على الإقامة بالأخضر
        for (int k : PrayerTimes.PRAYERS) {
            long iq = todayTimes[k] + PrayerTimes.IQAMA[k] * 60_000L;
            if (t >= todayTimes[k] && t < iq) {
                prayerName.setText(PrayerActivity.NAMES[k]);
                prayerTime.setText(Lang.left(this, iq - t));
                prayerTime.setTextColor(getColor(R.color.green));
                return;
            }
        }
        prayerTime.setTextColor(getColor(R.color.orange));
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

    /** يفتح Brave الحقيقي في نافذة صغيرة يسار الشاشة (أو بجانب التطبيق الحالي إن لم تدعم الشاشة النوافذ العائمة) */
    private void openBrave() { BraveWindow.open(this); }

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
