package com.santafe.launcher;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.media.AudioManager;
import android.os.SystemClock;
import android.view.ContextThemeWrapper;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

/**
 * «تحكم AAA Car L» (خدمة إمكانية وصول يفعّلها المستخدم بنفسه):
 *  ١) تستلم أزرار الصوت قبل أندرويد، فتغيّر الصوت بلا نافذة أندرويد وتعرض كبسولتنا بدلها.
 *  ٢) تعرف أن شاشة المكالمة الأصلية فُتحت، فترجع للرئيسية ما دامت كبسولة المكالمة تتحكم بالمكالمة كاملة.
 *  ٣) ترسم الكبسولتين بنافذة إمكانية الوصول، فلا تحتاج إذن «الظهور فوق التطبيقات».
 *  ٤) في الشاشات التي لا يضع تطبيق البلوتوث فيها إشعار مكالمة: تقرأ شاشة المكالمة الأصلية فقط
 *     (تطبيقات الهاتف والبلوتوث) وتغطّيها بتصميمنا، وأزرارنا تضغط أزرارها (CallScreen).
 * لا تقرأ محتوى أي تطبيق آخر، ولا تحفظ شيئًا.
 */
public class CarAccess extends AccessibilityService {

    private static CarAccess instance;
    private Context ui;
    private WindowManager wm;
    private AudioManager am;
    private VolumeHud volume;
    private CallBar bar;
    private CallScreen screen;
    private boolean keysChecked; // تأكدنا أن أزرار الصوت تغيّر صوت أندرويد فعلًا

    static boolean running() { return instance != null; }

    static CarAccess get() { return instance; }

    @Override
    protected void onServiceConnected() {
        instance = this;
        ui = new ContextThemeWrapper(Lang.wrap(this), R.style.CarTheme);
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        am = (AudioManager) getSystemService(AUDIO_SERVICE);
        applySettings();
        screen = new CallScreen(this, ui, wm);
        OverlayService.sync(this); // الخدمة العائمة لم تعد تحتاج كبسولة صوت خاصة بها
        CallWatcher.refresh();
    }

    /** بعد تغيير الإعدادات */
    static void settingsChanged() {
        if (instance != null) instance.applySettings();
    }

    private void applySettings() {
        if (Popups.volume(this) && volume == null) volume = new VolumeHud(ui, wm, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY);
        if (!Popups.volume(this) && volume != null) { volume.release(); volume = null; }
    }

    VolumeHud volume() { return volume; }

    CallBar callBar() {
        if (bar == null) bar = new CallBar(ui, wm, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY);
        return bar;
    }

    private void shutdown() {
        if (volume != null) volume.release();
        volume = null;
        if (bar != null) bar.hide();
        bar = null;
        if (screen != null) screen.release();
        screen = null;
        if (instance == this) instance = null;
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        shutdown();
        OverlayService.sync(this);
        CallWatcher.refresh();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        shutdown();
        super.onDestroy();
    }

    @Override public void onInterrupt() { }

    // ───────── أزرار الصوت ─────────

    @Override
    protected boolean onKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        if (code != KeyEvent.KEYCODE_VOLUME_UP && code != KeyEvent.KEYCODE_VOLUME_DOWN && code != KeyEvent.KEYCODE_VOLUME_MUTE)
            return false;
        if (e.getAction() == KeyEvent.ACTION_DOWN) Diag.volumeKeys++;
        if (volume == null || am == null || Diag.keysIgnored || am.isVolumeFixed()) return false;
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true; // رفع الإصبع: نكتمه مع ضغطته
        int stream = activeStream();
        int before = am.getStreamVolume(stream), max = am.getStreamMaxVolume(stream);
        int dir = code == KeyEvent.KEYCODE_VOLUME_UP ? AudioManager.ADJUST_RAISE
                : code == KeyEvent.KEYCODE_VOLUME_DOWN ? AudioManager.ADJUST_LOWER : AudioManager.ADJUST_TOGGLE_MUTE;
        try {
            am.adjustStreamVolume(stream, dir, 0); // بدون FLAG_SHOW_UI: لا تظهر نافذة أندرويد
        } catch (SecurityException ex) { // «عدم الإزعاج» يمنع التغيير: نتركه للنظام
            return false;
        }
        // أول ضغطة: إن لم يتغيّر صوت أندرويد (الشاشة تتحكم بالصوت من معالجها الخاص) نترك الأزرار للنظام دائمًا
        if (!keysChecked && dir != AudioManager.ADJUST_TOGGLE_MUTE) {
            int after = am.getStreamVolume(stream);
            boolean atEdge = (dir == AudioManager.ADJUST_RAISE && before >= max) || (dir == AudioManager.ADJUST_LOWER && before <= 0);
            if (after == before && !atEdge) { Diag.keysIgnored = true; return false; }
            if (after != before) keysChecked = true;
        }
        Diag.volume("keys", stream);
        volume.show(stream);
        return true;
    }

    /** الصوت الذي يغيّره الزر الآن: المكالمة أثناءها، والرنين وقت الرنين، والوسائط غير ذلك */
    private int activeStream() {
        int mode = am.getMode();
        if (mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION) return AudioManager.STREAM_VOICE_CALL;
        if (mode == AudioManager.MODE_RINGTONE) return AudioManager.STREAM_RING;
        return AudioManager.STREAM_MUSIC;
    }

    // ───────── شاشة المكالمة الأصلية ─────────

    private String lastPkg;
    private long lastPkgAt;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e == null) return;
        String evPkg = e.getPackageName() == null ? null : e.getPackageName().toString();
        if (screen != null && !getPackageName().equals(evPkg)) screen.poke(evPkg); // شاشة مكالمة بلا إشعار؟
        if (e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || evPkg == null) return;
        String pkg = evPkg;
        if (pkg.equals(getPackageName())) return;
        Diag.window(pkg);
        lastPkg = pkg;
        lastPkgAt = SystemClock.uptimeMillis();
        CallWatcher.onWindowOpened(this, pkg);
    }

    /** هل فُتحت نافذة هذا التطبيق للتو؟ (حين يصل إشعار المكالمة بعد شاشتها) */
    boolean openedRecently(String pkg) {
        return pkg != null && pkg.equals(lastPkg) && SystemClock.uptimeMillis() - lastPkgAt < 4000;
    }

    /** يخفي شاشة المكالمة الأصلية بالرجوع للرئيسية (والرئيسية في وضع هدهد تفتح هدهد) */
    void hideCallScreen(String pkg) {
        Diag.hiddenAt = System.currentTimeMillis();
        Diag.hiddenPkg = pkg;
        performGlobalAction(GLOBAL_ACTION_HOME);
    }
}
