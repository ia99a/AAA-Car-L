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
 * لوحة الفيديو المثبتة يسار الشاشة الرئيسية: متصفح صغير مدمج لا يختفي ولا يطفو،
 * يتكبّر لملء الشاشة ويرجع لمكانه، ويحجب نطاقات الإعلانات المعروفة.
 * أثناء القيادة تُغطّى الصورة (الصوت يستمر) حفاظًا على انتباه السائق.
 */
final class VideoPanel {

    static final String HOME = "https://m.youtube.com/";

    /** نطاقات إعلانات وتتبّع شائعة تُحجب داخل اللوحة */
    private static final String[] AD_HOSTS = {
            "doubleclick.net", "googlesyndication.com", "googleadservices.com", "google-analytics.com",
            "googletagmanager.com", "googletagservices.com", "adservice.google.com", "imasdk.googleapis.com",
            "ads.youtube.com", "adnxs.com", "amazon-adsystem.com", "criteo.com", "criteo.net", "taboola.com",
            "outbrain.com", "scorecardresearch.com", "moatads.com", "adsrvr.org", "pubmatic.com", "rubiconproject.com",
            "openx.net", "smartadserver.com", "yieldmo.com", "adcolony.com", "unityads.unity3d.com", "applovin.com"
    };
    /** مسارات إعلانات يوتيوب نفسه (تُحجب جزئيًا؛ بعض إعلانات يوتيوب تأتي من نفس خوادم الفيديو) */
    private static final String[] AD_PATHS = {"/pagead/", "/api/stats/ads", "/ptracking", "/get_midroll_", "ad_break"};

    private final Activity act;
    private final WebView web;
    private final ViewGroup dockSlot;      // مكان اللوحة الصغيرة
    private final FrameLayout fullHost;    // طبقة ملء الشاشة
    private final View driveCover;         // غطاء أثناء القيادة
    private final TextView toggleLabel;
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
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setGeolocationEnabled(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setSupportMultipleWindows(false);  // لا نوافذ منبثقة
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setSavePassword(false);
        s.setSaveFormData(false);
        if (android.os.Build.VERSION.SDK_INT >= 26) s.setSafeBrowsingEnabled(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false);
        web.setBackgroundColor(0xFF000000);

        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                String scheme = r.getUrl().getScheme();
                // https فقط داخل اللوحة؛ روابط التطبيقات (intent:، market: ...) تُتجاهل
                return !"https".equalsIgnoreCase(scheme);
            }

            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                return isAd(r.getUrl()) ? LiveMap.blocked() : null;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            // ملء الشاشة من زر يوتيوب نفسه
            @Override public void onShowCustomView(View view, CustomViewCallback cb) {
                if (moving) { cb.onCustomViewHidden(); return; }
                customView = view; customCb = cb;
                fullHost.addView(view, 0, new FrameLayout.LayoutParams(-1, -1));
                fullHost.setVisibility(View.VISIBLE);
            }

            @Override public void onHideCustomView() { hideCustom(); }
        });

        String saved = Lang.prefs(act).getString("video_url", HOME);
        web.loadUrl(saved != null && saved.startsWith("https://") ? saved : HOME);
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
    }

    void pause() {
        String u = web.getUrl();
        if (u != null && u.startsWith("https://")) Lang.prefs(act).edit().putString("video_url", u).apply();
    }

    void destroy() { web.destroy(); }
}
