package com.santafe.launcher;

import java.util.ArrayList;
import java.util.List;

/**
 * منطق تنبيهات الطريق، بلا أي اعتماد على أندرويد حتى يُختبر وحده:
 * يجد أقرب كاميرا أو مطب أمام السيارة في اتجاه سيرها.
 */
public final class Hazards {

    public static final int CAMERA = 0, BUMP = 1;

    /** مسافة التنبيه بالمتر: الكاميرا أبكر لأن السائق يحتاج وقتًا ليخفف */
    static final double CAMERA_RANGE = 500, BUMP_RANGE = 200;
    /** أقصى فرق بين اتجاه السير واتجاه الخطر حتى يُعدّ «أمامك» */
    static final double AHEAD_DEG = 35;
    /** لا تنبيه والسيارة شبه واقفة */
    static final double MIN_SPEED_KMH = 8;

    public static final class Poi {
        public final long id; public final double lat, lng; public final int kind;
        public Poi(long id, double lat, double lng, int kind) { this.id = id; this.lat = lat; this.lng = lng; this.kind = kind; }
    }

    public static final class Hit {
        public final Poi poi; public final double meters;
        Hit(Poi p, double m) { poi = p; meters = m; }
    }

    private Hazards() { }

    /** المسافة بالمتر بين نقطتين (هافرساين) */
    public static double distance(double lat1, double lng1, double lat2, double lng2) {
        double R = 6371000, dLat = Math.toRadians(lat2 - lat1), dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * R * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    /** الاتجاه من النقطة الأولى إلى الثانية بالدرجات (٠ = الشمال) */
    public static double bearing(double lat1, double lng1, double lat2, double lng2) {
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2), dl = Math.toRadians(lng2 - lng1);
        double y = Math.sin(dl) * Math.cos(p2);
        double x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dl);
        return (Math.toDegrees(Math.atan2(y, x)) + 360) % 360;
    }

    static double angleDiff(double a, double b) {
        double d = Math.abs(a - b) % 360;
        return d > 180 ? 360 - d : d;
    }

    /** أقرب خطر أمامك في مدى التنبيه، أو null */
    public static Hit ahead(double lat, double lng, double heading, double speedKmh, List<Poi> pois) {
        if (heading < 0 || speedKmh < MIN_SPEED_KMH || pois == null) return null;
        Hit best = null;
        for (Poi p : pois) {
            double d = distance(lat, lng, p.lat, p.lng);
            double range = p.kind == CAMERA ? CAMERA_RANGE : BUMP_RANGE;
            if (d > range || d < 5) continue;
            if (angleDiff(heading, bearing(lat, lng, p.lat, p.lng)) > AHEAD_DEG) continue;
            if (best == null || d < best.meters) best = new Hit(p, d);
        }
        return best;
    }

    /** يقرأ ردّ Overpass (JSON) إلى قائمة نقاط. نصّي حتى لا نحتاج مكتبة. */
    public static List<Poi> parseOverpass(org.json.JSONObject root) throws org.json.JSONException {
        List<Poi> out = new ArrayList<>();
        org.json.JSONArray els = root.optJSONArray("elements");
        if (els == null) return out;
        for (int i = 0; i < els.length() && out.size() < 2000; i++) {
            org.json.JSONObject e = els.getJSONObject(i);
            if (!"node".equals(e.optString("type"))) continue;
            org.json.JSONObject tags = e.optJSONObject("tags");
            if (tags == null) continue;
            int kind;
            if ("speed_camera".equals(tags.optString("highway"))) kind = CAMERA;
            else if (tags.has("traffic_calming") && !"no".equals(tags.optString("traffic_calming"))) kind = BUMP;
            else continue;
            double la = e.optDouble("lat", Double.NaN), lo = e.optDouble("lon", Double.NaN);
            if (Double.isNaN(la) || Double.isNaN(lo) || Math.abs(la) > 90 || Math.abs(lo) > 180) continue;
            out.add(new Poi(e.optLong("id"), la, lo, kind));
        }
        return out;
    }
}
