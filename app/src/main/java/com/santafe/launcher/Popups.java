package com.santafe.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.provider.Settings;

/** إعدادات النوافذ العائمة الجديدة: كبسولة الصوت أعلى الشاشة، وكبسولة المكالمات أسفلها. */
final class Popups {
    private Popups() { }

    /** كبسولة الصوت (مفعّلة افتراضيًا، وتعمل متى مُنح إذن الظهور فوق التطبيقات) */
    static boolean volume(Context c) { return Lang.prefs(c).getBoolean("popup_volume", true); }

    static void setVolume(Context c, boolean on) { Lang.prefs(c).edit().putBoolean("popup_volume", on).apply(); }

    /** كبسولة المكالمات (تحتاج أيضًا إذن الوصول للإشعارات لتعرف بالمكالمة) */
    static boolean call(Context c) { return Lang.prefs(c).getBoolean("popup_call", true); }

    static void setCall(Context c, boolean on) { Lang.prefs(c).edit().putBoolean("popup_call", on).apply(); }

    /** هل منح المستخدم المشغّل إذن قراءة الإشعارات (لمعرفة المكالمات فقط) */
    static boolean notificationAccess(Context c) {
        String list = Settings.Secure.getString(c.getContentResolver(), "enabled_notification_listeners");
        if (list == null) return false;
        ComponentName me = new ComponentName(c, CallWatcher.class);
        for (String s : list.split(":")) if (me.equals(ComponentName.unflattenFromString(s))) return true;
        return false;
    }
}
