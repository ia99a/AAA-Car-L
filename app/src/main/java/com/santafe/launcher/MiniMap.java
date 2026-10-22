package com.santafe.launcher;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.net.Uri;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.util.Locale;

/**
 * خريطة حية مصغّرة داخل بطاقة خرائط قوقل أو هدهد: موقعك فقط، بنفس صفحة الخريطة الآمنة.
 * أندرويد لا يسمح بعرض شاشة تطبيق آخر داخل المشغّل، فهذه خريطتنا (OpenStreetMap) بطابع كل تطبيق:
 * فاتحة ملوّنة لبطاقة قوقل، وداكنة لبطاقة هدهد. الضغط عليها يفتح التطبيق نفسه.
 */
final class MiniMap {

    private final Activity act;
    private final WebView web;
    private boolean ready, shown;
    private double lat = Double.NaN, lng, heading = -1;

    @SuppressLint("SetJavaScriptEnabled")
    MiniMap(Activity act, WebView web, String style) {
        this.act = act;
        this.web = web;
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setGeolocationEnabled(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setSupportMultipleWindows(false);
        s.setDomStorageEnabled(false);
        if (android.os.Build.VERSION.SDK_INT >= 26) s.setSafeBrowsingEnabled(true);
        web.setBackgroundColor(0x00000000);
        web.setVisibility(View.INVISIBLE);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) { return true; }

            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
                if (!"https".equals(u.getScheme())) return LiveMap.blocked();
                if (LiveMap.ORIGIN_HOST.equals(host)) return LiveMap.asset(act, u.getPath());
                return LiveMap.isTileHost(host) ? null : LiveMap.blocked();
            }

            @Override public void onPageFinished(WebView v, String url) {
                ready = true;
                PrayerTimes.City c = PrayerTimes.city(PrayerActivity.cityKey(act));
                if (Double.isNaN(lat)) v.evaluateJavascript(String.format(Locale.US, "map.setView([%.5f,%.5f],14)", c.lat, c.lng), null);
                else push();
            }

            @Override public void onReceivedSslError(WebView v, android.webkit.SslErrorHandler h, android.net.http.SslError e) {
                h.cancel();
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onReceivedTitle(WebView v, String title) {
                if (title != null && title.startsWith("tiles:ok") && !shown) {
                    shown = true;
                    web.setAlpha(0f);
                    web.setVisibility(View.VISIBLE);
                    web.animate().alpha(1f).setDuration(500).start();
                }
            }
        });
        web.loadUrl(LiveMap.PAGE + "?mini=1&style=" + style);
    }

    void setPos(double lat, double lng, double heading) {
        this.lat = lat; this.lng = lng; this.heading = heading;
        if (ready) push();
    }

    private void push() {
        web.evaluateJavascript(String.format(Locale.US, "setPos(%.6f,%.6f,%.1f)", lat, lng, heading), null);
    }

    void resume() { web.onResume(); }

    void pause() { web.onPause(); }

    void destroy() { web.destroy(); }
}
