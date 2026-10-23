package com.santafe.launcher;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.Locale;

/**
 * لوحة المتصفح المثبتة يسار الشاشة الرئيسية: متصفح صغير مدمج بطابع Brave (البحث عبر Brave Search
 * وحجب الإعلانات والمتتبعات)، لا يختفي ولا يطفو، يتكبّر لملء الشاشة ويرجع لمكانه.
 * أندرويد لا يسمح بعرض تطبيق Brave نفسه داخل المشغّل، فزر Brave يفتح الصفحة الحالية فيه.
 * أثناء القيادة تُغطّى الصورة (الصوت يستمر) ويُقفل شريط العنوان، حفاظًا على انتباه السائق.
 */
final class VideoPanel {

    /** صفحة البداية: بحث Brave واختصارات، من ملفات التطبيق نفسه */
    static final String HOME = "https://" + LiveMap.ORIGIN_HOST + "/browser/start.html";
    static final String SEARCH = "https://search.brave.com/search?q=";

    /** نطاقات إعلانات وتتبّع شائعة تُحجب داخل اللوحة */
    private static final String[] AD_HOSTS = {
            "doubleclick.net", "googlesyndication.com", "googleadservices.com", "google-analytics.com",
            "googletagmanager.com", "googletagservices.com", "adservice.google.com", "imasdk.googleapis.com",
            "ads.youtube.com", "adnxs.com", "amazon-adsystem.com", "criteo.com", "criteo.net", "taboola.com",
            "outbrain.com", "scorecardresearch.com", "moatads.com", "adsrvr.org", "pubmatic.com", "rubiconproject.com",
            "openx.net", "smartadserver.com", "yieldmo.com", "adcolony.com", "unityads.unity3d.com", "applovin.com",
            // متتبعات ونوافذ منبثقة شائعة (على نهج حماية Brave)
            "adform.net", "bidswitch.net", "casalemedia.com", "3lift.com", "sharethrough.com", "teads.tv", "mgid.com",
            "propellerads.com", "popads.net", "popcash.net", "exoclick.com", "adsterra.com", "hotjar.com", "clarity.ms",
            "quantserve.com", "mc.yandex.ru", "an.yandex.ru", "connect.facebook.net", "ads-twitter.com",
            "analytics.tiktok.com", "bat.bing.com", "snap.licdn.com", "app-measurement.com"
    };
    /** مسارات إعلانات يوتيوب نفسه (تُحجب جزئيًا؛ بعض إعلانات يوتيوب تأتي من نفس خوادم الفيديو) */
    private static final String[] AD_PATHS = {"/pagead/", "/api/stats/ads", "/ptracking", "/get_midroll_", "ad_break"};

    private final Activity act;
    private final WebView web;
    private final ViewGroup dockSlot;      // مكان اللوحة الصغيرة
    private final FrameLayout fullHost;    // طبقة ملء الشاشة
    private final View driveCover;         // غطاء أثناء القيادة
    private final TextView toggleLabel;
    private TextView status;
    private android.widget.EditText address;
    private boolean wipeHistory;

    /** بعد مسح البيانات من الإعدادات: نرجع لصفحة البداية بلا سجل */
    void reset() {
        wipeHistory = true;
        web.loadUrl(HOME);
    }
    private boolean full, moving;
    private View customView;
    private WebChromeClient.CustomViewCallback customCb;

    @SuppressLint("SetJavaScriptEnabled")
    VideoPanel(Activity act, WebView web, ViewGroup dockSlot, FrameLayout fullHost, View driveCover, TextView toggleLabel) {
        this.act = act; this.web = web; this.dockSlot = dockSlot; this.fullHost = fullHost;
        this.driveCover = driveCover; this.toggleLabel = toggleLabel;

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);        // يوتيوب لا يعمل بدونه
        s.setDomStorageEnabled(true);
        harden(s);                           // بلا ملفات، بلا نوافذ منبثقة، https فقط، تصفح آمن
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setSavePassword(false);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false);
        // يوتيوب يقدّم نسخة محدودة (أو لا يعمل) حين يعرف أنه داخل WebView؛ نعرّف اللوحة كمتصفح Chrome للجوال
        String ua = s.getUserAgentString();
        if (ua != null) s.setUserAgentString(ua.replace("; wv)", ")").replaceAll("Version/\\d+(\\.\\d+)* ", ""));
        web.setBackgroundColor(0xFF000000);

        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                String scheme = r.getUrl().getScheme();
                // https فقط داخل اللوحة؛ روابط التطبيقات (intent:، market: ...) تُتجاهل
                if (!"https".equalsIgnoreCase(scheme)) return true;
                if (NetGuard.isPrivateHost(r.getUrl().getHost()) && !LiveMap.ORIGIN_HOST.equals(r.getUrl().getHost())) {
                    if (r.isForMainFrame()) showError(act.getString(R.string.net_blocked));
                    return true; // لا وصول لأجهزة الشبكة المحلية
                }
                return false;
            }

            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                String host = u.getHost();
                if (LiveMap.ORIGIN_HOST.equals(host)) { // صفحة البداية فقط، لا ملفات الخريطة
                    String path = u.getPath();
                    return path != null && path.startsWith("/browser/") ? LiveMap.asset(act, path) : LiveMap.blocked();
                }
                if (!"https".equalsIgnoreCase(u.getScheme()) && !"data".equalsIgnoreCase(u.getScheme())
                        && !"blob".equalsIgnoreCase(u.getScheme())) return LiveMap.blocked();
                if (NetGuard.isPrivateHost(host) && !"data".equalsIgnoreCase(u.getScheme()) && !"blob".equalsIgnoreCase(u.getScheme()))
                    return LiveMap.blocked();
                return isAd(u) ? LiveMap.blocked() : null;
            }

            @Override public void onPageStarted(WebView v, String url, android.graphics.Bitmap icon) {
                if (status != null) status.setVisibility(View.GONE);
                showAddress(url);
            }

            @Override public void onPageFinished(WebView v, String url) {
                showAddress(url);
                if (wipeHistory) { wipeHistory = false; v.clearHistory(); } // بعد «مسح بيانات المتصفح»
            }

            @Override public void onReceivedError(WebView v, WebResourceRequest r, android.webkit.WebResourceError e) {
                if (android.os.Build.VERSION.SDK_INT >= 23 && r.isForMainFrame())
                    showError(LiveMap.reasonFor(act, e.getErrorCode(), 0, String.valueOf(e.getDescription())));
            }

            @Override public void onReceivedHttpError(WebView v, WebResourceRequest r, WebResourceResponse resp) {
                if (r.isForMainFrame() && resp.getStatusCode() >= 400)
                    showError(LiveMap.reasonFor(act, 0, resp.getStatusCode(), null));
            }

            @Override public void onReceivedSslError(WebView v, android.webkit.SslErrorHandler h, android.net.http.SslError e) {
                h.cancel();
                showError(act.getString(R.string.map_err_ssl));
            }
        });
        web.setWebChromeClient(new DenyChrome() {
            // ملء الشاشة من زر يوتيوب نفسه
            @Override public void onShowCustomView(View view, CustomViewCallback cb) {
                if (moving) { cb.onCustomViewHidden(); return; }
                customView = view; customCb = cb;
                fullHost.addView(view, 0, new FrameLayout.LayoutParams(-1, -1));
                fullHost.setVisibility(View.VISIBLE);
            }

            @Override public void onHideCustomView() { hideCustom(); }
        });

        String saved = Lang.prefs(act).getString("browser_url", HOME);
        boolean ok = saved != null && saved.startsWith("https://") && !NetGuard.isPrivateHost(Uri.parse(saved).getHost());
        web.loadUrl(ok || HOME.equals(saved) ? saved : HOME);
    }


    /** إعدادات أمان مشتركة لكل WebView في التطبيق */
    static void harden(WebSettings s) {
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        s.setGeolocationEnabled(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setSupportMultipleWindows(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setSaveFormData(false);
        if (android.os.Build.VERSION.SDK_INT >= 26) s.setSafeBrowsingEnabled(true);
    }

    /** يرفض كل طلبات الصلاحيات من الصفحات: كاميرا، ميكروفون، موقع، ملفات */
    static class DenyChrome extends WebChromeClient {
        @Override public void onPermissionRequest(android.webkit.PermissionRequest r) { r.deny(); }
        @Override public void onGeolocationPermissionsShowPrompt(String origin, android.webkit.GeolocationPermissions.Callback cb) {
            cb.invoke(origin, false, false);
        }
        @Override public boolean onShowFileChooser(WebView v, android.webkit.ValueCallback<Uri[]> cb, FileChooserParams p) {
            return false;
        }
    }

    /** شريط العنوان: كتابة عنوان أو بحث، و«انتقال» من لوحة المفاتيح يفتحه */
    void setAddressBar(android.widget.EditText e) {
        address = e;
        e.setOnEditorActionListener((tv, actionId, ev) -> {
            open(tv.getText().toString());
            tv.clearFocus();
            android.view.inputmethod.InputMethodManager im =
                    (android.view.inputmethod.InputMethodManager) act.getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
            if (im != null) im.hideSoftInputFromWindow(tv.getWindowToken(), 0);
            return true;
        });
        showAddress(web.getUrl());
    }

    /** يحوّل ما كُتب إلى عنوان: موقع إن كان يشبه عنوانًا، وإلا بحث في Brave Search */
    static String toUrl(String input) {
        String q = input == null ? "" : input.trim();
        if (q.isEmpty()) return HOME;
        if (q.startsWith("https://")) return q;
        if (q.startsWith("http://")) return "https://" + q.substring(7); // لا اتصال غير مشفّر
        boolean looksLikeHost = !q.contains(" ") && q.contains(".") && !q.endsWith(".")
                && q.matches("[\\p{L}\\p{N}.-]+(:\\d+)?(/.*)?");
        if (looksLikeHost) return "https://" + q;
        try {
            return SEARCH + java.net.URLEncoder.encode(q, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return HOME;
        }
    }

    void open(String input) { web.loadUrl(toUrl(input)); }

    /** يعرض اسم الموقع الحالي في شريط العنوان (فارغ في صفحة البداية ليظهر تلميح البحث) */
    private void showAddress(String url) {
        if (address == null || address.hasFocus()) return;
        Uri u = url == null ? null : Uri.parse(url);
        String host = u == null || u.getHost() == null || LiveMap.ORIGIN_HOST.equals(u.getHost()) ? "" : u.getHost();
        if (host.startsWith("www.")) host = host.substring(4);
        if (host.startsWith("m.")) host = host.substring(2);
        address.setText(host);
    }

    void goBack() { if (web.canGoBack()) web.goBack(); else home(); }

    /** رسالة الخطأ فوق اللوحة؛ الضغط عليها يعيد التحميل */
    void setStatusView(TextView t) {
        status = t;
        t.setOnClickListener(v -> { t.setVisibility(View.GONE); web.reload(); });
    }

    private void showError(String reason) {
        if (status == null) return;
        status.setText(act.getString(R.string.video_err, reason));
        status.setVisibility(View.VISIBLE);
    }

    static boolean isAd(Uri u) {
        return isAd(u.getHost(), u.toString());
    }

    /** منفصلة عن أندرويد لتُختبر وحدها */
    static boolean isAd(String hostRaw, String full) {
        String host = hostRaw == null ? "" : hostRaw.toLowerCase(Locale.ROOT);
        for (String h : AD_HOSTS) if (host.equals(h) || host.endsWith("." + h)) return true;
        if (host.equals("youtube.com") || host.endsWith(".youtube.com") || host.endsWith(".googlevideo.com")) {
            for (String p : AD_PATHS) if (full.contains(p)) return true;
        }
        return false;
    }

    boolean isFull() { return full || customView != null; }

    /** تكبير لملء الشاشة أو تصغير للمكان الأصلي، دون إيقاف الفيديو */
    void toggle() {
        if (customView != null) { hideCustom(); return; }
        if (!full && moving) return;
        full = !full;
        ((ViewGroup) web.getParent()).removeView(web);
        if (full) {
            fullHost.addView(web, 0, new FrameLayout.LayoutParams(-1, -1));
            fullHost.setVisibility(View.VISIBLE);
        } else {
            fullHost.setVisibility(View.GONE);
            dockSlot.addView(web, 0, new FrameLayout.LayoutParams(-1, -1));
        }
        toggleLabel.setText(full ? R.string.video_small : R.string.video_large);
    }

    /** زر الرجوع: يصغّر أولًا إن كانت مكبّرة */
    boolean back() {
        if (customView != null) { hideCustom(); return true; }
        if (full) { toggle(); return true; }
        if (web.canGoBack()) { web.goBack(); return true; }
        return false;
    }

    void home() { web.loadUrl(HOME); }

    String url() { return web.getUrl(); }

    private void hideCustom() {
        if (customView == null) return;
        fullHost.removeView(customView);
        customView = null;
        if (!full) fullHost.setVisibility(View.GONE);
        if (customCb != null) customCb.onCustomViewHidden();
        customCb = null;
    }

    /** أثناء القيادة: نغطي الصورة ونصغّرها إن كانت مكبّرة، والصوت يستمر */
    void setMoving(boolean m) {
        if (m == moving) return;
        moving = m;
        if (m && isFull()) { hideCustom(); if (full) toggle(); }
        driveCover.setVisibility(m ? View.VISIBLE : View.GONE);
        if (address != null) { // لا كتابة أثناء القيادة
            if (m) address.clearFocus();
            address.setEnabled(!m);
        }
    }

    void pause() {
        String u = web.getUrl();
        if (u != null && u.startsWith("https://")) Lang.prefs(act).edit().putString("browser_url", u).apply();
    }

    void destroy() { web.destroy(); }
}
