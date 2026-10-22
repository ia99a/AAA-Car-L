package com.santafe.launcher;

import android.content.Context;

/** إعدادات الشاشة الرئيسية: الخلفية، وما يظهر فيها من عناصر (كلها قابلة للإخفاء). */
final class Home {
    private Home() { }

    static final String CLOCK = "clock", SPEED = "speed", VIDEO = "video", MAPS = "maps", HUDHUD = "hudhud", DOCK = "dock";
    static final String[] ITEMS = {CLOCK, SPEED, VIDEO, MAPS, HUDHUD, DOCK};
    static final int[] NAMES = {R.string.el_clock, R.string.el_speed, R.string.el_video,
            R.string.maps, R.string.hudhud, R.string.el_dock};

    /** هل العنصر ظاهر؟ كلها ظاهرة افتراضيًا. البار السفلي يحفظ بمفتاحه القديم */
    static boolean shown(Context c, String item) {
        return Lang.prefs(c).getBoolean(DOCK.equals(item) ? "show_dock" : "show_" + item, true);
    }

    static void setShown(Context c, String item, boolean on) {
        Lang.prefs(c).edit().putBoolean(DOCK.equals(item) ? "show_dock" : "show_" + item, on).apply();
    }

    /** الخلفية: الخريطة الحية (الافتراضي) أو النقش */
    static boolean mapBackground(Context c) {
        return !"pattern".equals(Lang.prefs(c).getString("bg_mode", "map"));
    }

    static void setMapBackground(Context c, boolean map) {
        Lang.prefs(c).edit().putString("bg_mode", map ? "map" : "pattern").apply();
    }

    /** ما يتطلب إعادة بناء الرئيسية إن تغيّر: نوع الخلفية، ووجود لوحة الفيديو */
    static String structure(Context c) {
        return mapBackground(c) + "/" + shown(c, VIDEO);
    }
}
