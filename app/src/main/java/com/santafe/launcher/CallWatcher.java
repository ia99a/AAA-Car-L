package com.santafe.launcher;

import android.app.Notification;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.view.ContextThemeWrapper;
import android.view.WindowManager;

import java.util.Locale;

/**
 * يعرف بالمكالمات من إشعار تطبيق الهاتف ليعرض كبسولة المكالمة أسفل الشاشة.
 * يحتاج إذن «الوصول للإشعارات» من المستخدم. يتجاهل كل إشعار ليس مكالمة، ولا يحفظ أي شيء،
 * والمشغّل لا يملك إذن الإنترنت أصلًا فلا يخرج شيء من الجهاز.
 * إن كانت خدمة «تحكم AAA Car L» مفعّلة: الكبسولة ترسمها هي، وتُخفى شاشة المكالمة الأصلية
 * ما دامت أزرار الكبسولة تكفي للتحكم بالمكالمة (رد ورفض للواردة، إنهاء للجارية).
 */
public class CallWatcher extends NotificationListenerService {

    private static CallWatcher instance;
    private static CallBar.Info current;   // المكالمة المعروضة الآن
    private static String userOpenedKey;   // فتحت شاشة المكالمة الأصلية بنفسك: لا نخفيها في هذه المكالمة
    private static long lastHide;
    private CallBar ownBar;                // كبسولة بإذن «الظهور فوق التطبيقات» (حين لا تعمل خدمة التحكم)
    private CallBar shownOn;

    /** بعد تغيير الإعداد أو تشغيل/إيقاف خدمة التحكم: نعيد التقييم فورًا */
    static void refresh() {
        if (instance != null) instance.scan();
    }

    static boolean connected() { return instance != null; }

    static void userOpened(String key) { userOpenedKey = key; }

    @Override
    public void onListenerConnected() {
        instance = this;
        scan();
    }

    @Override
    public void onListenerDisconnected() {
        hide();
        if (instance == this) instance = null;
    }

    @Override
    public void onDestroy() {
        hide();
        if (instance == this) instance = null;
        super.onDestroy();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        boolean call = isCall(sbn);
        note(sbn, call);
        if (call || current != null) scan();
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (current != null) scan();
    }

    /** أين نرسم: خدمة التحكم أولًا، ثم إذن الظهور فوق التطبيقات، وإلا لا شيء */
    private CallBar host() {
        CarAccess a = CarAccess.get();
        if (a != null) return a.callBar();
        if (!OverlayService.canDraw(this)) return null;
        if (ownBar == null) ownBar = new CallBar(new ContextThemeWrapper(Lang.wrap(this), R.style.CarTheme),
                (WindowManager) getSystemService(WINDOW_SERVICE),
                Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE);
        return ownBar;
    }

    /** أحدث مكالمة نشطة تُعرض، وإن لم توجد تُخفى الكبسولة */
    private void scan() {
        CallBar bar = Popups.call(this) ? host() : null;
        if (bar == null) { hide(); return; }
        StatusBarNotification best = null;
        try {
            StatusBarNotification[] all = getActiveNotifications();
            if (all != null) for (StatusBarNotification s : all) {
                if (!isCall(s)) continue;
                if (best == null || s.getPostTime() > best.getPostTime()) best = s;
            }
        } catch (Exception ignored) { } // الإذن سُحب للتو
        if (best == null) { hide(); userOpenedKey = null; return; }
        if (shownOn != null && shownOn != bar) shownOn.hide(); // تغيّر مكان الرسم
        CallBar.Info info = CallBar.Info.from(best.getKey(), best.getNotification());
        info.pkg = best.getPackageName();
        boolean isNew = current == null || !current.key.equals(info.key) || current.incoming != info.incoming;
        current = info;
        bar.show(info);
        shownOn = bar;
        // شاشة المكالمة الأصلية فُتحت قبل وصول الإشعار: نخفيها الآن
        CarAccess a = CarAccess.get();
        if (isNew && a != null && a.openedRecently(info.pkg)) maybeHideScreen(a, info.pkg);
    }

    private void hide() {
        current = null;
        if (shownOn != null) shownOn.hide();
        shownOn = null;
    }

    /** نافذة تطبيق فُتحت (من خدمة التحكم): إن كانت شاشة المكالمة الأصلية نخفيها */
    static void onWindowOpened(CarAccess a, String pkg) {
        if (current != null && pkg.equals(current.pkg)) maybeHideScreen(a, pkg);
    }

    private static void maybeHideScreen(CarAccess a, String pkg) {
        CallBar.Info c = current;
        if (c == null || c.key.equals(userOpenedKey)) return;
        // لا نخفيها إلا إن كانت الكبسولة تكفي: رد ورفض للواردة، وإنهاء للجارية
        boolean enough = c.incoming ? c.has(CallBar.ANSWER) && c.has(CallBar.END) : c.has(CallBar.END);
        if (!enough) return;
        long now = SystemClock.uptimeMillis();
        if (now - lastHide < 1500) return;
        lastHide = now;
        new Handler(Looper.getMainLooper()).postDelayed(() -> a.hideCallScreen(pkg), 150);
    }

    // ───────── التعرّف على المكالمة ─────────

    private static boolean phoneApp(String pkg) {
        String p = pkg.toLowerCase(Locale.ROOT);
        return p.contains("dialer") || p.contains("incallui") || p.contains("telecom") || p.contains("phone")
                || p.contains("bluetooth") || p.contains(".bt") || p.contains("btphone") || p.contains("contacts");
    }

    /** هل هذا إشعار مكالمة؟ تصنيف «مكالمة»، أو CallStyle، أو تطبيق هاتف/بلوتوث بإشعار مستمر فيه زر رد أو إنهاء */
    private boolean isCall(StatusBarNotification s) {
        if (s == null || getPackageName().equals(s.getPackageName())) return false;
        Notification n = s.getNotification();
        if (n == null) return false;
        if (Notification.CATEGORY_CALL.equals(n.category)) return true;
        if (n.extras != null && n.extras.getInt("android.callType", 0) > 0) return true;
        if (!phoneApp(s.getPackageName()) || n.actions == null) return false;
        if ((n.flags & Notification.FLAG_ONGOING_EVENT) == 0 && n.fullScreenIntent == null) return false;
        for (Notification.Action a : n.actions) if (a != null && CallBar.kind(a.title) != CallBar.OTHER) return true;
        return false;
    }

    /** للفحص في الإعدادات: الحزمة والتصنيف وأسماء الأزرار فقط، لإشعارات المكالمات وتطبيقات الهاتف */
    private void note(StatusBarNotification s, boolean call) {
        Notification n = s.getNotification();
        if (n == null || (!call && !phoneApp(s.getPackageName()))) return;
        StringBuilder b = new StringBuilder();
        if (n.actions != null) for (Notification.Action a : n.actions) {
            if (a == null || a.title == null) continue;
            if (b.length() > 0) b.append("، ");
            b.append(a.title);
        }
        Diag.call(s.getPackageName(), n.category == null ? "-" : n.category, b.length() == 0 ? "-" : b.toString(), call);
    }

    // ───────── تجربة من الإعدادات ─────────

    /** يعرض كبسولة مكالمة واردة تجريبية ٦ ثوانٍ (أزرارها لا تفعل شيئًا) */
    static boolean demo(Context c) {
        CarAccess a = CarAccess.get();
        CallBar bar;
        if (a != null) bar = a.callBar();
        else if (OverlayService.canDraw(c)) bar = new CallBar(new ContextThemeWrapper(Lang.wrap(c), R.style.CarTheme),
                (WindowManager) c.getApplicationContext().getSystemService(WINDOW_SERVICE),
                Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE);
        else return false;
        if (current != null) return true; // مكالمة حقيقية ظاهرة
        CallBar.Info i = new CallBar.Info();
        i.key = "demo";
        i.name = Lang.wrap(c).getString(R.string.call_demo_name);
        i.detail = "05x xxx xxxx";
        i.incoming = true;
        i.buttons.add(new CallBar.Btn(CallBar.END, null, null, null));
        i.buttons.add(new CallBar.Btn(CallBar.ANSWER, null, null, null));
        bar.show(i);
        new Handler(Looper.getMainLooper()).postDelayed(() -> { if (current == null) bar.hide(); }, 6000);
        return true;
    }
}
