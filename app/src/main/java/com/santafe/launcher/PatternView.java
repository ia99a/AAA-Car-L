package com.santafe.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/**
 * خلفية النقش: بلاطة نقش نظيفة (tools/pattern_*.py) تتكرر بلا فواصل، بلونين داكنين من الإعدادات،
 * مع تعتيم خفيف للأطراف. ترسم بأي دقة دون فقدان الحدة لأن البلاطة أعلى دقة من الشاشة.
 */
public class PatternView extends View {

    /** المفتاح، اسم اللون، لون الخلفية، لون النقش */
    static final String[] KEYS = {"charcoal", "navy", "green", "burgundy", "bronze", "gold", "graphite"};
    static final int[] NAMES = {R.string.bg_charcoal, R.string.bg_navy, R.string.bg_green, R.string.bg_burgundy,
            R.string.bg_bronze, R.string.bg_gold, R.string.bg_graphite};
    static final int[][] COLORS = {
            {0xFF232427, 0xFF151618}, {0xFF16203A, 0xFF0B1222}, {0xFF0F2A1F, 0xFF07170F}, {0xFF2C1216, 0xFF1A090C},
            {0xFF2B2218, 0xFF18120B}, {0xFF0E0E0F, 0xFF2E2616}, {0xFF23282F, 0xFF15191E},
    };

    private static Bitmap mask; // مشتركة بين الشاشات، تُحمَّل مرة واحدة
    private final Paint motif = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint shade = new Paint();
    private final Matrix matrix = new Matrix();
    private int bg = COLORS[0][0];

    public PatternView(Context c) { this(c, null); }

    public PatternView(Context c, AttributeSet a) {
        super(c, a);
        if (mask == null) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inScaled = false;
            mask = BitmapFactory.decodeResource(c.getResources(), R.drawable.pattern_mask, o);
        }
        if (mask != null) motif.setShader(new BitmapShader(mask, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
        setPalette(0);
    }

    static int current(Context c) {
        String k = Lang.prefs(c).getString("bg_color", KEYS[0]);
        for (int i = 0; i < KEYS.length; i++) if (KEYS[i].equals(k)) return i;
        return 0;
    }

    static void save(Context c, int i) {
        if (i >= 0 && i < KEYS.length) Lang.prefs(c).edit().putString("bg_color", KEYS[i]).apply();
    }

    void setPalette(int i) {
        if (i < 0 || i >= COLORS.length) i = 0;
        bg = COLORS[i][0];
        motif.setColorFilter(new PorterDuffColorFilter(COLORS[i][1], PorterDuff.Mode.SRC_IN));
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        if (mask == null || h == 0) return;
        // ارتفاع التكرار حوالي نصف الشاشة تقريبًا، كما في الصورة الأصلية
        float s = h * 0.46f / mask.getHeight();
        matrix.setScale(s, s);
        matrix.postTranslate((w - mask.getWidth() * s) / 2f, (h - mask.getHeight() * s) / 2f);
        motif.getShader().setLocalMatrix(matrix);
        shade.setShader(new RadialGradient(w * 0.5f, h * 0.42f, Math.max(w, h) * 0.62f,
                new int[]{0x00000000, 0x00000000, 0x80000000}, new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas c) {
        c.drawColor(bg);
        if (mask != null) c.drawPaint(motif);
        c.drawPaint(shade);
    }
}
