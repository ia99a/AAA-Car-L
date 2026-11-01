package com.santafe.launcher;

import android.app.Notification;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.view.ContextThemeWrapper;

import java.util.Locale;

/**
 * يعرف بالمكالمات من إشعار تطبيق الهاتف ليعرض كبسولة المكالمة أسفل الشاشة.
 * يحتاج إذن «الوصول للإشعارات» من المستخدم. يتجاهل كل إشعار ليس مكالمة، ولا يحفظ أي شيء،
 * والمشغّل لا يملك إذن الإنترنت أصلًا فلا يخرج شيء من الجهاز.
 */
public class CallWatcher extends NotificationListenerService {

    private static CallWatcher instance;
    private CallBar bar;

    /** بعد تغيير الإعداد: نعيد التقييم فورًا */
    static void refresh() {
        if (instance != null) instance.scan();
    }

    @Override
    public void onListenerConnected() {
        instance = this;
        scan();
    }

    @Override
    public void onListenerDisconnected() {
        hide();
        instance = null;
    }

    @Override
    public void onDestroy() {
        hide();
        if (instance == this) instance = null;
        super.onDestroy();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (isCall(sbn)) scan();
        else if (bar != null && bar.showing()) scan(); // ربما تحوّل إشعار المكالمة لشيء آخر
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (bar != null && bar.showing()) scan();
    }

    /** أحدث مكالمة نشطة تُعرض، وإن لم توجد تُخفى الكبسولة */
    private void scan() {
        if (!Popups.call(this) || !OverlayService.canDraw(this)) { hide(); return; }
        StatusBarNotification best = null;
        try {
            StatusBarNotification[] all = getActiveNotifications();
            if (all != null) for (StatusBarNotification s : all) {
                if (!isCall(s)) continue;
                if (best == null || s.getPostTime() > best.getPostTime()) best = s;
            }
        } catch (Exception ignored) { } // الإذن سُحب للتو
        if (best == null) { hide(); return; }
        if (bar == null) bar = new CallBar(new ContextThemeWrapper(Lang.wrap(this), R.style.CarTheme));
        bar.show(CallBar.Info.from(best.getKey(), best.getNotification()));
    }

    private void hide() {
        if (bar != null) bar.hide();
    }

    /** هل هذا إشعار مكالمة؟ تصنيف «مكالمة»، أو CallStyle، أو تطبيق هاتف/بلوتوث بإشعار مستمر فيه زر رد أو إنهاء */
    private boolean isCall(StatusBarNotification s) {
        if (s == null || getPackageName().equals(s.getPackageName())) return false;
        Notification n = s.getNotification();
        if (n == null) return false;
        if (Notification.CATEGORY_CALL.equals(n.category)) return true;
        if (n.extras != null && n.extras.getInt("android.callType", 0) > 0) return true;
        String p = s.getPackageName().toLowerCase(Locale.ROOT);
        boolean phoneApp = p.contains("dialer") || p.contains("incallui") || p.contains("telecom") || p.contains("phone")
                || p.contains("bluetooth") || p.contains(".bt") || p.contains("btphone");
        if (!phoneApp || (n.flags & Notification.FLAG_ONGOING_EVENT) == 0 || n.actions == null) return false;
        for (Notification.Action a : n.actions) if (a != null && CallBar.kind(a.title) != CallBar.OTHER) return true;
        return false;
    }

    static boolean connected() { return instance != null; }
}
