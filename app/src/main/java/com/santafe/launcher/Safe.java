package com.santafe.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

/** فحوص أمان صغيرة قبل فتح أي تطبيق آخر */
final class Safe {

    private Safe() { }

    /**
     * هل هذا المكوّن شاشة بداية حقيقية (لها أيقونة ومسموح فتحها من الخارج) لتطبيق مثبت الآن؟
     * يمنع فتح شاشة داخلية لتطبيق ما، أو قيمة محفوظة قديمة لتطبيق حُذف أو استُبدل.
     */
    static boolean isLaunchable(Context c, ComponentName cn) {
        if (cn == null || cn.getPackageName().equals(c.getPackageName())) return false;
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(cn.getPackageName());
        for (ResolveInfo r : c.getPackageManager().queryIntentActivities(i, 0)) {
            if (r.activityInfo != null && r.activityInfo.exported && cn.getClassName().equals(r.activityInfo.name)) return true;
        }
        return false;
    }

    /** هل ثُبّت التطبيق من متجر قوقل؟ */
    static boolean fromPlayStore(Context c, String pkg) {
        try {
            PackageManager pm = c.getPackageManager();
            String installer = android.os.Build.VERSION.SDK_INT >= 30
                    ? pm.getInstallSourceInfo(pkg).getInstallingPackageName()
                    : pm.getInstallerPackageName(pkg);
            return "com.android.vending".equals(installer);
        } catch (Exception e) {
            return false;
        }
    }
}
