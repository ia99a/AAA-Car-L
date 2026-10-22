package com.santafe.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

/**
 * فقاعة مؤقت الإقامة بجانب أداة الصلاة: كبسولة صغيرة بسهم يشير للشريط،
 * فيها «الإقامة بعد» والعدّ بالدقائق والثواني. أخضر عادةً، وذهبي في آخر دقيقة.
 */
public class IqamaBubble extends View {

    private final float dp;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG), digits = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path shape = new Path();
    private String labelText = "", digitText = "";
    private boolean gold;
    private int alpha = 255;

    public IqamaBubble(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        float sp = c.getResources().getDisplayMetrics().scaledDensity;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(Math.max(1, dp));
        label.setTextSize(10 * sp);
        digits.setTextSize(15 * sp);
        digits.setTypeface(Typeface.DEFAULT_BOLD);
        setClickable(true);
        setFocusable(true);
        applyColors();
    }

    private static final float ARROW = 6, PAD = 10, GAP = 6, HEIGHT = 30;

    /** النص: label مثل «الإقامة بعد»، و digits مثل «٠٨:٤١» (فارغ عند «أقيمت الصلاة») */
    void set(String labelText, String digitText, boolean gold) {
        this.labelText = labelText;
        this.digitText = digitText;
        // الأرقام العربية ليست بعرض واحد: نعيد القياس كلما تغيّر العرض اللازم حتى لا يُقص الرقم
        boolean resize = desiredWidth() != getMeasuredWidth();
        if (gold != this.gold) { this.gold = gold; applyColors(); }
        setContentDescription(labelText + " " + digitText);
        if (resize) requestLayout();
        invalidate();
    }

    /** شفافية الخلفية من الإعدادات (النص يبقى واضحًا) */
    void setBackgroundOpacity(int alpha255) {
        alpha = alpha255;
        applyColors();
        invalidate();
    }

    private void applyColors() {
        int bg = gold ? 0x2C2416 : 0x1D2A24;
        fill.setColor((alpha << 24) | bg);
        stroke.setColor(((int) (alpha * (gold ? 0.67f : 0.33f)) << 24) | (gold ? 0xC9A35A : 0x3FBF7F));
        label.setColor(gold ? 0xFFE3C27A : 0xFFA8E3C5);
        digits.setColor(gold ? 0xFFF3DFAE : 0xFFFFFFFF);
    }

    private boolean rtl() { return getLayoutDirection() == LAYOUT_DIRECTION_RTL; }

    private int desiredWidth() {
        float content = label.measureText(labelText) + (digitText.isEmpty() ? 0 : GAP * dp + digits.measureText(digitText));
        return (int) Math.ceil((ARROW + PAD * 2) * dp + content);
    }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(desiredWidth(), (int) (HEIGHT * dp));
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), a = ARROW * dp, s = stroke.getStrokeWidth() / 2;
        // كبسولة تبدأ بعد السهم، والسهم يشير يسارًا نحو الشريط
        shape.reset();
        shape.addRoundRect(new RectF(a + s, s, w - s, h - s), h / 2, h / 2, Path.Direction.CW);
        Path arrow = new Path();
        arrow.moveTo(s, h / 2);
        arrow.lineTo(a + h * 0.25f, h / 2 - a);
        arrow.lineTo(a + h * 0.25f, h / 2 + a);
        arrow.close();
        shape.op(arrow, Path.Op.UNION);
        c.drawPath(shape, fill);
        c.drawPath(shape, stroke);

        float base = h / 2 - (digits.descent() + digits.ascent()) / 2;
        float lw = label.measureText(labelText), dw = digitText.isEmpty() ? 0 : digits.measureText(digitText);
        float left = a + PAD * dp, right = w - PAD * dp;
        float lBase = h / 2 - (label.descent() + label.ascent()) / 2;
        if (rtl()) { // العربية: «الإقامة بعد» يمينًا ثم الأرقام
            c.drawText(labelText, right - lw, lBase, label);
            if (dw > 0) c.drawText(digitText, left, base, digits);
        } else {
            c.drawText(labelText, left, lBase, label);
            if (dw > 0) c.drawText(digitText, right - dw, base, digits);
        }
    }
}
