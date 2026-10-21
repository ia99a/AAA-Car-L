package com.santafe.launcher;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

/**
 * أدوات شريط السائق: القائمة الكاملة، وما اختاره المستخدم منها (محفوظ بالترتيب).
 * الإعدادات ليست أداة هنا: لها زر ثابت أسفل الشريط حتى لا تختفي أبدًا. والحجب صار داخل الإعدادات.
 */
public final class Tools {

    public static final String LOCATION = "location", PRAYER = "prayer", APPS = "apps", BRAVE = "brave";

    public static final String[] ALL = {LOCATION, PRAYER, APPS, BRAVE};
    static final String DEFAULT = "location,prayer,apps";
    public static final int MAX = ALL.length;

    private Tools() { }

    /** الأدوات المختارة؛ الأدوات المحذوفة من الإصدارات السابقة (اللغة، الموسيقى، الإعدادات، الحجب) تُتجاهل */
    public static List<String> enabled(Context c) {
        String v = Lang.prefs(c).getString("tools", DEFAULT);
        List<String> out = new ArrayList<>();
        for (String k : v.split(",")) if (!k.isEmpty() && isKnown(k) && !out.contains(k)) out.add(k);
        if (out.isEmpty()) for (String k : DEFAULT.split(",")) out.add(k);
        return out;
    }

    public static void save(Context c, List<String> keys) {
        Lang.prefs(c).edit().putString("tools", android.text.TextUtils.join(",", keys)).apply();
    }

    static boolean isKnown(String k) {
        for (String a : ALL) if (a.equals(k)) return true;
        return false;
    }

    public static int icon(String k) {
        switch (k) {
            case LOCATION: return R.drawable.ic_location;
            case PRAYER: return R.drawable.ic_prayer;
            case APPS: return R.drawable.ic_grid;
            default: return R.drawable.ic_pip;
        }
    }

    public static int label(String k) {
        switch (k) {
            case LOCATION: return R.string.tool_location;
            case PRAYER: return R.string.tool_prayer;
            case APPS: return R.string.tool_apps;
            default: return R.string.tool_brave;
        }
    }

    public static int desc(String k) {
        switch (k) {
            case LOCATION: return R.string.tool_location_desc;
            case PRAYER: return R.string.tool_prayer_desc;
            case APPS: return R.string.tool_apps_desc;
            default: return R.string.tool_brave_desc;
        }
    }
}
