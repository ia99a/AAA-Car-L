package com.santafe.launcher;

import android.content.Context;

/** إعدادات الشاشة الرئيسية: الخلفية، وما يظهر فيها من عناصر (كلها قابلة للإخفاء). */
final class Home {
    private Home() { }

    static final String CLOCK = "clock", SPEED = "speed", VIDEO = "video", MAPS = "maps", HUDHUD = "hudhud", DOCK = "dock", IQAMA = "iqama";
    /** عناصر تُظهر وتُخفى بزر واحد (خرائط قوقل وهدهد لهما سطران مستقلان بأربعة أوضاع) */
    static final String[] ITEMS = {CLOCK, SPEED, VIDEO, DOCK, IQAMA};
    static final int[] NAMES = {R.string.el_clock, R.string.el_speed, R.string.el_video, R.string.el_dock, R.string.el_iqama};

    /** طريقة عرض خرائط قوقل وهدهد: بطاقة، أيقونة فقط، خريطة حية مصغّرة، أو مخفية */
    static final String CARD = "card", ICON = "icon", LIVE = "live", HIDDEN = "hidden";
    static final String[] NAV_MODES = {CARD, ICON, LIVE, HIDDEN};
    static final int[] NAV_MODE_NAMES = {R.string.nav_card, R.string.nav_icon, R.string.nav_live, R.string.nav_hidden};

    static String navMode(Context c, String app) {
        String m = Lang.prefs(c).getString("nav_mode_" + app, null);
        if (m == null) return Lang.prefs(c).getBoolean("show_" + app, true) ? CARD : HIDDEN; // من الإصدار ٢.٠
        for (String k : NAV_MODES) if (k.equals(m)) return k;
        return CARD;
    }

    static void setNavMode(Context c, String app, String mode) {
        Lang.prefs(c).edit().putString("nav_mode_" + app, mode).apply();
    }

    /** هل العنصر ظاهر؟ كلها ظاهرة افتراضيًا. البار السفلي يحفظ بمفتاحه القديم */
    static boolean shown(Context c, String item) {
        if (MAPS.equals(item) || HUDHUD.equals(item)) return !HIDDEN.equals(navMode(c, item));
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

    /** ما يتطلب إعادة بناء الرئيسية إن تغيّر: نوع الخلفية، ولوحة الفيديو، والخرائط المصغّرة */
    static String structure(Context c) {
        return mapBackground(c) + "/" + shown(c, VIDEO) + "/" + LIVE.equals(navMode(c, MAPS)) + "/" + LIVE.equals(navMode(c, HUDHUD));
    }
}
