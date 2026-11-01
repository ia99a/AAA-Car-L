package com.santafe.launcher;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Outline;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.Chronometer;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * كبسولة المكالمة أسفل الشاشة (بجانب شريط السائق): الاسم والحالة أو مدة المكالمة، وأزرار التحكم.
 * الأزرار هي أزرار إشعار المكالمة نفسه من تطبيق الهاتف (رد، رفض، إنهاء، كتم...)، فتعمل مع أي تطبيق اتصال
 * يضع إشعار مكالمة. الضغط على الاسم يفتح تطبيق الهاتف.
 */
final class CallBar {

    static final int OTHER = 0, ANSWER = 1, END = 2;

    /** نوع الزر من نصه (عربي وإنجليزي وصيني، لأن تطبيقات البلوتوث في الشاشات الصينية كثيرًا ما تكون كذلك) */
    static int kind(CharSequence title) {
        if (title == null) return OTHER;
        String t = title.toString().toLowerCase(Locale.ROOT).trim();
        if (t.contains("answer") || t.contains("accept") || t.equals("رد") || t.contains("الرد") || t.contains("إجابة")
                || t.contains("اجابة") || t.contains("قبول") || t.contains("接听") || t.contains("接聽")) return ANSWER;
        if (t.contains("decline") || t.contains("reject") || t.contains("hang") || t.contains("end call") || t.equals("end")
                || t.contains("رفض") || t.contains("إنهاء") || t.contains("انهاء") || t.contains("إغلاق") || t.contains("قطع")
                || t.contains("挂断") || t.contains("掛斷") || t.contains("拒绝") || t.contains("拒絕")) return END;
        return OTHER;
    }

    /** زر واحد من أزرار المكالمة */
    static final class Btn {
        final int kind; final CharSequence title; final PendingIntent intent; final Icon icon;
        Btn(int kind, CharSequence title, PendingIntent intent, Icon icon) {
            this.kind = kind; this.title = title; this.intent = intent; this.icon = icon;
        }
    }

    /** ما نعرضه من إشعار المكالمة */
    static final class Info {
        String key, pkg, name, detail;
        boolean incoming;
        long since; // بداية المكالمة (لعدّاد المدة)، 0 إن لم تُعرف
        Icon avatar;
        PendingIntent open;
        final List<Btn> buttons = new ArrayList<>();

        /** يقرأ إشعار المكالمة. لا يحفظ شيئًا ولا يرسله لأي مكان */
        static Info from(String key, Notification n) {
            Info i = new Info();
            i.key = key;
            Bundle x = n.extras;
            CharSequence title = x.getCharSequence(Notification.EXTRA_TITLE);
            CharSequence text = x.getCharSequence(Notification.EXTRA_TEXT);
            i.name = title == null ? "" : title.toString();
            i.detail = text == null ? "" : text.toString();
            i.open = n.contentIntent;
            if (Build.VERSION.SDK_INT >= 23) i.avatar = n.getLargeIcon();
            int callType = x.getInt("android.callType", 0); // CallStyle في أندرويد ١٢+: ١ واردة، ٢ جارية
            // أزرار CallStyle الصريحة أولًا
            PendingIntent answer = x.getParcelable("android.answerIntent");
            PendingIntent decline = x.getParcelable("android.declineIntent");
            PendingIntent hangUp = x.getParcelable("android.hangUpIntent");
            if (n.actions != null) {
                for (Notification.Action a : n.actions) {
                    if (a == null || a.actionIntent == null) continue;
                    if (a.getRemoteInputs() != null && a.getRemoteInputs().length > 0) continue; // الرد برسالة: ليس للقيادة
                    i.buttons.add(new Btn(kind(a.title), a.title, a.actionIntent, Build.VERSION.SDK_INT >= 23 ? a.getIcon() : null));
                }
            }
            if (answer != null && !i.has(ANSWER)) i.buttons.add(new Btn(ANSWER, null, answer, null));
            if (decline != null && !i.has(END)) i.buttons.add(new Btn(END, null, decline, null));
            if (hangUp != null && !i.has(END)) i.buttons.add(new Btn(END, null, hangUp, null));
            i.incoming = callType == 1 || (callType == 0 && (i.has(ANSWER) || n.fullScreenIntent != null));
            if (!i.incoming && n.when > 0 && x.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false)) i.since = n.when;
            return i;
        }

        boolean has(int k) {
            for (Btn b : buttons) if (b.kind == k) return true;
            return false;
        }

        String signature() {
            StringBuilder s = new StringBuilder(key).append('|').append(name).append('|').append(detail)
                    .append('|').append(incoming).append('|').append(since);
            for (Btn b : buttons) s.append('|').append(b.kind).append(b.title);
            return s.toString();
        }
    }

    // ───────── النافذة ─────────

    private final Context ui;
    private final WindowManager wm;
    private final float dp;
    private FrameLayout root;
    private String shownSig;

    private final int type;

    /** type: نافذة إمكانية الوصول (من «تحكم AAA Car L») أو نافذة الظهور فوق التطبيقات */
    CallBar(Context ui, WindowManager wm, int type) {
        this.ui = ui;
        this.wm = wm;
        this.type = type;
        this.dp = ui.getResources().getDisplayMetrics().density;
    }

    private int px(float v) { return Math.round(v * dp); }

    boolean showing() { return root != null; }

    void show(Info info) {
        String sig = info.signature();
        if (root != null && sig.equals(shownSig)) return; // لا جديد
        boolean first = root == null;
        if (first) {
            root = new FrameLayout(ui);
            int w = ui.getResources().getDisplayMetrics().widthPixels - px(100);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(w, px(88), type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.BOTTOM | Gravity.LEFT;
            lp.x = px(80); // بعد شريط السائق
            lp.y = px(12);
            try { wm.addView(root, lp); } catch (Exception e) { root = null; return; }
        }
        shownSig = sig;
        root.removeAllViews();
        root.addView(build(info));
        if (first) {
            root.setAlpha(0f);
            root.setTranslationY(px(24));
            root.animate().alpha(1f).translationY(0f).setDuration(260).setInterpolator(new DecelerateInterpolator(2f)).start();
        }
    }

    void hide() {
        if (root == null) return;
        final View r = root;
        root = null;
        shownSig = null;
        r.animate().cancel();
        r.animate().alpha(0f).translationY(px(24)).setDuration(220)
                .withEndAction(() -> { try { wm.removeView(r); } catch (Exception ignored) { } }).start();
    }

    private View build(Info info) {
        LinearLayout bar = new LinearLayout(ui);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setLayoutDirection(Lang.isArabic(ui) ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        bar.setPaddingRelative(px(16), 0, px(16), 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF7181B1F);
        bg.setStroke(Math.max(1, px(1)), 0xFF363C47);
        bg.setCornerRadius(px(44));
        bar.setBackground(bg);

        // الصورة أو الحرفان الأولان
        FrameLayout av = new FrameLayout(ui);
        GradientDrawable avBg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF3A4250, 0xFF272C35});
        avBg.setShape(GradientDrawable.OVAL);
        av.setBackground(avBg);
        av.setClipToOutline(true);
        av.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setOval(0, 0, v.getWidth(), v.getHeight()); }
        });
        Drawable photo = null;
        if (info.avatar != null) try { photo = info.avatar.loadDrawable(ui); } catch (Exception ignored) { }
        if (photo != null) {
            ImageView iv = new ImageView(ui);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setImageDrawable(photo);
            av.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        } else {
            String ini = initials(info.name);
            if (ini.isEmpty()) {
                ImageView iv = new ImageView(ui);
                iv.setImageResource(R.drawable.ic_call);
                iv.setImageTintList(ColorStateList.valueOf(0xFFDFE3E8));
                av.addView(iv, new FrameLayout.LayoutParams(px(24), px(24), Gravity.CENTER));
            } else {
                TextView t = new TextView(ui);
                t.setText(ini);
                t.setTextColor(0xFFDFE3E8);
                t.setTextSize(20);
                t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                t.setGravity(Gravity.CENTER);
                av.addView(t, new FrameLayout.LayoutParams(-1, -1));
            }
        }
        bar.addView(av, new LinearLayout.LayoutParams(px(56), px(56)));

        // الاسم والحالة
        LinearLayout who = new LinearLayout(ui);
        who.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(ui);
        name.setText(info.name.isEmpty() ? ui.getString(R.string.call_unknown) : info.name);
        name.setTextColor(0xFFEEF0F3);
        name.setTextSize(20);
        name.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        who.addView(name);
        if (info.since > 0) {
            Chronometer ch = new Chronometer(ui);
            ch.setBase(SystemClock.elapsedRealtime() - Math.max(0, System.currentTimeMillis() - info.since));
            ch.setTextColor(0xFF3FBF7F);
            ch.setTextSize(14);
            ch.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            ch.start();
            who.addView(ch);
        } else {
            TextView st = new TextView(ui);
            String d = info.detail;
            st.setText(info.incoming ? (d.isEmpty() ? ui.getString(R.string.call_incoming) : ui.getString(R.string.call_incoming) + " · " + d) : d);
            st.setTextColor(0xFF9AA1AB);
            st.setTextSize(14);
            st.setSingleLine(true);
            st.setEllipsize(TextUtils.TruncateAt.END);
            who.addView(st);
        }
        LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(0, -2, 1f);
        wl.setMarginStart(px(16));
        wl.setMarginEnd(px(12));
        bar.addView(who, wl);
        if (info.open != null) {
            // فتح شاشة المكالمة الأصلية بطلبك: لا نخفيها بعدها في هذه المكالمة
            View.OnClickListener open = v -> { CallWatcher.userOpened(info.key); send(info.open); };
            av.setOnClickListener(open);
            who.setOnClickListener(open);
        }

        // الأزرار: الواردة ← رفض ورد عريضان؛ الجارية ← بقية الأزرار ثم إنهاء
        List<Btn> list = new ArrayList<>();
        if (info.incoming) {
            for (Btn b : info.buttons) if (b.kind == END) { list.add(b); break; }
            for (Btn b : info.buttons) if (b.kind == ANSWER) { list.add(b); break; }
        } else {
            for (Btn b : info.buttons) if (b.kind == OTHER && list.size() < 3) list.add(b);
            for (Btn b : info.buttons) if (b.kind == END) { list.add(b); break; }
        }
        for (Btn b : list) {
            View v = button(b, info.incoming);
            LinearLayout.LayoutParams bl = (LinearLayout.LayoutParams) v.getLayoutParams();
            bl.setMarginStart(px(12));
            bar.addView(v, bl);
        }
        return bar;
    }

    private View button(Btn b, boolean wide) {
        int color = b.kind == ANSWER ? 0xFF3FBF7F : b.kind == END ? 0xFFE0483E : 0xFF2B3038;
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(px(29));
        RippleDrawable bg = new RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), shape, null);
        String desc = b.title != null ? b.title.toString()
                : ui.getString(b.kind == ANSWER ? R.string.call_answer : R.string.call_end);
        View v;
        boolean known = b.kind != OTHER;
        Drawable icon = null;
        if (known) icon = ui.getDrawable(b.kind == ANSWER ? R.drawable.ic_call : R.drawable.ic_call_end);
        else if (b.icon != null) try { icon = b.icon.loadDrawable(ui); } catch (Exception ignored) { }
        int w = wide && known ? px(120) : px(58);
        if (icon != null) {
            ImageView iv = new ImageView(ui);
            icon = icon.mutate();
            icon.setTint(0xFFFFFFFF);
            iv.setImageDrawable(icon);
            iv.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            iv.setPadding(px(16), px(16), px(16), px(16));
            v = iv;
        } else { // زر بلا أيقونة: نكتب اسمه
            TextView t = new TextView(ui);
            t.setText(desc);
            t.setTextColor(0xFFEEF0F3);
            t.setTextSize(14);
            t.setGravity(Gravity.CENTER);
            t.setSingleLine(true);
            t.setPadding(px(16), 0, px(16), 0);
            v = t;
            w = -2;
        }
        v.setBackground(bg);
        v.setContentDescription(desc);
        v.setClickable(true);
        v.setFocusable(true);
        v.setOnClickListener(x -> send(b.intent));
        v.setLayoutParams(new LinearLayout.LayoutParams(w, px(58)));
        return v;
    }

    private static void send(PendingIntent p) {
        try { p.send(); } catch (Exception ignored) { } // انتهت المكالمة أو أُلغي الزر
    }

    /** أول حرف من أول كلمتين، أو لا شيء إن كان الاسم رقم هاتف */
    static String initials(String name) {
        if (name == null) return "";
        String n = name.trim();
        if (n.isEmpty() || !Character.isLetter(n.codePointAt(0))) return "";
        String[] w = n.split("\\s+");
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < w.length && s.length() < 2; i++) {
            if (w[i].isEmpty() || !Character.isLetter(w[i].codePointAt(0))) continue;
            s.appendCodePoint(w[i].codePointAt(0));
        }
        return s.toString();
    }
}
