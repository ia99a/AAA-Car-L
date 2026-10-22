package com.santafe.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/**
 * خلفية النقش: الصورة الأصلية نفسها بنعومتها (tools/pattern_soft.py أزال فقط اختلاف الإضاءة)،
 * تُلوَّن بلونين داكنين من الإعدادات: الطرف الداكن للنقش، والفاتح للأرضية، ثم تعتيم ناعم للأطراف.
 */
public class PatternView extends View {

    static final String[] KEYS = {"charcoal", "navy", "green", "burgundy", "bronze", "gold", "graphite"};
    static final int[] NAMES = {R.string.bg_charcoal, R.string.bg_navy, R.string.bg_green, R.string.bg_burgundy,
            R.string.bg_bronze, R.string.bg_gold, R.string.bg_graphite};
    /** {لون النقش، لون الأرضية}. الفحمي يطابق الصورة الأصلية */
    static final int[][] COLORS = {
            {0xFF191919, 0xFF2D2D2E}, {0xFF0C1324, 0xFF1C2947}, {0xFF08160F, 0xFF173729}, {0xFF170A0D, 0xFF3B161C},
            {0xFF15110B, 0xFF392D1D}, {0xFF3B2F19, 0xFF0E0E0F}, {0xFF131519, 0xFF292E36},
    };

    private static Bitmap image; // مشتركة، تُحمَّل مرة واحدة
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint shade = new Paint();
    private final Matrix matrix = new Matrix();

    public PatternView(Context c) { this(c, null); }

    public PatternView(Context c, AttributeSet a) {
        super(c, a);
        if (image == null) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inScaled = false;
            image = BitmapFactory.decodeResource(c.getResources(), R.drawable.pattern_soft, o);
        }
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

    /** درجة الرمادي t (0..255) تصير: لون النقش + t × (لون الأرضية − لون النقش) */
    void setPalette(int i) {
        if (i < 0 || i >= COLORS.length) i = 0;
        int c0 = COLORS[i][0], c1 = COLORS[i][1];
        float[] m = new float[20];
        int[] sh = {16, 8, 0};
        for (int ch = 0; ch < 3; ch++) {
            int a = (c0 >> sh[ch]) & 0xFF, b = (c1 >> sh[ch]) & 0xFF;
            m[ch * 5] = (b - a) / 255f; // من القناة الحمراء (الصورة رمادية فكل القنوات متساوية)
            m[ch * 5 + 4] = a;
        }
        m[18] = 1f;
        paint.setColorFilter(new ColorMatrixColorFilter(new ColorMatrix(m)));
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        if (image == null || w == 0 || h == 0) return;
        // ملء الشاشة مع الحفاظ على النسبة (قص بسيط من الأطراف)
        float s = Math.max(w / (float) image.getWidth(), h / (float) image.getHeight());
        matrix.setScale(s, s);
        matrix.postTranslate((w - image.getWidth() * s) / 2f, (h - image.getHeight() * s) / 2f);
        shade.setShader(new RadialGradient(w * 0.5f, h * 0.42f, Math.max(w, h) * 0.62f,
                new int[]{0x00000000, 0x00000000, 0x80000000}, new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas c) {
        c.drawColor(COLORS[0][1]);
        if (image != null) c.drawBitmap(image, matrix, paint);
        c.drawPaint(shade);
    }
}
