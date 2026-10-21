package com.santafe.launcher;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.net.Uri;

import java.util.Calendar;

/**
 * تنبيهات الصلاة: منبّه واحد للتنبيه القادم فقط، وعند وصوله يظهر إشعار بنغمة هادئة
 * ثم يُجدول التالي. يعمل والشاشة في أي تطبيق آخر (خرائط قوقل، هدهد...).
 * المستقبِل غير مكشوف لتطبيقات أخرى.
 */
public final class PrayerAlerts extends BroadcastReceiver {

    private static final String EXTRA_TYPE = "type", EXTRA_PRAYER = "prayer", EXTRA_AT = "at", EXTRA_ADHAN = "adhan";
    private static final int NOTIF_ID = 77;
    /** معرّفات القنوات (تتغير اللاحقة إن تغيّرت النغمة، لأن أندرويد لا يسمح بتعديل صوت قناة موجودة) */
    private static final String[] CHANNELS = {"prayer_soon_v1", "prayer_adhan_v1", "prayer_iqama_v1"};
    private static final int[] SOUNDS = {R.raw.chime_soon, R.raw.chime_adhan, R.raw.chime_iqama};

    static boolean enabled(Context c) {
        return Lang.prefs(c).getBoolean("prayer_alerts", true);
    }

    static void setEnabled(Context c, boolean on) {
        Lang.prefs(c).edit().putBoolean("prayer_alerts", on).apply();
        if (on) schedule(c); else cancel(c);
    }

    /** يجدول التنبيه القادم (يُستدعى من الشاشة الرئيسية وبعد كل تنبيه وعند تغيير المدينة) */
    static void schedule(Context c) {
        if (!enabled(c)) { cancel(c); return; }
        PrayerTimes.City city = PrayerTimes.city(PrayerActivity.cityKey(c));
        Calendar now = Calendar.getInstance();
        Calendar tm = (Calendar) now.clone();
        tm.add(Calendar.DAY_OF_YEAR, 1);
        AlertPlan.Event e = AlertPlan.next(now.getTimeInMillis(), PrayerTimes.forDay(now, city),
                PrayerTimes.forDay(tm, city), PrayerTimes.IQAMA);
        if (e == null) return;
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = pending(c, new Intent(c, PrayerAlerts.class)
                .putExtra(EXTRA_TYPE, e.type).putExtra(EXTRA_PRAYER, e.prayer)
                .putExtra(EXTRA_AT, e.at).putExtra(EXTRA_ADHAN, e.adhanAt));
        try {
            boolean exact = android.os.Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms();
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, e.at, pi);
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, e.at, pi); // قد يتأخر دقائق قليلة
        } catch (SecurityException ex) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, e.at, pi);
        }
    }

    static void cancel(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(pending(c, new Intent(c, PrayerAlerts.class)));
    }

    private static PendingIntent pending(Context c, Intent i) {
        return PendingIntent.getBroadcast(c, 1, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    @Override
    public void onReceive(Context base, Intent intent) {
        Context c = Lang.wrap(base);
        int type = intent.getIntExtra(EXTRA_TYPE, -1), prayer = intent.getIntExtra(EXTRA_PRAYER, -1);
        long at = intent.getLongExtra(EXTRA_AT, 0), adhan = intent.getLongExtra(EXTRA_ADHAN, 0);
        boolean valid = type >= 0 && type <= 2 && prayer >= 0 && prayer < 6;
        // تنبيه متأخر أكثر من دقيقتين (مثلًا الشاشة كانت مطفأة) لا يُعرض حتى لا يربك
        if (enabled(c) && valid && Math.abs(System.currentTimeMillis() - at) < 2 * 60_000L) show(c, type, prayer, adhan);
        schedule(c);
    }

    private static void show(Context c, int type, int prayer, long adhan) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        ensureChannels(c, nm);
        String name = c.getString(PrayerActivity.NAMES[prayer]);
        String title, text;
        long iqamaAt = adhan + PrayerTimes.IQAMA[prayer] * 60_000L;
        if (type == AlertPlan.SOON) {
            title = c.getString(R.string.pa_soon_title);
            text = c.getString(R.string.pa_soon_text, name);
        } else if (type == AlertPlan.ADHAN) {
            title = c.getString(R.string.pa_adhan_title, name);
            text = c.getString(R.string.pa_adhan_text, Lang.time(c, iqamaAt));
        } else {
            title = name;
            text = c.getString(R.string.pa_iqama_text);
        }
        PendingIntent open = PendingIntent.getActivity(c, 2, new Intent(c, PrayerActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = android.os.Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(c, CHANNELS[type]) : new Notification.Builder(c);
        b.setSmallIcon(R.drawable.ic_prayer)
                .setContentTitle(title)
                .setContentText(text)
                .setCategory(Notification.CATEGORY_REMINDER)
                .setContentIntent(open)
                .setAutoCancel(true)
                .setColor(c.getColor(R.color.orange))
                .setShowWhen(true);
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            // يختفي وحده: إشعار الأذان عند الإقامة، والبقية بعد خمس دقائق
            long ttl = type == AlertPlan.ADHAN ? Math.max(60_000L, iqamaAt - System.currentTimeMillis()) : 5 * 60_000L;
            b.setTimeoutAfter(ttl);
        } else {
            b.setPriority(Notification.PRIORITY_HIGH).setSound(sound(c, type));
        }
        try { nm.notify(NOTIF_ID, b.build()); } catch (SecurityException ignored) { } // لم يُسمح بالإشعارات
    }

    private static Uri sound(Context c, int type) {
        return Uri.parse("android.resource://" + c.getPackageName() + "/" + SOUNDS[type]);
    }

    private static void ensureChannels(Context c, NotificationManager nm) {
        if (android.os.Build.VERSION.SDK_INT < 26) return;
        int[] names = {R.string.pa_ch_soon, R.string.pa_ch_adhan, R.string.pa_ch_iqama};
        AudioAttributes aa = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build();
        for (int i = 0; i < 3; i++) {
            if (nm.getNotificationChannel(CHANNELS[i]) != null) continue;
            NotificationChannel ch = new NotificationChannel(CHANNELS[i], c.getString(names[i]), NotificationManager.IMPORTANCE_HIGH);
            ch.setSound(sound(c, i), aa);
            ch.enableVibration(false);
            nm.createNotificationChannel(ch);
        }
    }
}
