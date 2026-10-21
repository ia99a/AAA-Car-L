package com.santafe.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

import java.text.NumberFormat;
import java.util.Calendar;
import java.util.Locale;

/** لغة الواجهة (عربي أو إنجليزي) يختارها المستخدم من زر في شريط السائق، وتنسيق الأوقات بأرقامها */
public final class Lang {

    private Lang() { }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("launcher", Context.MODE_PRIVATE);
    }

    public static String get(Context c) {
        return prefs(c).getString("lang", "ar");
    }

    public static boolean isArabic(Context c) {
        return "ar".equals(get(c));
    }

    public static void toggle(Context c) {
        prefs(c).edit().putString("lang", isArabic(c) ? "en" : "ar").apply();
    }

    /** يُستدعى من attachBaseContext في كل شاشة */
    public static Context wrap(Context base) {
        Locale loc = "en".equals(get(base)) ? Locale.ENGLISH : new Locale("ar", "SA");
        Locale.setDefault(loc);
        Configuration cfg = new Configuration(base.getResources().getConfiguration());
        cfg.setLocale(loc);
        cfg.setLayoutDirection(loc);
        return base.createConfigurationContext(cfg);
    }

    /** "٣:١٢ م" أو "3:12 PM" */
    public static String time(Context c, long millis) {
        return time(c, millis, true);
    }

    /** بدون ص/م: للأماكن الضيقة مثل شريط السائق */
    public static String time(Context c, long millis, boolean suffix) {
        Calendar k = Calendar.getInstance();
        k.setTimeInMillis(millis);
        int h = k.get(Calendar.HOUR_OF_DAY), m = k.get(Calendar.MINUTE);
        boolean am = h < 12;
        h = h % 12 == 0 ? 12 : h % 12;
        if (isArabic(c)) {
            NumberFormat nf = NumberFormat.getInstance(new Locale("ar", "SA"));
            nf.setGroupingUsed(false);
            nf.setMinimumIntegerDigits(1);
            NumberFormat nf2 = NumberFormat.getInstance(new Locale("ar", "SA"));
            nf2.setGroupingUsed(false);
            nf2.setMinimumIntegerDigits(2);
            return nf.format(h) + ":" + nf2.format(m) + (suffix ? (am ? " ص" : " م") : "");
        }
        return String.format(Locale.ENGLISH, "%d:%02d", h, m) + (suffix ? (am ? " AM" : " PM") : "");
    }

    /** المدة الباقية بشكل مختصر: "٤٥ د" أو "١ س ١٢ د" */
    public static String left(Context c, long ms) {
        long mins = Math.max(0, Math.round(ms / 60000.0));
        long h = mins / 60, m = mins % 60;
        boolean ar = isArabic(c);
        NumberFormat nf = NumberFormat.getInstance(ar ? new Locale("ar", "SA") : Locale.ENGLISH);
        nf.setGroupingUsed(false);
        String H = ar ? " س" : "h", M = ar ? " د" : "m";
        if (h == 0) return nf.format(m) + M;
        return nf.format(h) + H + " " + nf.format(m) + M;
    }
}
