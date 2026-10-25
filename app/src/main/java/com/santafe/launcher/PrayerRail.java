package com.santafe.launcher;

import android.content.Context;
import android.widget.TextView;

import java.util.Calendar;

/** أداة الصلاة في شريط السائق (في الرئيسية وفي الشريط العائم فوق هدهد): الصلاة القادمة، أو اسم الصلاة الحالية مع الوقت الباقي على إقامتها */
final class PrayerRail {

    private long[] today, tomorrow;
    private int day = -1;
    private String city;

    /** أوقات اليوم للمدينة المختارة (تُحسب مرة في اليوم أو عند تغيير المدينة) */
    long[] today(Context c) {
        Calendar now = Calendar.getInstance();
        String k = PrayerActivity.cityKey(c);
        if (day != now.get(Calendar.DAY_OF_YEAR) || !k.equals(city) || today == null) {
            day = now.get(Calendar.DAY_OF_YEAR);
            city = k;
            PrayerTimes.City pc = PrayerTimes.city(k);
            today = PrayerTimes.forDay(now, pc);
            Calendar tm = (Calendar) now.clone();
            tm.add(Calendar.DAY_OF_YEAR, 1);
            tomorrow = PrayerTimes.forDay(tm, pc);
        }
        return today;
    }

    void invalidate() { day = -1; }

    void bind(Context c, TextView name, TextView time) {
        if (name == null || time == null) return;
        long[] td = today(c);
        long t = System.currentTimeMillis();
        // بين الأذان والإقامة: اسم الصلاة مع الوقت الباقي على الإقامة بالأخضر
        for (int k : PrayerTimes.PRAYERS) {
            long iq = td[k] + PrayerTimes.IQAMA[k] * 60_000L;
            if (t >= td[k] && t < iq) {
                name.setText(PrayerActivity.NAMES[k]);
                time.setText(Lang.left(c, iq - t));
                time.setTextColor(c.getColor(R.color.green));
                return;
            }
        }
        time.setTextColor(c.getColor(R.color.orange));
        int next = -1;
        for (int k : PrayerTimes.PRAYERS) if (td[k] > t) { next = k; break; }
        long at = next == -1 ? tomorrow[PrayerTimes.FAJR] : td[next];
        if (next == -1) next = PrayerTimes.FAJR;
        name.setText(PrayerActivity.NAMES[next]);
        // قبل الأذان بربع ساعة: الوقت الباقي بدل الساعة
        time.setText(at - t <= 15 * 60_000L ? Lang.left(c, at - t) : Lang.time(c, at, false));
    }
}
