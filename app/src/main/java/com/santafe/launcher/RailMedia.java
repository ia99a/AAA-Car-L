package com.santafe.launcher;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * أزرار الصوت داخل شريط السائق (فوق الإعدادات): مؤشر يتحرك والصوت شغّال، ترجيع ١٠ ثوانٍ،
 * تشغيل/إيقاف بالذهبي، تقديم ١٠ ثوانٍ، وكتم. تظهر فقط والصوت شغّال (ودقيقة بعد الإيقاف المؤقت).
 * ثلاثة أحجام من الإعدادات.
 */
final class RailMedia extends LinearLayout implements MediaWatch.Listener {

    static final int HIDDEN = 0, SMALL = 1, MEDIUM = 2, LARGE = 3;

    static int size(Context c) {
        int s = Lang.prefs(c).getInt("media_size", MEDIUM);
        return s < HIDDEN || s > LARGE ? MEDIUM : s;
    }

    static void setSize(Context c, int s) { Lang.prefs(c).edit().putInt("media_size", s).apply(); }

    private final float dp;
    private final ImageView play, mute;
    private final Eq eq;
    private boolean attached;

    RailMedia(Context c, int size) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER_HORIZONTAL);
        setVisibility(GONE);
        int btn = size == SMALL ? 24 : size == LARGE ? 32 : 28;
        int icon = size == SMALL ? 15 : size == LARGE ? 20 : 17;

        View line = new View(c); // فاصل خفيف عن أدوات الشريط
        line.setBackgroundColor(c.getColor(R.color.line));
        LayoutParams ll = new LayoutParams(px(22), Math.max(1, px(1)));
        ll.bottomMargin = px(5);
        addView(line, ll);

        eq = new Eq(c);
        LayoutParams el = new LayoutParams(px(btn * 0.45f), px(size == SMALL ? 7 : size == LARGE ? 10 : 8));
        el.bottomMargin = px(3);
        addView(eq, el);

        addView(seekButton(c, btn, icon, false));
        play = button(c, R.drawable.ic_pause, btn, icon, true, R.string.media_pause);
        play.setOnClickListener(v -> MediaWatch.toggle());
        addView(play, lp(btn));
        addView(seekButton(c, btn, icon, true));
        mute = button(c, R.drawable.ic_volume, btn, icon, false, R.string.media_mute);
        mute.setOnClickListener(v -> MediaWatch.toggleMute());
        addView(mute, lp(btn));
        setPadding(0, 0, 0, px(4));
    }

    private int px(float v) { return Math.round(v * dp); }

    private LayoutParams lp(int btn) {
        LayoutParams l = new LayoutParams(px(btn), px(btn));
        l.bottomMargin = px(1);
        return l;
    }

    private ImageView button(Context c, int iconRes, int btn, int icon, boolean main, int desc) {
        ImageView v = new ImageView(c);
        v.setImageResource(iconRes);
        int pad = px((btn - icon) / 2f);
        v.setPadding(pad, pad, pad, pad);
        v.setScaleType(ImageView.ScaleType.FIT_CENTER);
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.OVAL);
        shape.setColor(main ? 0xFFD8AE5E : 0x00000000);
        v.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), shape, main ? null : maskOval()));
        if (main) v.setImageTintList(ColorStateList.valueOf(0xFF1C1608));
        v.setContentDescription(getContext().getString(desc));
        v.setClickable(true);
        v.setFocusable(true);
        return v;
    }

    private GradientDrawable maskOval() {
        GradientDrawable m = new GradientDrawable();
        m.setShape(GradientDrawable.OVAL);
        m.setColor(0xFFFFFFFF);
        return m;
    }

    /** زر ترجيع/تقديم: سهم دائري وفي وسطه «١٠» */
    private View seekButton(Context c, int btn, int icon, boolean fwd) {
        FrameLayout f = new FrameLayout(c);
        ImageView iv = button(c, fwd ? R.drawable.ic_seek_fwd : R.drawable.ic_seek_back, btn, icon, false,
                fwd ? R.string.media_fwd : R.string.media_back);
        f.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        TextView t = new TextView(c);
        t.setText(R.string.media_ten);
        t.setTextColor(0xFFEEF0F3);
        t.setTextSize(icon * 0.4f);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t.setIncludeFontPadding(false);
        t.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER);
        tl.topMargin = px(icon * 0.12f);
        f.addView(t, tl);
        iv.setOnClickListener(v -> MediaWatch.seek(fwd ? 10_000 : -10_000));
        f.setLayoutParams(lp(btn));
        return f;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
        MediaWatch.add(getContext(), this);
        onMediaChanged();
    }

    @Override
    protected void onDetachedFromWindow() {
        attached = false;
        MediaWatch.remove(this);
        eq.run(false);
        super.onDetachedFromWindow();
    }

    /** الشاشة رجعت (onResume): نعيد المحاولة إن مُنح الإذن للتو */
    void resume() {
        if (attached) { MediaWatch.add(getContext(), this); onMediaChanged(); }
    }

    @Override
    public void onMediaChanged() {
        boolean show = MediaWatch.visible();
        if (show != (getVisibility() == VISIBLE)) setVisibility(show ? VISIBLE : GONE);
        if (!show) { eq.run(false); return; }
        boolean playing = MediaWatch.playing();
        play.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        play.setContentDescription(getContext().getString(playing ? R.string.media_pause : R.string.media_play));
        boolean m = MediaWatch.muted();
        mute.setImageResource(m ? R.drawable.ic_volume_off : R.drawable.ic_volume);
        mute.setImageTintList(m ? ColorStateList.valueOf(0xFFE0483E) : null);
        mute.setContentDescription(getContext().getString(m ? R.string.media_unmute : R.string.media_mute));
        eq.run(playing);
    }

    /** ثلاثة أعمدة ذهبية تتحرك والصوت شغّال، وتثبت قصيرة عند الإيقاف */
    static final class Eq extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
        private float t;
        private boolean on;

        Eq(Context c) {
            super(c);
            p.setColor(0xFFD8AE5E);
            anim.setDuration(1000);
            anim.setRepeatCount(ValueAnimator.INFINITE);
            anim.setInterpolator(new LinearInterpolator());
            anim.addUpdateListener(a -> { t = (float) a.getAnimatedValue(); invalidate(); });
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        void run(boolean playing) {
            if (playing == on) return;
            on = playing;
            if (on && (android.os.Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled())) anim.start(); else { anim.cancel(); t = 0; invalidate(); }
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), bw = w / 5f;
            for (int i = 0; i < 3; i++) {
                float k = on ? 0.3f + 0.7f * (float) (0.5 + 0.5 * Math.sin((t + i * 0.2f) * 2 * Math.PI)) : 0.3f;
                float x = bw * (i * 2), bh = h * k;
                c.drawRoundRect(x, h - bh, x + bw, h, bw / 3, bw / 3, p);
            }
        }
    }
}
