package com.santafe.launcher;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * لشاشات السيارات التي لا يضع تطبيق البلوتوث فيها إشعار مكالمة:
 * نقرأ شاشة المكالمة الأصلية (تطبيقات الهاتف والبلوتوث فقط)، فإن وجدنا زر الرد أو الإنهاء
 * نغطّيها بتصميمنا: النقش وكبسولة المكالمة، وأزرار الكبسولة تضغط أزرار الشاشة الأصلية تحتها.
 * إن لم نجد الأزرار لا نغطّي شيئًا، فتبقى الشاشة الأصلية تعمل كما هي.
 * لا نقرأ نوافذ أي تطبيق آخر، ولا نحفظ شيئًا.
 */
final class CallScreen {

    private static final Pattern TIME = Pattern.compile("^\\d{1,2}:\\d{2}(:\\d{2})?$");
    private static final int MAX_NODES = 500;

    private final AccessibilityService svc;
    private final Context ui;
    private final WindowManager wm;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final float dp;
    private FrameLayout cover;
    private CallBar shape; // نستعير بناء الكبسولة من كبسولة المكالمات
    private String coverSig;
    private String pkg;      // تطبيق المكالمة المغطّى
    private final Rect bounds = new Rect();
    private boolean scheduled;
    private String ongoingPkg;   // مكالمة جارية: نبدأ عدّاد المدة مرة واحدة من وقت الشاشة الأصلية
    private long ongoingSince;

    CallScreen(AccessibilityService svc, Context ui, WindowManager wm) {
        this.svc = svc;
        this.ui = ui;
        this.wm = wm;
        this.dp = ui.getResources().getDisplayMetrics().density;
    }

    /** حدث نافذة: نعيد الفحص بعد لحظة (تتجمع الأحداث المتلاحقة في فحص واحد) */
    void poke(String eventPkg) {
        if (!Popups.call(svc)) { hide(); return; }
        boolean relevant = cover != null || (eventPkg != null && CallWatcher.phoneApp(eventPkg));
        if (!relevant || scheduled) return;
        scheduled = true;
        h.postDelayed(() -> { scheduled = false; scan(); }, 250);
    }

    void release() { hide(); h.removeCallbacksAndMessages(null); }

    // ───────── قراءة الشاشة الأصلية ─────────

    /** ما وجدناه في شاشة مكالمة واحدة */
    private static final class Found {
        AccessibilityNodeInfo answer, end, mute;
        String name, timer;
        boolean incomingWord;
        final Rect bounds = new Rect();
        String pkg;
    }

    private void scan() {
        if (CallWatcher.hasNotificationCall()) { hide(); return; } // إشعار المكالمة يكفي (يعمل بالطريقة الأخرى)
        Found best = null;
        List<AccessibilityWindowInfo> ws;
        try { ws = svc.getWindows(); } catch (Exception e) { ws = null; }
        if (ws != null) for (AccessibilityWindowInfo w : ws) {
            if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION && w.getType() != AccessibilityWindowInfo.TYPE_SYSTEM) continue;
            AccessibilityNodeInfo root = w.getRoot();
            if (root == null || root.getPackageName() == null) continue;
            String p = root.getPackageName().toString();
            if (p.equals(svc.getPackageName()) || !CallWatcher.phoneApp(p)) continue; // تطبيقات الهاتف والبلوتوث فقط
            Found f = read(root);
            if (f.answer == null && f.end == null) continue;
            f.pkg = p;
            w.getBoundsInScreen(f.bounds);
            if (best == null || (f.answer != null && best.answer == null)) best = f;
        }
        if (best == null) { hide(); return; }
        Diag.window(best.pkg + "  ← " + (best.answer != null ? "answer" : "") + (best.end != null ? " end" : ""));
        show(best);
    }

    private Found read(AccessibilityNodeInfo root) {
        Found f = new Found();
        List<AccessibilityNodeInfo> stack = new ArrayList<>();
        stack.add(root);
        List<String> texts = new ArrayList<>();
        int seen = 0;
        while (!stack.isEmpty() && seen++ < MAX_NODES) {
            AccessibilityNodeInfo n = stack.remove(stack.size() - 1);
            if (n == null || !n.isVisibleToUser()) continue;
            CharSequence t = n.getText(), d = n.getContentDescription();
            String id = n.getViewIdResourceName();
            String label = t != null && t.length() > 0 ? t.toString() : d != null ? d.toString() : "";
            int k = kind(label, id);
            if (k != CallBar.OTHER) {
                AccessibilityNodeInfo c = clickable(n);
                if (c != null) {
                    if (k == CallBar.ANSWER && f.answer == null) f.answer = c;
                    if (k == CallBar.END && f.end == null) f.end = c;
                }
            } else if (isMute(label, id) && f.mute == null) {
                f.mute = clickable(n);
            } else if (t != null) {
                String s = t.toString().trim();
                if (TIME.matcher(s).matches()) { if (f.timer == null) f.timer = s; }
                else if (incomingWord(s)) f.incomingWord = true;
                else if (s.length() >= 2 && s.length() <= 40) texts.add(s);
            }
            for (int i = n.getChildCount() - 1; i >= 0; i--) stack.add(n.getChild(i));
        }
        // الاسم: أول نص فيه حروف (اسم جهة الاتصال)، وإلا أول رقم هاتف
        for (String s : texts) if (hasLetter(s)) { f.name = s; break; }
        if (f.name == null) for (String s : texts) if (s.replaceAll("[^0-9+]", "").length() >= 5) { f.name = s; break; }
        return f;
    }

    private static int kind(String label, String id) {
        int k = CallBar.kind(label);
        if (k != CallBar.OTHER || id == null) return k;
        String i = id.toLowerCase(Locale.ROOT);
        if (i.contains("answer") || i.contains("accept") || i.contains("pickup") || i.contains("pick_up") || i.contains("jieting")) return CallBar.ANSWER;
        if (i.contains("hangup") || i.contains("hang_up") || i.contains("reject") || i.contains("decline") || i.contains("endcall")
                || i.contains("end_call") || i.contains("guaduan")) return CallBar.END;
        return CallBar.OTHER;
    }

    private static boolean isMute(String label, String id) {
        String s = (label + " " + (id == null ? "" : id)).toLowerCase(Locale.ROOT);
        return s.contains("mute") || s.contains("كتم") || s.contains("静音") || s.contains("mic");
    }

    private static boolean incomingWord(String s) {
        String l = s.toLowerCase(Locale.ROOT);
        return l.contains("incoming") || l.contains("وارد") || l.contains("来电") || l.contains("calling");
    }

    private static boolean hasLetter(String s) {
        for (int i = 0; i < s.length(); i++) if (Character.isLetter(s.charAt(i))) return !incomingWord(s);
        return false;
    }

    private static AccessibilityNodeInfo clickable(AccessibilityNodeInfo n) {
        for (int up = 0; n != null && up < 4; up++, n = n.getParent()) if (n.isClickable()) return n;
        return null;
    }

    // ───────── الغطاء ─────────

    private void show(Found f) {
        CallBar.Info info = new CallBar.Info();
        info.key = "screen:" + f.pkg;
        info.pkg = f.pkg;
        info.name = f.name == null ? "" : f.name;
        info.incoming = f.answer != null;
        info.detail = "";
        if (info.incoming) ongoingPkg = null;
        else {
            if (!f.pkg.equals(ongoingPkg)) { ongoingPkg = f.pkg; ongoingSince = System.currentTimeMillis() - seconds(f.timer) * 1000L; }
            info.since = ongoingSince; // عدّاد يتحرك وحده، فلا نعيد بناء الغطاء كل ثانية
        }
        if (f.end != null) info.buttons.add(proxy(CallBar.END, f.pkg));
        if (f.answer != null) info.buttons.add(proxy(CallBar.ANSWER, f.pkg));
        if (!info.incoming && f.mute != null) {
            CallBar.Btn m = new CallBar.Btn(CallBar.OTHER, ui.getString(R.string.media_mute), null, null);
            m.run = () -> press(f.pkg, -1);
            info.buttons.add(0, m);
        }
        String sig = info.signature() + "|" + f.bounds.flattenToString();
        if (sig.equals(coverSig)) return;
        coverSig = sig;
        pkg = f.pkg;
        bounds.set(f.bounds);

        DisplayMetrics dm = ui.getResources().getDisplayMetrics();
        boolean big = bounds.width() * bounds.height() > dm.widthPixels * dm.heightPixels * 0.35f;
        if (shape == null) shape = new CallBar(ui, wm, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY);
        View capsule = shape.view(info);

        boolean first = cover == null;
        if (first) cover = new FrameLayout(ui);
        cover.removeAllViews();
        if (big) {
            // شاشة كاملة: نقشنا مكانها، والكبسولة في الأسفل بجانب مكان الشريط
            PatternView pv = new PatternView(ui);
            pv.setPalette(PatternView.current(ui));
            cover.addView(pv, new FrameLayout.LayoutParams(-1, -1));
            FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(-1, px(88), Gravity.BOTTOM);
            cl.leftMargin = Math.max(0, px(80) - bounds.left);
            cl.rightMargin = px(20);
            cl.bottomMargin = px(12);
            cover.addView(capsule, cl);
            cover.setClickable(true); // لا يمر اللمس للشاشة الأصلية إلا عبر أزرارنا
        } else {
            // نافذة صغيرة عائمة: الكبسولة بحجمها ومكانها
            cover.addView(capsule, new FrameLayout.LayoutParams(-1, -1));
        }
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                big ? bounds.width() : Math.max(bounds.width(), px(420)), big ? bounds.height() : Math.max(bounds.height(), px(88)),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.x = bounds.left;
        lp.y = bounds.top;
        try {
            if (first) wm.addView(cover, lp); else wm.updateViewLayout(cover, lp);
        } catch (Exception e) { cover = null; coverSig = null; }
    }

    private static long seconds(String t) {
        if (t == null) return 0;
        long s = 0;
        for (String p : t.split(":")) s = s * 60 + Long.parseLong(p);
        return s;
    }

    void hide() {
        ongoingPkg = null;
        if (cover != null) try { wm.removeView(cover); } catch (Exception ignored) { }
        cover = null;
        coverSig = null;
        pkg = null;
    }

    private CallBar.Btn proxy(int kind, String p) {
        CallBar.Btn b = new CallBar.Btn(kind, null, null, null);
        b.run = () -> press(p, kind);
        return b;
    }

    /** يضغط الزر المطلوب في شاشة المكالمة الأصلية (نبحث عنه من جديد لحظة الضغط). kind=-1 للكتم */
    private void press(String p, int kind) {
        try {
            for (AccessibilityWindowInfo w : svc.getWindows()) {
                AccessibilityNodeInfo root = w.getRoot();
                if (root == null || root.getPackageName() == null || !p.equals(root.getPackageName().toString())) continue;
                Found f = read(root);
                AccessibilityNodeInfo n = kind == CallBar.ANSWER ? f.answer : kind == CallBar.END ? f.end : f.mute;
                if (n == null) continue;
                if (!n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) tap(n);
                h.postDelayed(this::scan, 400);
                return;
            }
        } catch (Exception ignored) { }
    }

    /** إن رفض الزر الضغط البرمجي: لمسة على مكانه (تمر من تحت غطائنا لأنها من النظام) */
    private void tap(AccessibilityNodeInfo n) {
        if (Build.VERSION.SDK_INT < 24) return;
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        Path path = new Path();
        path.moveTo(r.exactCenterX(), r.exactCenterY());
        View c = cover;
        if (c != null) c.setVisibility(View.GONE); // اللمسة تصل للشاشة الأصلية
        svc.dispatchGesture(new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 60)).build(), null, null);
        h.postDelayed(() -> { if (cover != null) cover.setVisibility(View.VISIBLE); }, 300);
    }

    private int px(float v) { return Math.round(v * dp); }
}
