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
 * خريطة داكنة (Esri أو OpenStreetMap) تتبع موقعك من GPS السيارة،
 * وعليها كاميرات السرعة والمطبات من OpenStreetMap، مع تنبيه صوتي ومكتوب حين تقترب منها.
 * الصفحة تُخدم من أصل https افتراضي داخل التطبيق (لا file://)، والبلاطات من Esri ثم OpenStreetMap احتياطًا (بلا مفتاح API).
 * إن تعذّر تحميل الخريطة تبقى خلفية النقش ظاهرة، ويظهر سبب واضح (لا إنترنت، تاريخ خاطئ، رفض الخادم...).
 * في وضع خلفية النقش لا تُحمَّل الخريطة أصلًا، ويبقى GPS للسرعة وتنبيهات الكاميرات والمطبات.
 */
final class LiveMap implements LocationListener {

    interface Listener {
        void onSpeed(double kmh, boolean moving);
        /** reason == null: الخريطة تعمل */
        void onMapStatus(String reason);
        /** كل موقع جديد (للخرائط المصغّرة في بطاقتي قوقل وهدهد) */
        void onPosition(double lat, double lng, double heading);
    }

    /** أصل افتراضي لملفات الصفحة (نفس النطاق الذي تستخدمه مكتبة أندرويد WebViewAssetLoader) */
    static final String ORIGIN_HOST = "appassets.androidplatform.net";
    static final String PAGE = "https://" + ORIGIN_HOST + "/map/index.html";

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

    private final boolean display;
    private boolean pageReady, shown, failed;
    private String lastError;
    private Location last;
    private double heading = -1;
    private volatile List<Hazards.Poi> pois = new ArrayList<>();
    private double fetchLat = Double.NaN, fetchLng = Double.NaN;
    private long fetchAt;
    private boolean fetching;
    private final Set<Long> alerted = new HashSet<>();
    private ToneGenerator tone;

    @SuppressLint("SetJavaScriptEnabled")
    LiveMap(Activity act, WebView web, View fallback, View alertBox, TextView alertText, TextView alertDist,
            boolean display, Listener l) {
        this.act = act; this.web = web; this.fallback = fallback; this.display = display;
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
        web.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        web.setVisibility(View.INVISIBLE);
        if (!display) return; // خلفية النقش: لا نحمّل الخريطة
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) { return true; } // لا تنقّل أبدًا

            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
                if (!"https".equals(u.getScheme())) return blocked();
                if (ORIGIN_HOST.equals(host)) return asset(act, u.getPath());
                return isTileHost(host) ? null : blocked();
            }

            @Override public void onPageFinished(WebView v, String url) {
                pageReady = true;
                pushCity();
                if (last != null) pushPosition();
                pushPois();
            }

            @Override public void onReceivedError(WebView v, WebResourceRequest r, android.webkit.WebResourceError e) {
                if (android.os.Build.VERSION.SDK_INT >= 23)
                    lastError = reasonFor(act, e.getErrorCode(), 0, String.valueOf(e.getDescription()));
            }

            @Override public void onReceivedHttpError(WebView v, WebResourceRequest r, WebResourceResponse resp) {
                String h = r.getUrl().getHost();
                if (h != null && isTileHost(h)) lastError = reasonFor(act, 0, resp.getStatusCode(), null);
            }

            @Override public void onReceivedSslError(WebView v, android.webkit.SslErrorHandler h, android.net.http.SslError e) {
                lastError = act.getString(R.string.map_err_ssl);
                h.cancel(); // لا نتجاوز أخطاء الشهادات أبدًا
            }
        });
        // حالة البلاطات تصل عبر عنوان الصفحة: tiles:ok أو tiles:fail
        web.setWebChromeClient(new android.webkit.WebChromeClient() {
            @Override public void onReceivedTitle(WebView v, String title) {
                if (title == null) return;
                if (title.startsWith("tiles:ok")) {
                    failed = false; lastError = null;
                    listener.onMapStatus(null);
                    reveal();
                } else if (title.startsWith("tiles:fail")) {
                    failed = true;
                    listener.onMapStatus(lastError != null ? lastError : offlineReason());
                    ui.removeCallbacks(retry);
                    ui.postDelayed(retry, 60_000); // نحاول تلقائيًا كل دقيقة
                }
            }
        });
        web.loadUrl(PAGE);
    }

    private final Runnable retry = this::retryNow;

    /** إعادة المحاولة (تلقائيًا كل دقيقة عند الفشل، أو بالضغط على رسالة الخطأ) */
    void retryNow() {
        ui.removeCallbacks(retry);
        if (!display) return;
        lastError = null;
        if (pageReady) web.evaluateJavascript("retryTiles()", null); else web.loadUrl(PAGE);
    }

    static boolean isTileHost(String host) {
        return host.equals("server.arcgisonline.com") || host.equals("tile.openstreetmap.org");
    }

    /** ملفات الصفحات من مجلدَي map و browser داخل التطبيق فقط */
    static WebResourceResponse asset(android.content.Context c, String path) {
        if (path == null || !(path.startsWith("/map/") || path.startsWith("/browser/")) || path.contains("..")) return blocked();
        String mime = path.endsWith(".html") ? "text/html" : path.endsWith(".js") ? "application/javascript"
                : path.endsWith(".css") ? "text/css" : path.endsWith(".png") ? "image/png" : null;
        if (mime == null) return blocked();
        try {
            return new WebResourceResponse(mime, "utf-8", c.getAssets().open(path.substring(1)));
        } catch (java.io.IOException e) {
            return blocked();
        }
    }

    private String offlineReason() {
        android.net.ConnectivityManager cm = (android.net.ConnectivityManager) act.getSystemService(Activity.CONNECTIVITY_SERVICE);
        android.net.NetworkInfo ni = cm == null ? null : cm.getActiveNetworkInfo();
        return act.getString(ni == null || !ni.isConnected() ? R.string.map_err_offline : R.string.map_err_unknown);
    }

    /** سبب مفهوم للسائق من رمز الخطأ */
    static String reasonFor(android.content.Context c, int code, int http, String desc) {
        if (http == 401 || http == 403 || http == 429) return c.getString(R.string.map_err_refused, http);
        if (http >= 400) return c.getString(R.string.map_err_server, http);
        switch (code) {
            case WebViewClient.ERROR_HOST_LOOKUP: return c.getString(R.string.map_err_dns);
            case WebViewClient.ERROR_CONNECT:
            case WebViewClient.ERROR_TIMEOUT:
            case WebViewClient.ERROR_IO: return c.getString(R.string.map_err_connect);
            case WebViewClient.ERROR_FAILED_SSL_HANDSHAKE: return c.getString(R.string.map_err_ssl);
            default: return desc == null || desc.isEmpty() ? c.getString(R.string.map_err_unknown) : desc;
        }
    }

    static WebResourceResponse blocked() {
        return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
    }

    static boolean hasPermission(Activity a) {
        return a.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || a.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    @SuppressLint("MissingPermission")
    /** خيارات رادار الكاميرات من الإعدادات (تُقرأ عند كل عودة للرئيسية) */
    private double camRange = Hazards.CAMERA_RANGE, bumpRange = Hazards.BUMP_RANGE;
    private boolean sound = true;

    void start() {
        camRange = Looks.radarCameras(act) ? Looks.radarDistance(act) : 0;
        bumpRange = Looks.radarBumps(act) ? Hazards.BUMP_RANGE : 0;
        sound = Looks.radarSound(act);
        web.onResume();
        if (failed) retryNow();
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

    private boolean destroyed;

    void destroy() {
        destroyed = true;
        ui.removeCallbacks(retry);
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
        listener.onPosition(loc.getLatitude(), loc.getLongitude(), heading);
        if (pageReady) pushPosition();
        maybeFetch();
        checkAlerts(kmh);
    }

    @Override public void onStatusChanged(String p, int s, Bundle b) { }
    @Override public void onProviderEnabled(String p) { }
    @Override public void onProviderDisabled(String p) { }

    private void reveal() {
        if (shown || !display) return;
        shown = true;
        web.setAlpha(0f);
        web.setVisibility(View.VISIBLE);
        // بعد ظهور الخريطة نخفي النقش تحتها فلا يُرسم بلا فائدة
        web.animate().alpha(1f).setDuration(700).withEndAction(() -> fallback.setVisibility(View.INVISIBLE)).start();
    }

    private double pushedLat = Double.NaN, pushedLng, pushedHeading;
    private long pushedAt;

    /** لا نرسل للصفحة إلا ما يغيّر الصورة: حركة متر ونصف، أو استدارة ٤ درجات، أو كل ٣ ثوانٍ */
    private void pushPosition() {
        if (!display) return;
        double la = last.getLatitude(), lo = last.getLongitude();
        long now = android.os.SystemClock.uptimeMillis();
        if (!Double.isNaN(pushedLat) && Hazards.distance(la, lo, pushedLat, pushedLng) < 1.5
                && Hazards.angleDiff(heading, pushedHeading) < 4 && now - pushedAt < 3000) return;
        pushedLat = la; pushedLng = lo; pushedHeading = heading; pushedAt = now;
        web.evaluateJavascript(String.format(Locale.US, "setPos(%.6f,%.6f,%.1f)", la, lo, heading), null);
    }

    /** قبل أول موقع: نتوسط مدينة الصلاة المختارة */
    private void pushCity() {
        if (last != null) return;
        PrayerTimes.City c = PrayerTimes.city(PrayerActivity.cityKey(act));
        web.evaluateJavascript(String.format(Locale.US, "map.setView([%.5f,%.5f],13)", c.lat, c.lng), null);
    }

    private void pushPois() {
        if (!pageReady || !display) return;
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
                if (destroyed) return; // أُغلقت الشاشة أثناء التحميل
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
            c.setRequestProperty("User-Agent", "AAA-Car-L/2.0 (car launcher)");
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
        Hazards.Hit hit = Hazards.ahead(last.getLatitude(), last.getLongitude(), heading, kmh, pois, camRange, bumpRange);
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
        if (alerted.add(hit.poi.id) && sound) beep(cam);
    }

    private void beep(boolean cam) {
        try {
            if (tone == null) tone = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90);
            tone.startTone(cam ? ToneGenerator.TONE_PROP_BEEP2 : ToneGenerator.TONE_PROP_BEEP, cam ? 400 : 200);
        } catch (Exception ignored) { }
    }
}
