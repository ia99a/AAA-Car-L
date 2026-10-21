package com.santafe.launcher;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * الخريطة الحية خلف الشاشة الرئيسية:
 * خريطة داكنة (OpenStreetMap عبر CARTO) تتبع موقعك من GPS السيارة،
 * وعليها كاميرات السرعة والمطبات من OpenStreetMap، مع تنبيه صوتي ومكتوب حين تقترب منها.
 * إن لم يُسمح بالموقع أو لم يتوفر إنترنت، تبقى الخلفية المرسومة القديمة ظاهرة.
 */
final class LiveMap implements LocationListener {

    interface Listener { void onSpeed(double kmh, boolean moving); }

    private static final String OVERPASS = "https://overpass-api.de/api/interpreter";
    private static final double REFETCH_METERS = 2000, FETCH_RADIUS = 4000;
    private static final long REFETCH_MIN_MS = 60_000;
    /** فوق هذه السرعة نعدّ السيارة متحركة */
    static final double MOVING_KMH = 8;

    private final Activity act;
    private final WebView web;
    private final View fallback;
    private final View alertBox;
    private final TextView alertText, alertDist;
    private final Listener listener;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private LocationManager lm;

    private boolean pageReady, shown;
    private Location last;
    private double heading = -1;
    private volatile List<Hazards.Poi> pois = new ArrayList<>();
    private double fetchLat = Double.NaN, fetchLng = Double.NaN;
    private long fetchAt;
    private boolean fetching;
    private final Set<Long> alerted = new HashSet<>();
    private ToneGenerator tone;

    @SuppressLint("SetJavaScriptEnabled")
    LiveMap(Activity act, WebView web, View fallback, View alertBox, TextView alertText, TextView alertDist, Listener l) {
        this.act = act; this.web = web; this.fallback = fallback;
        this.alertBox = alertBox; this.alertText = alertText; this.alertDist = alertDist; this.listener = l;

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);           // لازم لمكتبة الخريطة المحلية فقط
        s.setAllowFileAccess(false);            // لا وصول لملفات الجهاز (الأصول المضمّنة مستثناة)
        s.setAllowContentAccess(false);
        s.setGeolocationEnabled(false);         // الموقع يأتي من التطبيق، لا من الصفحة
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setSupportMultipleWindows(false);
        s.setDomStorageEnabled(false);
        s.setBuiltInZoomControls(false);
        if (android.os.Build.VERSION.SDK_INT >= 26) s.setSafeBrowsingEnabled(true);
        web.setBackgroundColor(act.getColor(R.color.map_bg));
        web.setVisibility(View.INVISIBLE);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) { return true; } // لا تنقّل أبدًا

            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                String scheme = u.getScheme(), host = u.getHost() == null ? "" : u.getHost();
                boolean asset = "file".equals(scheme) && u.getPath() != null && u.getPath().startsWith("/android_asset/map/");
                boolean tile = "https".equals(scheme) && host.endsWith(".basemaps.cartocdn.com");
                return (asset || tile) ? null : blocked();
            }

            @Override public void onPageFinished(WebView v, String url) {
                pageReady = true;
                pushCity();
                if (last != null) pushPosition();
                pushPois();
            }
        });
        web.loadUrl("file:///android_asset/map/index.html");
    }

    static WebResourceResponse blocked() {
        return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
    }

    static boolean hasPermission(Activity a) {
        return a.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || a.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    @SuppressLint("MissingPermission")
    void start() {
        web.onResume();
        if (!hasPermission(act)) return;
        lm = (LocationManager) act.getSystemService(Activity.LOCATION_SERVICE);
        if (lm == null) return;
        try {
            for (String p : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
                if (!lm.isProviderEnabled(p)) continue;
                lm.requestLocationUpdates(p, 1000, 3, this, Looper.getMainLooper());
                Location k = lm.getLastKnownLocation(p);
                if (k != null && (last == null || k.getTime() > last.getTime())) onLocationChanged(k);
            }
        } catch (Exception ignored) { }
    }

    void stop() {
        web.onPause();
        if (lm != null) try { lm.removeUpdates(this); } catch (Exception ignored) { }
    }

    void destroy() {
        stop();
        net.shutdownNow();
        if (tone != null) tone.release();
        web.destroy();
    }

    // ───── الموقع ─────

    @Override public void onLocationChanged(Location loc) {
        if (loc == null) return;
        if (last != null && LocationManager.NETWORK_PROVIDER.equals(loc.getProvider())
                && LocationManager.GPS_PROVIDER.equals(last.getProvider())
                && loc.getTime() - last.getTime() < 10_000) return; // GPS أدق؛ لا نستبدله بموقع الشبكة
        if (loc.hasBearing() && loc.getSpeed() > 1) heading = loc.getBearing();
        else if (last != null && Hazards.distance(last.getLatitude(), last.getLongitude(), loc.getLatitude(), loc.getLongitude()) > 8)
            heading = Hazards.bearing(last.getLatitude(), last.getLongitude(), loc.getLatitude(), loc.getLongitude());
        last = loc;
        double kmh = loc.hasSpeed() ? loc.getSpeed() * 3.6 : 0;
        listener.onSpeed(kmh, kmh >= MOVING_KMH);
        if (pageReady) pushPosition();
        reveal();
        maybeFetch();
        checkAlerts(kmh);
    }

    @Override public void onStatusChanged(String p, int s, Bundle b) { }
    @Override public void onProviderEnabled(String p) { }
    @Override public void onProviderDisabled(String p) { }

    private void reveal() {
        if (shown) return;
        shown = true;
        web.setAlpha(0f);
        web.setVisibility(View.VISIBLE);
        web.animate().alpha(1f).setDuration(700).withEndAction(() -> fallback.setVisibility(View.INVISIBLE)).start();
    }

    private void pushPosition() {
        web.evaluateJavascript(String.format(Locale.US, "setPos(%.6f,%.6f,%.1f)",
                last.getLatitude(), last.getLongitude(), heading), null);
    }

    /** قبل أول موقع: نتوسط مدينة الصلاة المختارة */
    private void pushCity() {
        if (last != null) return;
        PrayerTimes.City c = PrayerTimes.city(PrayerActivity.cityKey(act));
        web.evaluateJavascript(String.format(Locale.US, "map.setView([%.5f,%.5f],13)", c.lat, c.lng), null);
    }

    private void pushPois() {
        if (!pageReady) return;
        StringBuilder sb = new StringBuilder("setPois([");
        List<Hazards.Poi> list = pois;
        for (int i = 0; i < list.size(); i++) {
            Hazards.Poi p = list.get(i);
            if (i > 0) sb.append(',');
            sb.append(String.format(Locale.US, "[%.6f,%.6f,%d]", p.lat, p.lng, p.kind));
        }
        web.evaluateJavascript(sb.append("])").toString(), null);
    }

    // ───── الكاميرات والمطبات من OpenStreetMap ─────

    private void maybeFetch() {
        if (fetching || last == null) return;
        double la = last.getLatitude(), lo = last.getLongitude();
        boolean far = Double.isNaN(fetchLat) || Hazards.distance(la, lo, fetchLat, fetchLng) > REFETCH_METERS;
        if (!far || System.currentTimeMillis() - fetchAt < REFETCH_MIN_MS) return;
        fetching = true;
        fetchAt = System.currentTimeMillis();
        net.execute(() -> {
            List<Hazards.Poi> got = null;
            try { got = fetch(la, lo); } catch (Exception ignored) { }
            List<Hazards.Poi> result = got;
            ui.post(() -> {
                fetching = false;
                if (result == null) return; // بلا إنترنت: نحاول بعد دقيقة
                fetchLat = la; fetchLng = lo;
                pois = result;
                pushPois();
            });
        });
    }

    private static List<Hazards.Poi> fetch(double lat, double lng) throws Exception {
        String q = String.format(Locale.US,
                "[out:json][timeout:20];(node[\"highway\"=\"speed_camera\"](around:%.0f,%.6f,%.6f);" +
                "node[\"traffic_calming\"](around:%.0f,%.6f,%.6f););out;",
                FETCH_RADIUS, lat, lng, FETCH_RADIUS, lat, lng);
        HttpURLConnection c = (HttpURLConnection) new URL(OVERPASS).openConnection();
        try {
            c.setConnectTimeout(10_000);
            c.setReadTimeout(25_000);
            c.setDoOutput(true);
            c.setRequestMethod("POST");
            c.setRequestProperty("User-Agent", "AAA-Car-L/1.5 (car launcher)");
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            try (OutputStream o = c.getOutputStream()) { o.write(("data=" + URLEncoder.encode(q, "UTF-8")).getBytes("UTF-8")); }
            if (c.getResponseCode() != 200) return null;
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] b = new byte[16384];
                int n, total = 0;
                while ((n = in.read(b)) > 0) {
                    total += n;
                    if (total > 4_000_000) return null; // ردّ غير طبيعي: نتجاهله
                    buf.write(b, 0, n);
                }
            }
            return Hazards.parseOverpass(new org.json.JSONObject(buf.toString("UTF-8")));
        } finally {
            c.disconnect();
        }
    }

    // ───── التنبيه ─────

    private void checkAlerts(double kmh) {
        // ننسى التنبيهات القديمة حين نبتعد عنها، فيُعاد التنبيه إن مررنا بها مرة أخرى لاحقًا
        if (!alerted.isEmpty()) {
            List<Hazards.Poi> list = pois;
            for (Hazards.Poi p : list) {
                if (alerted.contains(p.id) && Hazards.distance(last.getLatitude(), last.getLongitude(), p.lat, p.lng) > 1000)
                    alerted.remove(p.id);
            }
        }
        Hazards.Hit hit = Hazards.ahead(last.getLatitude(), last.getLongitude(), heading, kmh, pois);
        if (hit == null) {
            if (alertBox.getVisibility() == View.VISIBLE) alertBox.animate().alpha(0f).setDuration(300)
                    .withEndAction(() -> alertBox.setVisibility(View.GONE)).start();
            return;
        }
        boolean cam = hit.poi.kind == Hazards.CAMERA;
        alertText.setText(cam ? R.string.alert_camera : R.string.alert_bump);
        alertDist.setText(Lang.meters(act, hit.meters));
        alertBox.setBackgroundResource(cam ? R.drawable.alert_cam_bg : R.drawable.alert_bump_bg);
        ((android.widget.ImageView) alertBox.findViewById(R.id.alert_icon))
                .setImageResource(cam ? R.drawable.ic_camera_alert : R.drawable.ic_bump_alert);
        if (alertBox.getVisibility() != View.VISIBLE) {
            alertBox.setAlpha(0f);
            alertBox.setVisibility(View.VISIBLE);
            alertBox.animate().alpha(1f).setDuration(250).start();
        }
        if (alerted.add(hit.poi.id)) beep(cam);
    }

    private void beep(boolean cam) {
        try {
            if (tone == null) tone = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90);
            tone.startTone(cam ? ToneGenerator.TONE_PROP_BEEP2 : ToneGenerator.TONE_PROP_BEEP, cam ? 400 : 200);
        } catch (Exception ignored) { }
    }
}
