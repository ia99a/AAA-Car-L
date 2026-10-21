package com.santafe.launcher;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.Typeface;
import android.provider.Settings;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;

/**
 * خلفية الخريطة الداكنة المتحركة:
 * شوارع وكتل مبانٍ، ومسار أزرق يُرسم عند الفتح، ثم سهم ملاحة يتقدّم عليه ببطء ويتكرر.
 * مرسومة بالكامل بالكود، فلا تحتاج إنترنت ولا مفتاح خرائط.
 */
public class MapBackdropView extends View {

    private final Paint block = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint road = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mainRoad = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint routeFaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint routeGlow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint route = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arrowFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arrowEdge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final float[] vx = {0.10f, 0.27f, 0.44f, 0.60f, 0.77f, 0.92f};
    private final float[] hy = {0.14f, 0.36f, 0.58f, 0.80f};
    private final boolean[] vMain = {false, true, false, false, true, false};
    private final boolean[] hMain = {false, true, false, false};

    private final Path routePath = new Path();
    private final Path segment = new Path();
    private final Path arrow = new Path();
    private PathMeasure measure;
    private float routeLen;

    private final float[] pos = new float[2];
    private final float[] tan = new float[2];

    private float drawIn = 0f;   // ٠→١ رسم المسار عند الفتح
    private float travel = 0.08f; // موضع السهم على المسار
    private ValueAnimator introAnim, travelAnim;
    private final float dp;
    private final String roadFahd, roadOlaya, roadTakhassusi; // تُقرأ مرة واحدة لا في كل إطار

    public MapBackdropView(Context c, AttributeSet a) {
        super(c, a);
        dp = getResources().getDisplayMetrics().density;
        roadFahd = c.getString(R.string.road_fahd);
        roadOlaya = c.getString(R.string.road_olaya);
        roadTakhassusi = c.getString(R.string.road_takhassusi);

        block.setColor(c.getColor(R.color.map_block));
        road.setColor(c.getColor(R.color.map_road));
        road.setStrokeWidth(7 * dp);
        road.setStrokeCap(Paint.Cap.SQUARE);
        mainRoad.setColor(c.getColor(R.color.map_main_road));
        mainRoad.setStrokeWidth(11 * dp);

        int blue = c.getColor(R.color.blue);
        routeFaint.setStyle(Paint.Style.STROKE);
        routeFaint.setStrokeWidth(4.5f * dp);
        routeFaint.setColor(blue);
        routeFaint.setAlpha(55);
        routeFaint.setStrokeJoin(Paint.Join.ROUND);
        routeFaint.setStrokeCap(Paint.Cap.ROUND);

        routeGlow.set(routeFaint);
        routeGlow.setStrokeWidth(13 * dp);
        routeGlow.setAlpha(45);

        route.set(routeFaint);
        route.setAlpha(255);

        arrowFill.setColor(0xFFE4E6EA);
        arrowEdge.setStyle(Paint.Style.STROKE);
        arrowEdge.setStrokeWidth(2 * dp);
        arrowEdge.setColor(0xFF16181C);
        arrowEdge.setStrokeJoin(Paint.Join.ROUND);

        label.setColor(c.getColor(R.color.map_label));
        label.setTextSize(11 * dp);
        label.setTypeface(Typeface.DEFAULT);

        // سهم الملاحة متجه للأعلى، مركزه عند (٠،٠)
        float s = 11 * dp;
        arrow.moveTo(0, -s);
        arrow.lineTo(s * 0.75f, s);
        arrow.lineTo(0, s * 0.55f);
        arrow.lineTo(-s * 0.75f, s);
        arrow.close();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        // المسار يمشي على الشوارع: من أسفل الوسط، للأعلى، يلتف يسارًا، ثم للأعلى حتى خارج الشاشة
        routePath.reset();
        routePath.moveTo(vx[3] * w, h * 1.02f);
        routePath.lineTo(vx[3] * w, hy[1] * h);
        routePath.lineTo(vx[1] * w, hy[1] * h);
        routePath.lineTo(vx[1] * w, -h * 0.02f);
        measure = new PathMeasure(routePath, false);
        routeLen = measure.getLength();
    }

    @Override
    protected void onDraw(Canvas cv) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || measure == null) return;
        cv.drawColor(getContext().getColor(R.color.map_bg));

        // كتل المباني بين الشوارع
        float inset = 10 * dp;
        float[] xs = withEdges(vx), ys = withEdges(hy);
        for (int i = 0; i + 1 < xs.length; i++) {
            for (int j = 0; j + 1 < ys.length; j++) {
                if ((i * 3 + j) % 4 == 0) continue; // ساحات فارغة لتبدو الخريطة طبيعية
                cv.drawRoundRect(xs[i] * w + inset, ys[j] * h + inset,
                        xs[i + 1] * w - inset, ys[j + 1] * h - inset, 6 * dp, 6 * dp, block);
            }
        }

        // الشوارع
        for (int i = 0; i < vx.length; i++) cv.drawLine(vx[i] * w, 0, vx[i] * w, h, vMain[i] ? mainRoad : road);
        for (int j = 0; j < hy.length; j++) cv.drawLine(0, hy[j] * h, w, hy[j] * h, hMain[j] ? mainRoad : road);

        // أسماء شوارع محلية
        cv.save();
        cv.rotate(-90, vx[4] * w - 12 * dp, h * 0.5f);
        cv.drawText(roadFahd, vx[4] * w - 12 * dp, h * 0.5f, label);
        cv.restore();
        cv.drawText(roadOlaya, vx[2] * w + 10 * dp, hy[2] * h - 12 * dp, label);
        cv.drawText(roadTakhassusi, vx[4] * w + 14 * dp, hy[1] * h - 14 * dp, label);

        // المسار: الجزء المقطوع خافت، والباقي أزرق متوهج
        float shown = routeLen * drawIn;
        float at = Math.min(routeLen * travel, shown);
        cv.drawPath(routePath, routeFaint);
        segment.reset();
        if (shown > at) {
            measure.getSegment(at, shown, segment, true);
            cv.drawPath(segment, routeGlow);
            cv.drawPath(segment, route);
        }

        // سهم الملاحة
        measure.getPosTan(at, pos, tan);
        float angle = (float) Math.toDegrees(Math.atan2(tan[1], tan[0])) + 90f;
        cv.save();
        cv.translate(pos[0], pos[1]);
        cv.rotate(angle);
        cv.drawPath(arrow, arrowFill);
        cv.drawPath(arrow, arrowEdge);
        cv.restore();
    }

    private static float[] withEdges(float[] a) {
        float[] r = new float[a.length + 2];
        r[0] = -0.05f;
        System.arraycopy(a, 0, r, 1, a.length);
        r[r.length - 1] = 1.05f;
        return r;
    }

    /** يُستدعى من الشاشة الرئيسية عند الظهور */
    public void start() {
        stop();
        if (animationsOff()) { drawIn = 1f; invalidate(); return; }

        introAnim = ValueAnimator.ofFloat(drawIn, 1f);
        introAnim.setDuration(1400);
        introAnim.setInterpolator(new DecelerateInterpolator(1.6f));
        introAnim.addUpdateListener(a -> { drawIn = (float) a.getAnimatedValue(); invalidate(); });
        introAnim.start();

        travelAnim = ValueAnimator.ofFloat(0.08f, 0.92f);
        travelAnim.setDuration(45000); // رحلة هادئة، لا تشتت السائق
        travelAnim.setStartDelay(1400);
        travelAnim.setInterpolator(new LinearInterpolator());
        travelAnim.setRepeatCount(ValueAnimator.INFINITE);
        travelAnim.addUpdateListener(a -> { travel = (float) a.getAnimatedValue(); invalidate(); });
        travelAnim.start();
    }

    /** يُستدعى عند الخروج لتوفير المعالج والبطارية */
    public void stop() {
        if (introAnim != null) introAnim.cancel();
        if (travelAnim != null) travelAnim.cancel();
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    private boolean animationsOff() {
        try {
            return Settings.Global.getFloat(getContext().getContentResolver(),
                    Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f;
        } catch (Exception e) {
            return false;
        }
    }
}
