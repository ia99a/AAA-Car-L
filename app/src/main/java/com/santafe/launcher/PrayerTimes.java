package com.santafe.launcher;

import java.util.Calendar;

/**
 * أوقات الصلاة بطريقة أم القرى:
 * الفجر ١٨٫٥°، العشاء بعد المغرب بـ٩٠ دقيقة (١٢٠ في رمضان)، العصر على المذهب الشافعي.
 * نفس حساب «شاشة البيت الهادئة»، منقول إلى جافا. لا يحتاج إنترنت.
 */
public final class PrayerTimes {

    public static final int FAJR = 0, SUNRISE = 1, DHUHR = 2, ASR = 3, MAGHRIB = 4, ISHA = 5;
    /** الصلوات الخمس فقط (بدون الشروق) */
    public static final int[] PRAYERS = {FAJR, DHUHR, ASR, MAGHRIB, ISHA};
    /** دقائق الإقامة بعد الأذان كما في أغلب مساجد المملكة؛ الشروق بلا إقامة */
    public static final int[] IQAMA = {25, 0, 20, 20, 10, 20};

    public static final class City {
        public final String key; public final int name; public final double lat, lng;
        City(String key, int name, double lat, double lng) {
            this.key = key; this.name = name; this.lat = lat; this.lng = lng;
        }
    }

    public static final City[] CITIES = {
            new City("riyadh", R.string.city_riyadh, 24.7136, 46.6753),
            new City("qassim", R.string.city_qassim, 26.3260, 43.9750),
            new City("hail", R.string.city_hail, 27.5114, 41.7208),
            new City("jeddah", R.string.city_jeddah, 21.4858, 39.1925),
            new City("makkah", R.string.city_makkah, 21.4225, 39.8262),
            new City("madinah", R.string.city_madinah, 24.4672, 39.6024),
            new City("taif", R.string.city_taif, 21.2703, 40.4158),
            new City("dammam", R.string.city_dammam, 26.4207, 50.0888),
            new City("ahsa", R.string.city_ahsa, 25.3830, 49.5860),
            new City("abha", R.string.city_abha, 18.2164, 42.5053),
            new City("jazan", R.string.city_jazan, 16.8892, 42.5511),
            new City("tabuk", R.string.city_tabuk, 28.3838, 36.5550),
    };

    public static City city(String key) {
        for (City c : CITIES) if (c.key.equals(key)) return c;
        return CITIES[0];
    }

    private PrayerTimes() { }

    /** يرجع ستة أوقات (بالمللي ثانية) لليوم الذي يحتويه day، بتوقيت السعودية */
    public static long[] forDay(Calendar day, City c) {
        int y = day.get(Calendar.YEAR), m = day.get(Calendar.MONTH) + 1, d = day.get(Calendar.DAY_OF_MONTH);
        boolean ramadan = isRamadan(day);
        double[] t = compute(y, m, d, c.lat, c.lng, 3, ramadan);
        long[] out = new long[6];
        for (int i = 0; i < 6; i++) {
            Calendar x = (Calendar) day.clone();
            x.set(Calendar.HOUR_OF_DAY, 0);
            x.set(Calendar.MINUTE, 0);
            x.set(Calendar.SECOND, 0);
            x.set(Calendar.MILLISECOND, 0);
            x.add(Calendar.MINUTE, (int) Math.round(t[i] * 60));
            out[i] = x.getTimeInMillis();
        }
        return out;
    }

    public static boolean isRamadan(Calendar day) {
        try {
            android.icu.util.IslamicCalendar h = new android.icu.util.IslamicCalendar();
            h.setCalculationType(android.icu.util.IslamicCalendar.CalculationType.ISLAMIC_UMALQURA);
            h.setTimeInMillis(day.getTimeInMillis());
            return h.get(android.icu.util.Calendar.MONTH) == 8; // رمضان هو الشهر التاسع (يبدأ العد من صفر)
        } catch (Throwable e) {
            return false;
        }
    }

    // ───── الحساب الفلكي ─────

    private static double rad(double x) { return Math.toRadians(x); }
    private static double deg(double x) { return Math.toDegrees(x); }
    private static double sin(double x) { return Math.sin(rad(x)); }
    private static double cos(double x) { return Math.cos(rad(x)); }
    private static double tan(double x) { return Math.tan(rad(x)); }
    private static double asin(double x) { return deg(Math.asin(x)); }
    private static double acos(double x) { return deg(Math.acos(Math.max(-1, Math.min(1, x)))); }
    private static double acot(double x) { return deg(Math.atan(1 / x)); }
    private static double atan2(double y, double x) { return deg(Math.atan2(y, x)); }
    private static double fix(double a, double b) { a = a - b * Math.floor(a / b); return a < 0 ? a + b : a; }

    private static double[] compute(int y, int m, int d, double lat, double lng, double tz, boolean ramadan) {
        if (m <= 2) { y--; m += 12; }
        double A = Math.floor(y / 100.0), B = 2 - A + Math.floor(A / 4);
        final double jd = Math.floor(365.25 * (y + 4716)) + Math.floor(30.6001 * (m + 1)) + d + B - 1524.5 - lng / (15 * 24);

        double[] t = {5, 6, 12, 13, 18};
        for (int i = 0; i < 2; i++) {
            t = new double[]{
                    angle(jd, lat, 18.5, t[0] / 24, true),
                    angle(jd, lat, 0.833, t[1] / 24, true),
                    noon(jd, t[2] / 24),
                    asr(jd, lat, t[3] / 24),
                    angle(jd, lat, 0.833, t[4] / 24, false)};
        }
        double adj = tz - lng / 15;
        double[] out = new double[6];
        for (int i = 0; i < 5; i++) out[i] = t[i] + adj;
        out[ISHA] = out[MAGHRIB] + (ramadan ? 2 : 1.5);
        return out;
    }

    /** {الميل، معادلة الزمن} */
    private static double[] sun(double jd, double t) {
        double D = jd - 2451545 + t;
        double g = fix(357.529 + 0.98560028 * D, 360), q = fix(280.459 + 0.98564736 * D, 360);
        double L = fix(q + 1.915 * sin(g) + 0.020 * sin(2 * g), 360);
        double e = 23.439 - 0.00000036 * D;
        double RA = fix(atan2(cos(e) * sin(L), cos(L)) / 15, 24);
        return new double[]{asin(sin(e) * sin(L)), q / 15 - RA};
    }

    private static double noon(double jd, double t) { return fix(12 - sun(jd, t)[1], 24); }

    private static double angle(double jd, double lat, double a, double t, boolean ccw) {
        double dec = sun(jd, t)[0];
        double T = acos((-sin(a) - sin(dec) * sin(lat)) / (cos(dec) * cos(lat))) / 15;
        return noon(jd, t) + (ccw ? -T : T);
    }

    private static double asr(double jd, double lat, double t) {
        double dec = sun(jd, t)[0];
        return angle(jd, lat, -acot(1 + tan(Math.abs(lat - dec))), t, false);
    }
}
