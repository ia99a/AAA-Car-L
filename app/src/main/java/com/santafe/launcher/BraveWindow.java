package com.santafe.launcher;

import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.net.Uri;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.widget.Toast;

/**
 * يفتح تطبيق Brave الحقيقي (بحماية الإعلانات الخاصة به) في نافذة صغيرة يسار الشاشة، بجانب شريط السائق.
 * أندرويد يعرض التطبيق في نافذة عائمة فقط إن كانت «النوافذ العائمة» مفعّلة في الشاشة؛
 * وإلا يفتحه بجانب التطبيق الحالي (تقسيم الشاشة) إن أمكن، وإلا بملء الشاشة.
 */
final class BraveWindow {
    private BraveWindow() { }

    static final String PKG = "com.brave.browser";

    static String installed(Context c) {
        PackageManager pm = c.getPackageManager();
        for (String p : new String[]{PKG, PKG + "_beta", PKG + "_nightly"})
            if (pm.getLaunchIntentForPackage(p) != null) return p;
        return null;
    }

    /** هل الشاشة تدعم النوافذ العائمة (من خيارات المطوّر أو من الشركة المصنعة)؟ */
    static boolean freeformSupported(Context c) {
        PackageManager pm = c.getPackageManager();
        if (pm.hasSystemFeature(PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT)) return true;
        try {
            return Settings.Global.getInt(c.getContentResolver(), "enable_freeform_support", 0) != 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** مكان النافذة: يسار الشاشة بعد شريط السائق، في النصف السفلي (حيث كانت لوحة الفيديو) */
    static Rect bounds(Context c) {
        DisplayMetrics m = c.getResources().getDisplayMetrics();
        float dp = m.density;
        int left = (int) (80 * dp), bottom = m.heightPixels - (int) (12 * dp);
        int w = Math.min((int) (420 * dp), m.widthPixels / 3), h = Math.min((int) (300 * dp), (int) (m.heightPixels * 0.62f));
        return new Rect(left, bottom - h, left + w, bottom);
    }

    static void open(Context c) {
        String pkg = installed(c);
        if (pkg == null) {
            Toast.makeText(c, R.string.opening_store, Toast.LENGTH_SHORT).show();
            if (!tryStart(c, new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + PKG)), null))
                tryStart(c, new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=" + PKG)), null);
            return;
        }
        Intent i = c.getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) return;
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT);
        ActivityOptions o = ActivityOptions.makeBasic();
        o.setLaunchBounds(bounds(c)); // تُحترم فقط حين تكون النوافذ العائمة مفعّلة
        if (!tryStart(c, i, o)) tryStart(c, c.getPackageManager().getLaunchIntentForPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), null);
    }

    private static boolean tryStart(Context c, Intent i, ActivityOptions o) {
        try {
            if (!(c instanceof android.app.Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i, o == null ? null : o.toBundle());
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
