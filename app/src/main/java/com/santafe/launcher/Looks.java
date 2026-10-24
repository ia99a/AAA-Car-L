package com.santafe.launcher;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;

/**
 * مظهر بطاقة الساعة ولوحة السرعة، وخيارات الوقت والتاريخ والسرعة (كلها من الإعدادات).
 * «متناسق» يأخذ ألوانه من لون الخلفية المختار، فتبقى الواجهة كلها بطابع واحد.
 */
final class Looks {
    private Looks() { }

    static final String[] STYLES = {"auto", "glass", "blue", "gold", "green", "burgundy"};
    static final int[] STYLE_NAMES = {R.string.look_auto, R.string.look_glass, R.string.look_blue,
            R.string.look_gold, R.string.look_green, R.string.look_burgundy};
    /** {أعلى التدرج، أسفله، الإطار، لون النص، لون الأيقونة} */
    private static final int[][] FIXED = {
            null,
            {0xE61E2126, 0xE616181C, 0x33FFFFFF, 0xFFEEF0F3, 0xFFEBA53C},
            {0xFF3B7DDD, 0xFF2A5FB8, 0x00000000, 0xFFFFFFFF, 0xFFFFFFFF},
            {0xF06B5428, 0xF03A2F19, 0x55E6C17A, 0xFFF7E9C8, 0xFFF2CF7E},
            {0xF01F6B4C, 0xF00F3D2C, 0x4466D19E, 0xFFE6F5EE, 0xFFA8E3C5},
            {0xF07A2B36, 0xF04A1820, 0x44E08A96, 0xFFF8E6E8, 0xFFF0B3BC},
    };

    static int style(Context c, String key) {
        String v = Lang.prefs(c).getString(key, "auto");
        for (int i = 0; i < STYLES.length; i++) if (STYLES[i].equals(v)) return i;
        return 0;
    }

    static void setStyle(Context c, String key, int i) {
        Lang.prefs(c).edit().putString(key, STYLES[i]).apply();
    }

    /** ألوان النمط: للنمط «متناسق» تُشتق من لون أرضية النقش المختار */
    static int[] colors(Context c, int style) {
        if (style > 0) return FIXED[style];
        int ground = PatternView.COLORS[PatternView.current(c)][1];
        boolean gold = PatternView.KEYS[PatternView.current(c)].equals("gold");
        if (gold) return FIXED[3];
        return new int[]{0xF0000000 | mix(ground, 0xFFFFFF, 0.16f), 0xF0000000 | mix(ground, 0xFFFFFF, 0.05f),
                0x40000000 | mix(ground, 0xFFFFFF, 0.5f), 0xFFF2F3F5, 0xFF000000 | mix(ground, 0xEBA53C, 0.75f)};
    }

    static GradientDrawable background(Context c, int style, float radiusDp) {
        int[] k = colors(c, style);
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{k[0], k[1]});
        float dp = c.getResources().getDisplayMetrics().density;
        d.setCornerRadius(radiusDp * dp);
        if ((k[2] >>> 24) != 0) d.setStroke(Math.max(1, (int) dp), k[2]);
        return d;
    }

    private static int mix(int a, int b, float t) {
        int r = (int) (((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
        int g = (int) (((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
        int bl = (int) ((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
        return (r << 16) | (g << 8) | bl;
    }

    // ───── الشفافية ─────

    /** شفافية خلفيات عناصر الرئيسية: ١٠٠ معتمة تمامًا، ٠ شفافة تمامًا (النص والأيقونات تبقى ظاهرة) */
    static int opacity(Context c) {
        int v = Lang.prefs(c).getInt("ui_opacity", 100);
        return Math.max(0, Math.min(100, v / 10 * 10));
    }

    static void setOpacity(Context c, int v) { Lang.prefs(c).edit().putInt("ui_opacity", v).apply(); }

    // ───── الوقت والتاريخ ─────

    static boolean clock24(Context c) { return Lang.prefs(c).getBoolean("clock_24h", false); }

    /** "greg" ميلادي، "hijri" هجري أم القرى، "none" بلا تاريخ */
    static String dateMode(Context c) { return Lang.prefs(c).getString("date_mode", "greg"); }

    static String dateText(Context c, long now) {
        String mode = dateMode(c);
        if ("none".equals(mode)) return "";
        boolean ar = Lang.isArabic(c);
        boolean hijri = "hijri".equals(mode);
        android.icu.util.ULocale loc = new android.icu.util.ULocale(
                (ar ? "ar_SA" : "en") + "@calendar=" + (hijri ? "islamic-umalqura" : "gregorian"));
        String pattern = hijri ? (ar ? "EEEE، d MMMM" : "EEEE, d MMMM") : c.getString(R.string.date_format);
        android.icu.text.SimpleDateFormat f = new android.icu.text.SimpleDateFormat(pattern, loc);
        return f.format(new java.util.Date(now));
    }

    // ───── السرعة ─────

    /** حد تنبيه السرعة (كم/س)، ٠ = بلا تنبيه */
    static int speedLimit(Context c) { return Lang.prefs(c).getInt("speed_limit", 0); }
}
