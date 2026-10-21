package com.santafe.launcher;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.widget.Toast;

/**
 * حجب الإعلانات والمحتوى غير اللائق لكل التطبيقات عبر «DNS خاص» (أندرويد ٩ فما فوق).
 * أندرويد لا يسمح لتطبيق عادي بتغيير هذا الإعداد بنفسه، فننسخ العنوان ونفتح إعدادات الشبكة.
 */
public final class AdBlock {

    static final String HOST = "family.adguard-dns.com";

    private AdBlock() { }

    public static boolean isOn(Context c) {
        // الطريقة الرسمية: اسم خادم DNS الخاص للشبكة الحالية
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            try {
                android.net.ConnectivityManager cm =
                        (android.net.ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
                android.net.Network net = cm == null ? null : cm.getActiveNetwork();
                android.net.LinkProperties lp = net == null ? null : cm.getLinkProperties(net);
                String server = lp == null ? null : lp.getPrivateDnsServerName();
                if (server != null) return server.contains("adguard");
            } catch (Exception ignored) { }
        }
        // احتياط: بدون إنترنت لا توجد شبكة نشطة، فنقرأ الإعداد نفسه حيث يسمح النظام
        try {
            String mode = Settings.Global.getString(c.getContentResolver(), "private_dns_mode");
            String host = Settings.Global.getString(c.getContentResolver(), "private_dns_specifier");
            return "hostname".equals(mode) && host != null && host.contains("adguard");
        } catch (Exception e) {
            return false;
        }
    }

    public static void setup(Activity a) {
        if (isOn(a)) {
            Toast.makeText(a, R.string.adblock_on, Toast.LENGTH_SHORT).show();
            return;
        }
        if (android.os.Build.VERSION.SDK_INT < 28) {
            Toast.makeText(a, R.string.adblock_old_android, Toast.LENGTH_LONG).show();
            return;
        }
        ClipboardManager cb = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cb != null) cb.setPrimaryClip(ClipData.newPlainText("dns", HOST));
        Toast.makeText(a, R.string.adblock_steps, Toast.LENGTH_LONG).show();
        if (!start(a, new Intent(Settings.ACTION_WIRELESS_SETTINGS))) start(a, new Intent(Settings.ACTION_SETTINGS));
    }

    private static boolean start(Activity a, Intent i) {
        try {
            a.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
