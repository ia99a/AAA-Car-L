package com.santafe.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;

import java.text.NumberFormat;
import java.util.Locale;

/**
 * كبسولة الصوت أعلى منتصف الشاشة: تظهر فوق أي تطبيق كلما تغيّر الصوت (من المقود أو الشاشة)
 * وتختفي بعد ثانيتين. فيها أيقونة نوع الصوت، شريط ذهبي يمتلئ، الرقم، واسم نوع الصوت.
 * لا تُلمس ولا تأخذ التركيز، فلا تعطّل أي شي تحتها.
 */
final class VolumeHud {

    private static final String ACTION_VOLUME = "android.media.VOLUME_CHANGED_ACTION";
    private static final String ACTION_MUTE = "android.media.STREAM_MUTE_CHANGED_ACTION";
    private static final String EXTRA_STREAM = "android.media.EXTRA_VOLUME_STREAM_TYPE";
    private static final long SHOW_MS = 2000;

    private final Context ui;
    private final WindowManager wm;
    private final AudioManager am;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final float dp;
    private Capsule view;
    private boolean added;
    private final Runnable hide = this::hideNow;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            // نأخذ نوع الصوت فقط من البث، والمستوى نقرؤه من النظام نفسه
            int stream = i.getIntExtra(EXTRA_STREAM, AudioManager.STREAM_MUSIC);
            if (label(stream) == 0) return; // أصوات لا تهم السائق (مثل إمكانية الوصول)
            Diag.volume("system", stream);
            show(stream);
        }
    };

    VolumeHud(Context ui, WindowManager wm, int overlayType) {
        this.ui = ui;
        this.wm = wm;
        this.am = (AudioManager) ui.getSystemService(Context.AUDIO_SERVICE);
        this.dp = ui.getResources().getDisplayMetrics().density;
        this.type = overlayType;
        IntentFilter f = new IntentFilter(ACTION_VOLUME);
        f.addAction(ACTION_MUTE);
        if (Build.VERSION.SDK_INT >= 33) ui.registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED);
        else ui.registerReceiver(receiver, f);
        current = this;
    }

    private final int type;

    void release() {
        if (current == this) current = null;
        h.removeCallbacks(hide);
        try { ui.unregisterReceiver(receiver); } catch (Exception ignored) { }
        if (added) try { wm.removeView(view); } catch (Exception ignored) { }
        added = false;
        view = null;
    }

    private static int label(int stream) {
        switch (stream) {
            case AudioManager.STREAM_MUSIC: return R.string.vol_media;
            case AudioManager.STREAM_VOICE_CALL:
            case 6: /* STREAM_BLUETOOTH_SCO */ return R.string.vol_call;
            case AudioManager.STREAM_RING: return R.string.vol_ring;
            case AudioManager.STREAM_ALARM: return R.string.vol_alarm;
            case AudioManager.STREAM_NOTIFICATION: return R.string.vol_notif;
            case AudioManager.STREAM_SYSTEM: return R.string.vol_system;
            default: return 0;
        }
    }

    /** آخر كبسولة صوت عاملة (لزر «جرّب» في الإعدادات) */
    static VolumeHud current;

    void show(int stream) {
        if (am == null) return;
        int max = Math.max(1, am.getStreamMaxVolume(stream));
        int level = am.getStreamVolume(stream);
        boolean muted = level == 0 || (Build.VERSION.SDK_INT >= 23 && am.isStreamMute(stream));
        boolean call = stream == AudioManager.STREAM_VOICE_CALL || stream == 6;
        if (view == null) view = new Capsule(ui);
        view.set(muted ? 0 : level, max, ui.getString(label(stream)),
                ui.getDrawable(muted ? R.drawable.ic_volume_off : call ? R.drawable.ic_call : R.drawable.ic_volume));
        if (!added) {
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            lp.y = (int) (14 * dp);
            try { wm.addView(view, lp); added = true; } catch (Exception e) { return; }
            view.setAlpha(0f);
            view.setVisibility(View.GONE);
        }
        if (view.getVisibility() != View.VISIBLE || view.getAlpha() < 1f) {
            view.animate().cancel();
            if (view.getVisibility() != View.VISIBLE) view.setTranslationY(-16 * dp);
            view.setVisibility(View.VISIBLE);
            view.animate().alpha(1f).translationY(0f).setDuration(200).setInterpolator(new DecelerateInterpolator(2f)).start();
        }
        h.removeCallbacks(hide);
        h.postDelayed(hide, SHOW_MS);
    }

    private void hideNow() {
        if (view == null || view.getVisibility() != View.VISIBLE) return;
        view.animate().cancel();
        view.animate().alpha(0f).translationY(-10 * dp).setDuration(250)
                .withEndAction(() -> { if (view != null) view.setVisibility(View.GONE); }).start();
    }

    /** الكبسولة نفسها: [أيقونة] [شريط يمتلئ] [الرقم] [نوع الصوت] من اليسار لليمين بأي لغة */
    static final class Capsule extends View {
        private final float dp;
        private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG), border = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint num = new Paint(Paint.ANTI_ALIAS_FLAG), lbl = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final NumberFormat nf;
        private int level, max = 1;
        private String label = "";
        private Drawable icon;

        Capsule(Context c) {
            super(c);
            dp = c.getResources().getDisplayMetrics().density;
            float sp = c.getResources().getDisplayMetrics().scaledDensity;
            bg.setColor(0xF714161A);
            border.setStyle(Paint.Style.STROKE);
            border.setStrokeWidth(Math.max(1, dp));
            border.setColor(0xFF363C47);
            track.setColor(0xFF2B3038);
            fill.setColor(0xFFD8AE5E);
            num.setColor(0xFFEEF0F3);
            num.setTextSize(18 * sp);
            num.setTypeface(Typeface.DEFAULT_BOLD);
            num.setTextAlign(Paint.Align.RIGHT);
            lbl.setColor(0xFF9AA1AB);
            lbl.setTextSize(12 * sp);
            nf = NumberFormat.getInstance(Lang.isArabic(c) ? new Locale("ar", "SA") : Locale.ENGLISH);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        }

        void set(int level, int max, String label, Drawable icon) {
            boolean resize = !label.equals(this.label);
            this.level = level;
            this.max = max;
            this.label = label;
            this.icon = icon;
            setContentDescription(label + " " + level);
            if (resize) requestLayout();
            invalidate();
        }

        private static final float H = 48, PAD = 18, ICON = 22, GAP = 14, NUM = 34, TRACK_MIN = 200;

        @Override
        protected void onMeasure(int w, int h) {
            int width = (int) Math.ceil((PAD * 2 + ICON + GAP * 3 + NUM + TRACK_MIN) * dp + lbl.measureText(label));
            setMeasuredDimension(Math.max(width, (int) (380 * dp)), (int) (H * dp));
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), s = border.getStrokeWidth() / 2;
            r.set(s, s, w - s, h - s);
            c.drawRoundRect(r, h / 2, h / 2, bg);
            c.drawRoundRect(r, h / 2, h / 2, border);
            float x = PAD * dp, cy = h / 2;
            if (icon != null) {
                int is = (int) (ICON * dp);
                icon.setBounds((int) x, (int) (cy - is / 2f), (int) x + is, (int) (cy + is / 2f));
                icon.draw(c);
            }
            float lw = lbl.measureText(label);
            float right = w - PAD * dp;
            float lBase = cy - (lbl.descent() + lbl.ascent()) / 2;
            c.drawText(label, right - lw, lBase, lbl);
            float numRight = right - lw - GAP * dp;
            c.drawText(nf.format(level), numRight, cy - (num.descent() + num.ascent()) / 2, num);
            float t0 = x + (ICON + GAP) * dp, t1 = numRight - (NUM + GAP) * dp, th = 8 * dp;
            r.set(t0, cy - th / 2, t1, cy + th / 2);
            c.drawRoundRect(r, th / 2, th / 2, track);
            float f = Math.max(0f, Math.min(1f, level / (float) max));
            if (f > 0) {
                r.set(t0, cy - th / 2, t0 + Math.max(th, (t1 - t0) * f), cy + th / 2);
                c.drawRoundRect(r, th / 2, th / 2, fill);
            }
        }
    }
}
