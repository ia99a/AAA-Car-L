package com.santafe.launcher;

import java.util.Locale;

/**
 * حماية الشبكة للمتصفح المدمج، بلا أي اعتماد على أندرويد حتى تُختبر وحدها:
 * صفحات الإنترنت لا تستطيع الوصول إلى أجهزة الشبكة المحلية (الشاشة نفسها، الراوتر، جوال متصل بنقطة الاتصال).
 */
public final class NetGuard {
    private NetGuard() { }

    /** هل العنوان محلي أو خاص (localhost، 10.x، 192.168.x، 172.16-31.x، 169.254.x، 127.x، ‎::1، fc00::/7، fe80::/10)؟ */
    public static boolean isPrivateHost(String hostRaw) {
        if (hostRaw == null || hostRaw.isEmpty()) return true;
        String h = hostRaw.toLowerCase(Locale.ROOT);
        if (h.startsWith("[") && h.endsWith("]")) h = h.substring(1, h.length() - 1);
        if (h.endsWith(".")) h = h.substring(0, h.length() - 1);
        if (h.equals("localhost") || h.endsWith(".localhost") || h.endsWith(".local") || h.endsWith(".internal")
                || h.endsWith(".lan") || h.endsWith(".home.arpa")) return true;
        if (h.contains(":")) { // IPv6
            return h.equals("::1") || h.equals("::") || h.startsWith("fc") || h.startsWith("fd")
                    || h.startsWith("fe8") || h.startsWith("fe9") || h.startsWith("fea") || h.startsWith("feb")
                    || h.startsWith("::ffff:"); // عنوان IPv4 داخل IPv6: نرفضه احتياطًا
        }
        String[] p = h.split("\\.");
        if (p.length != 4) return false;
        int[] o = new int[4];
        for (int i = 0; i < 4; i++) {
            if (!p[i].matches("\\d{1,3}")) return false; // اسم نطاق عادي
            o[i] = Integer.parseInt(p[i]);
            if (o[i] > 255) return false;
        }
        return o[0] == 10 || o[0] == 127 || o[0] == 0
                || (o[0] == 192 && o[1] == 168)
                || (o[0] == 172 && o[1] >= 16 && o[1] <= 31)
                || (o[0] == 169 && o[1] == 254)
                || (o[0] == 100 && o[1] >= 64 && o[1] <= 127); // شبكات مشغّلي الاتصالات الداخلية
    }
}
