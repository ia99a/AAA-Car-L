package com.santafe.launcher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * وضع هدهد: شريط السائق يطفو فوق تطبيق هدهد (أو أي تطبيق) على يسار الشاشة،
 * ومعه فقاعة مؤقت الإقامة. يحتاج إذن «الظهور فوق التطبيقات»، ويعمل كخدمة أمامية بإشعار هادئ حتى لا يوقفه النظام.
 * يختفي تلقائيًا حين تكون إحدى شاشات المشغّل نفسه ظاهرة (لها شريطها الخاص).
 */
public class OverlayService extends Service {

    private static final String CHANNEL = "overlay_v1";
    private static final int NOTIF_ID = 78;
    private static OverlayService instance;
    private static int suppressed; // عدد شاشات المشغّل الظاهرة الآن

    private WindowManager wm;
    private Context ui; // سياق بلغة الواجهة وثيمها
    private LinearLayout rail;
    private WindowManager.LayoutParams railLp;
    private IqamaBubble bubble;
    private WindowManager.LayoutParams bubbleLp;
    private View prayerItem;
    private TextView prayerName, prayerTime;
    private final PrayerRail prayer = new PrayerRail();
    private final Handler h = new Handler(Looper.getMainLooper());
    private java.text.NumberFormat twoDigits;
    private final Runnable tick = new Runnable() {
        @Override public void run() { refresh(); h.postDelayed(this, nextDelay()); }
    };

    // ───────── التحكم من الشاشات ─────────

    static boolean canDraw(Context c) {
        return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(c);
    }

    /** يشغّل الشريط العائم إن كان وضع هدهد مفعّلًا والإذن ممنوحًا، ويوقفه غير ذلك */
    static void sync(Context c) {
        Context app = c.getApplicationContext();
        Intent i = new Intent(app, OverlayService.class);
        if (Home.hudhudMode(app) && canDraw(app)) {
            try {
                if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(i); else app.startService(i);
            } catch (Exception ignored) { } // بعض الأنظمة ترفض البدء من الخلفية؛ نعيد المحاولة عند العودة للرئيسية
        } else {
            app.stopService(i);
        }
    }

    /** شاشات المشغّل تستدعيها: true في onResume و false في onPause */
    static void suppress(boolean on) {
        suppressed = Math.max(0, suppressed + (on ? 1 : -1));
        if (instance != null) instance.applyVisibility();
    }

    static boolean running() { return instance != null; }

    // ───────── دورة الحياة ─────────

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        startForegroundCompat();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForegroundCompat(); // مطلوب بعد كل startForegroundService
        if (!Home.hudhudMode(this) || !canDraw(this)) { stopSelf(); return START_NOT_STICKY; }
        if (!signature().equals(builtSig)) rebuild(); // تغيّرت الأدوات أو اللغة أو الشفافية فقط
        return START_STICKY;
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        rebuild(); // تغيّر المقاس أو الوضع الليلي
    }

    @Override
    public void onDestroy() {
        h.removeCallbacks(tick);
        removeViews();
        instance = null;
        super.onDestroy();
    }

    private void startForegroundCompat() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        Context l = Lang.wrap(this);
        if (Build.VERSION.SDK_INT >= 26 && nm != null && nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, l.getString(R.string.overlay_channel), NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false);
            ch.setSound(null, null);
            nm.createNotificationChannel(ch);
        }
        PendingIntent open = PendingIntent.getActivity(this, 3,
                new Intent(this, SettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        Notification n = b.setSmallIcon(R.drawable.ic_dock)
                .setContentTitle(l.getString(R.string.overlay_title))
                .setContentText(l.getString(R.string.overlay_text))
                .setContentIntent(open)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 34)
            startForeground(NOTIF_ID, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else
            startForeground(NOTIF_ID, n);
    }

    // ───────── الشريط العائم ─────────

    private int overlayType() {
        return Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
    }

    private String builtSig = "";

    /** ما يغيّر شكل الشريط العائم */
    private String signature() {
        return Tools.enabled(this) + "|" + Lang.get(this) + "|" + Looks.opacity(this) + "|" + Home.shown(this, Home.IQAMA)
                + "|" + getResources().getDisplayMetrics().heightPixels;
    }

    private void rebuild() {
        removeViews();
        build();
    }

    private void removeViews() {
        h.removeCallbacks(tick);
        try { if (rail != null) wm.removeView(rail); } catch (Exception ignored) { }
        try { if (bubble != null) wm.removeView(bubble); } catch (Exception ignored) { }
        rail = null;
        bubble = null;
        prayerItem = null;
        prayerName = prayerTime = null;
    }

    private void build() {
        builtSig = signature();
        ui = new ContextThemeWrapper(Lang.wrap(this), R.style.CarTheme);
        float dp = getResources().getDisplayMetrics().density;
        int screenH = getResources().getDisplayMetrics().heightPixels;
        twoDigits = null;

        rail = new LinearLayout(ui);
        rail.setOrientation(LinearLayout.VERTICAL);
        rail.setBackgroundResource(R.drawable.rail_bg);
        rail.getBackground().mutate().setAlpha(Math.round(Looks.opacity(this) * 2.55f));
        int p = (int) (3 * dp);
        rail.setPadding(p, p, p, p);
        rail.setElevation(6 * dp);

        LayoutInflater inf = LayoutInflater.from(ui);
        for (String k : Tools.enabled(this)) {
            View item = inf.inflate(R.layout.item_rail, rail, false);
            ((ImageView) item.findViewById(R.id.rail_icon)).setImageResource(Tools.icon(k));
            item.setContentDescription(ui.getString(Tools.label(k)));
            switch (k) {
                case Tools.LOCATION: item.setOnClickListener(v -> openHudhud(this)); break;
                case Tools.PRAYER:
                    prayerItem = item;
                    prayerName = item.findViewById(R.id.rail_label);
                    prayerTime = item.findViewById(R.id.rail_extra);
                    prayerName.setVisibility(View.VISIBLE);
                    prayerTime.setVisibility(View.VISIBLE);
                    item.setOnClickListener(v -> startScreen(PrayerActivity.class, false));
                    break;
                case Tools.APPS: item.setOnClickListener(v -> startScreen(AppsActivity.class, false)); break;
                default: item.setOnClickListener(v -> BraveWindow.open(this));
            }
            rail.addView(item);
        }
        View spacer = new View(ui);
        rail.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1f));
        // الرئيسية (شاشة المشغّل نفسها) والإعدادات في الأسفل
        rail.addView(bottomItem(inf, R.drawable.ic_home, R.string.home, v -> startScreen(MainActivity.class, true)));
        rail.addView(bottomItem(inf, R.drawable.ic_settings, R.string.settings_title, v -> startScreen(SettingsActivity.class, false)));

        railLp = new WindowManager.LayoutParams((int) (52 * dp), screenH - (int) (24 * dp), overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        railLp.gravity = Gravity.TOP | Gravity.LEFT;
        railLp.x = (int) (12 * dp);
        railLp.y = (int) (12 * dp);
        try { wm.addView(rail, railLp); } catch (Exception e) { stopSelf(); return; }

        bubble = new IqamaBubble(ui);
        bubble.setLayoutDirection(Lang.isArabic(this) ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        bubble.setBackgroundOpacity(Math.max(Math.round(Looks.opacity(this) * 2.55f), 140));
        bubble.setOnClickListener(v -> startScreen(PrayerActivity.class, false));
        bubbleLp = new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType(), WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        bubbleLp.gravity = Gravity.TOP | Gravity.LEFT;
        bubbleLp.x = railLp.x + railLp.width + (int) (4 * dp);
        bubble.setVisibility(View.GONE);
        try { wm.addView(bubble, bubbleLp); } catch (Exception ignored) { bubble = null; }

        prayer.invalidate();
        applyVisibility();
        h.post(tick);
    }

    private View bottomItem(LayoutInflater inf, int icon, int label, View.OnClickListener l) {
        View item = inf.inflate(R.layout.item_rail, rail, false);
        ImageView iv = item.findViewById(R.id.rail_icon);
        iv.setImageResource(icon);
        iv.setColorFilter(getColor(R.color.ink_soft));
        item.setContentDescription(ui.getString(label));
        item.setOnClickListener(l);
        return item;
    }

    private void applyVisibility() {
        int v = suppressed > 0 ? View.GONE : View.VISIBLE;
        if (rail != null) rail.setVisibility(v);
        if (bubble != null && v == View.GONE) bubble.setVisibility(View.GONE);
        if (v == View.VISIBLE) refresh();
    }

    /** الصلاة كل ٥ ثوانٍ، ومؤقت الإقامة كل ثانية وهو ظاهر */
    private long nextDelay() {
        return bubble != null && bubble.getVisibility() == View.VISIBLE ? 1000 - System.currentTimeMillis() % 1000 + 5 : 5000;
    }

    private void refresh() {
        if (rail == null || suppressed > 0) return;
        prayer.bind(ui, prayerName, prayerTime);
        if (bubble == null) return;
        long[] w = prayerItem == null || !Home.shown(this, Home.IQAMA) ? null
                : AlertPlan.iqamaWindow(System.currentTimeMillis(), prayer.today(this), PrayerTimes.IQAMA);
        if (w == null) { if (bubble.getVisibility() != View.GONE) bubble.setVisibility(View.GONE); return; }
        if (w[1] > 0) {
            long sec = (w[1] + 999) / 1000;
            bubble.set(ui.getString(R.string.iq_in), mmss(sec), sec < 60);
        } else {
            bubble.set(ui.getString(R.string.iq_done), "", true);
        }
        // مستوى أداة الصلاة في الشريط
        int top = railLp.y + prayerItem.getTop() + prayerItem.getHeight() / 2
                - (int) (17 * getResources().getDisplayMetrics().density); // نصف ارتفاع الفقاعة
        if (prayerItem.getHeight() > 0 && bubbleLp.y != top) {
            bubbleLp.y = top;
            try { wm.updateViewLayout(bubble, bubbleLp); } catch (Exception ignored) { }
        }
        if (bubble.getVisibility() != View.VISIBLE) bubble.setVisibility(View.VISIBLE);
    }

    private String mmss(long sec) {
        if (twoDigits == null) {
            twoDigits = java.text.NumberFormat.getInstance(Lang.isArabic(this) ? new java.util.Locale("ar", "SA") : java.util.Locale.ENGLISH);
            twoDigits.setMinimumIntegerDigits(2);
            twoDigits.setGroupingUsed(false);
        }
        return twoDigits.format(sec / 60) + ":" + twoDigits.format(sec % 60);
    }

    private void startScreen(Class<?> cls, boolean stay) {
        Intent i = new Intent(this, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (stay) i.putExtra(MainActivity.EXTRA_STAY, true);
        try { startActivity(i); } catch (Exception ignored) { }
    }

    // ───────── هدهد ─────────

    /** حزمة هدهد المثبتة (يُعرف باسم الحزمة فقط، ويُفضَّل المثبّت من متجر قوقل) */
    static String hudhudPackage(Context c) {
        android.content.pm.PackageManager pm = c.getPackageManager();
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        String found = null;
        for (android.content.pm.ResolveInfo r : pm.queryIntentActivities(i, 0)) {
            String pkg = r.activityInfo.packageName;
            if (pkg.equals(c.getPackageName()) || !pkg.toLowerCase(java.util.Locale.ROOT).contains("hudhud")) continue;
            if (Safe.fromPlayStore(c, pkg)) return pkg;
            if (found == null) found = pkg;
        }
        return found;
    }

    /** يفتح هدهد بملء الشاشة؛ وإن لم يكن مثبتًا يرجع false */
    static boolean openHudhud(Context c) {
        String pkg = hudhudPackage(c);
        Intent launch = pkg == null ? null : c.getPackageManager().getLaunchIntentForPackage(pkg);
        if (launch == null) return false;
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try { c.startActivity(launch); return true; } catch (Exception e) { return false; }
    }
}
