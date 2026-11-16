package com.santafe.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;

/**
 * التحديث من الإعدادات: يقرأ رقم آخر إصدار من مستودع المشروع على GitHub، وإن كان أحدث
 * ينزّله ويتحقق منه ثلاث مرات قبل التثبيت:
 *  ١) بصمة SHA-256 للملف تطابق المنشورة مع الإصدار
 *  ٢) اسم الحزمة نفسه ورقم الإصدار المعلن
 *  ٣) موقّع بنفس مفتاح المشغّل المثبت (وأندرويد نفسه يرفض أي تحديث بمفتاح آخر)
 * لا يتصل إلا بـ GitHub، ولا يعمل إلا حين تضغط «التحقق من التحديث». لا تحديث في الخلفية.
 */
final class Updater {
    private Updater() { }

    private static final String BASE = "https://raw.githubusercontent.com/ia99a/AAA-Car-L/main/release/";
    private static final String VERSION_URL = BASE + "version.json";
    private static final String APK_URL = BASE + "AAA-Car-L.apk";
    private static final long MAX_APK = 60L * 1024 * 1024;
    static final String ACTION_RESULT = "com.santafe.launcher.UPDATE_RESULT";

    /** المضيفات المسموحة فقط (GitHub يحوّل الملفات الكبيرة لمضيف تخزينه) */
    private static boolean allowedHost(URL u) {
        String h = u.getHost().toLowerCase(Locale.ROOT);
        return "https".equals(u.getProtocol()) && (h.equals("raw.githubusercontent.com") || h.equals("github.com")
                || h.equals("objects.githubusercontent.com") || h.equals("media.githubusercontent.com")
                || h.endsWith(".githubusercontent.com"));
    }

    private static final Handler ui = new Handler(Looper.getMainLooper());
    private static volatile boolean busy;

    static long installedCode(Context c) {
        try {
            PackageInfo p = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? p.getLongVersionCode() : p.versionCode;
        } catch (Exception e) { return 0; }
    }

    static String installedName(Context c) {
        try { return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName; }
        catch (Exception e) { return "?"; }
    }

    /** زر «التحقق من التحديث» */
    static void check(Activity a) {
        if (busy) return;
        busy = true;
        Toast.makeText(a, R.string.upd_checking, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                JSONObject v = new JSONObject(new String(fetch(VERSION_URL, 64 * 1024), "UTF-8"));
                long code = v.getLong("versionCode");
                String name = v.getString("versionName");
                String sha = v.getString("sha256").toLowerCase(Locale.ROOT);
                ui.post(() -> {
                    busy = false;
                    if (a.isFinishing()) return;
                    if (code <= installedCode(a)) {
                        Toast.makeText(a, a.getString(R.string.upd_latest, installedName(a)), Toast.LENGTH_LONG).show();
                        return;
                    }
                    new AlertDialog.Builder(a)
                            .setTitle(a.getString(R.string.upd_available, name))
                            .setMessage(a.getString(R.string.upd_ask, installedName(a), name))
                            .setPositiveButton(R.string.upd_install, (d, w) -> download(a, code, sha))
                            .setNegativeButton(R.string.upd_later, null)
                            .show();
                });
            } catch (Exception e) {
                ui.post(() -> { busy = false; Toast.makeText(a, R.string.upd_no_net, Toast.LENGTH_LONG).show(); });
            }
        }, "update-check").start();
    }

    private static void download(Activity a, long code, String sha) {
        // أندرويد ٨+: يلزم السماح للمشغّل بتثبيت التطبيقات مرة واحدة
        if (Build.VERSION.SDK_INT >= 26 && !a.getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(a, R.string.upd_allow_install, Toast.LENGTH_LONG).show();
            try {
                a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.getPackageName())));
            } catch (Exception ignored) { }
            return;
        }
        if (busy) return;
        busy = true;
        Toast.makeText(a, R.string.upd_downloading, Toast.LENGTH_LONG).show();
        Context app = a.getApplicationContext();
        new Thread(() -> {
            File f = new File(app.getCacheDir(), "update.apk");
            try {
                String got = downloadTo(APK_URL, f);
                if (!got.equals(sha)) throw new SecurityException("sha");
                verifyApk(app, f, code);
                install(app, f);
            } catch (SecurityException e) {
                f.delete();
                ui.post(() -> Toast.makeText(app, R.string.upd_bad_file, Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                f.delete();
                ui.post(() -> Toast.makeText(app, R.string.upd_no_net, Toast.LENGTH_LONG).show());
            } finally {
                busy = false;
            }
        }, "update-download").start();
    }

    /** نفس الحزمة، نفس رقم الإصدار المعلن، ونفس مفتاح التوقيع */
    @SuppressWarnings("deprecation")
    private static void verifyApk(Context c, File f, long code) {
        PackageManager pm = c.getPackageManager();
        int flag = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo apk = pm.getPackageArchiveInfo(f.getPath(), flag);
        if (apk == null || !c.getPackageName().equals(apk.packageName)) throw new SecurityException("pkg");
        long apkCode = Build.VERSION.SDK_INT >= 28 ? apk.getLongVersionCode() : apk.versionCode;
        if (apkCode != code) throw new SecurityException("code");
        try {
            PackageInfo mine = pm.getPackageInfo(c.getPackageName(), flag);
            Signature[] a, b;
            if (Build.VERSION.SDK_INT >= 28) {
                if (apk.signingInfo == null || mine.signingInfo == null) throw new SecurityException("sig");
                a = apk.signingInfo.getApkContentsSigners();
                b = mine.signingInfo.getApkContentsSigners();
            } else {
                a = apk.signatures;
                b = mine.signatures;
            }
            if (a == null || b == null || !Arrays.equals(a, b)) throw new SecurityException("sig");
        } catch (PackageManager.NameNotFoundException e) {
            throw new SecurityException("self");
        }
    }

    private static void install(Context c, File f) throws Exception {
        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams p = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        p.setAppPackageName(c.getPackageName());
        p.setSize(f.length());
        int id = pi.createSession(p);
        try (PackageInstaller.Session s = pi.openSession(id);
             InputStream in = new FileInputStream(f);
             OutputStream out = s.openWrite("update.apk", 0, f.length())) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            s.fsync(out);
            out.close();
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            Intent i = new Intent(ACTION_RESULT).setPackage(c.getPackageName());
            PendingIntent pend = PendingIntent.getBroadcast(c, id, i, flags);
            s.commit(pend.getIntentSender());
        }
    }

    // ───────── الشبكة ─────────

    private static HttpURLConnection open(String url) throws Exception {
        URL u = new URL(url);
        for (int hop = 0; hop < 5; hop++) {
            if (!allowedHost(u)) throw new SecurityException("host");
            HttpURLConnection c = (HttpURLConnection) u.openConnection();
            c.setInstanceFollowRedirects(false); // نتحقق من كل تحويل بأنفسنا
            c.setConnectTimeout(10_000);
            c.setReadTimeout(20_000);
            c.setRequestProperty("Cache-Control", "no-cache");
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null) throw new Exception("redirect");
                u = new URL(u, loc);
                continue;
            }
            if (code != 200) { c.disconnect(); throw new Exception("http " + code); }
            return c;
        }
        throw new Exception("redirects");
    }

    private static byte[] fetch(String url, int max) throws Exception {
        HttpURLConnection c = open(url);
        try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > max) throw new SecurityException("size");
            }
            return out.toByteArray();
        } finally { c.disconnect(); }
    }

    /** ينزّل الملف ويرجع بصمته SHA-256 */
    private static String downloadTo(String url, File f) throws Exception {
        HttpURLConnection c = open(url);
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        long total = 0;
        try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(f)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_APK) throw new SecurityException("size");
                md.update(buf, 0, n);
                out.write(buf, 0, n);
            }
        } finally { c.disconnect(); }
        StringBuilder s = new StringBuilder();
        for (byte b : md.digest()) s.append(String.format(Locale.ROOT, "%02x", b));
        return s.toString();
    }

    /** نتيجة التثبيت من أندرويد: يطلب تأكيدك، أو يخبرك بالنجاح أو السبب */
    public static final class Result extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent i) {
            int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try { c.startActivity(confirm); } catch (Exception ignored) { }
                }
            } else if (st != PackageInstaller.STATUS_SUCCESS) {
                Toast.makeText(c, Lang.wrap(c).getString(R.string.upd_failed), Toast.LENGTH_LONG).show();
            }
            new File(c.getCacheDir(), "update.apk").delete();
        }
    }
}
