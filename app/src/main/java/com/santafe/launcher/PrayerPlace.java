package com.santafe.launcher;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;
import android.location.LocationManager;

import java.util.Locale;

/**
 * مكان حساب أوقات الصلاة: مدينة مختارة، أو «حسب الموقع» من GPS السيارة.
 * آخر موقع يُحفظ في الجهاز فقط ليعمل بلا GPS ولا إنترنت، ويُحدَّث إذا ابتعدت أكثر من ٣ كم.
 */
final class PrayerPlace {
    private PrayerPlace() { }

    static final String AUTO = "auto";
    private static final float MOVE_M = 3000;

    static boolean auto(Context c) { return AUTO.equals(PrayerActivity.cityKey(c)); }

    /** هل عندنا موقع محفوظ لـ«حسب الموقع»؟ */
    static boolean hasFix(Context c) {
        SharedPreferences p = Lang.prefs(c);
        if (p.contains("auto_lat")) return true;
        return seedFromLastKnown(c);
    }

    /** المكان الحالي للحساب */
    static PrayerTimes.City current(Context c) {
        if (!auto(c)) return PrayerTimes.city(PrayerActivity.cityKey(c));
        if (!hasFix(c)) return PrayerTimes.CITIES[0]; // لا موقع بعد: الرياض حتى يصل GPS
        SharedPreferences p = Lang.prefs(c);
        return PrayerTimes.at(Double.longBitsToDouble(p.getLong("auto_lat", 0)), Double.longBitsToDouble(p.getLong("auto_lng", 0)));
    }

    /** مفتاح للذاكرة المؤقتة: يتغيّر إذا تغيّرت المدينة أو تحرّك الموقع المحفوظ */
    static String cacheKey(Context c) {
        if (!auto(c)) return PrayerActivity.cityKey(c);
        PrayerTimes.City k = current(c);
        return String.format(Locale.ROOT, "auto:%.4f,%.4f", k.lat, k.lng);
    }

    /** من GPS (الشاشة الرئيسية): يحفظ الموقع إذا ابتعد ٣ كم أو أكثر، ويرجع true إن تغيّر */
    static boolean update(Context c, double lat, double lng) {
        if (!auto(c)) return false;
        SharedPreferences p = Lang.prefs(c);
        if (p.contains("auto_lat")) {
            float[] d = new float[1];
            Location.distanceBetween(Double.longBitsToDouble(p.getLong("auto_lat", 0)),
                    Double.longBitsToDouble(p.getLong("auto_lng", 0)), lat, lng, d);
            if (d[0] < MOVE_M) return false;
        }
        save(c, lat, lng);
        return true;
    }

    private static void save(Context c, double lat, double lng) {
        Lang.prefs(c).edit().putLong("auto_lat", Double.doubleToRawLongBits(lat))
                .putLong("auto_lng", Double.doubleToRawLongBits(lng)).apply();
    }

    /** أول مرة: آخر موقع يعرفه الجهاز إن وُجد */
    @SuppressLint("MissingPermission")
    private static boolean seedFromLastKnown(Context c) {
        if (!GpsSpeed.hasPermission(c)) return false;
        LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return false;
        Location best = null;
        for (String prov : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER}) {
            try {
                Location l = lm.getLastKnownLocation(prov);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            } catch (Exception ignored) { }
        }
        if (best == null) return false;
        save(c, best.getLatitude(), best.getLongitude());
        return true;
    }
}
