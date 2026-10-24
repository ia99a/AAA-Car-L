package com.santafe.launcher;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Calendar;

/** أوقات الصلاة لليوم: ست بطاقات، القادمة مميزة، مع اختيار المدينة */
public class PrayerActivity extends Activity {

    static final int[] NAMES = {R.string.fajr, R.string.sunrise, R.string.dhuhr,
            R.string.asr, R.string.maghrib, R.string.isha};

    @Override
    protected void attachBaseContext(Context base) { super.attachBaseContext(Lang.wrap(base)); }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_prayer);
        findViewById(R.id.btn_home).setOnClickListener(v -> finish());
        ((TextView) findViewById(R.id.hijri)).setText(hijriToday(this));
        buildCities();
        buildTimes();
    }

    static String cityKey(Context c) {
        return Lang.prefs(c).getString("city", "riyadh");
    }

    private void buildCities() {
        LinearLayout row = findViewById(R.id.cities);
        row.removeAllViews();
        float dp = getResources().getDisplayMetrics().density;
        String current = cityKey(this);
        for (PrayerTimes.City c : PrayerTimes.CITIES) {
            TextView chip = new TextView(this);
            chip.setText(c.name);
            chip.setTextSize(14);
            chip.setTextColor(getColor(R.color.ink));
            chip.setBackgroundResource(R.drawable.chip_bg);
            chip.setGravity(android.view.Gravity.CENTER);
            chip.setPadding((int) (16 * dp), 0, (int) (16 * dp), 0);
            chip.setSelected(c.key.equals(current));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, (int) (38 * dp));
            lp.setMarginEnd((int) (8 * dp));
            chip.setLayoutParams(lp);
            chip.setOnClickListener(v -> {
                Lang.prefs(this).edit().putString("city", c.key).apply();
                PrayerAlerts.schedule(this);
                buildCities();
                buildTimes();
            });
            row.addView(chip);
        }
    }

    private void buildTimes() {
        LinearLayout row = findViewById(R.id.times);
        row.removeAllViews();
        float dp = getResources().getDisplayMetrics().density;
        PrayerTimes.City city = PrayerTimes.city(cityKey(this));
        long[] t = PrayerTimes.forDay(Calendar.getInstance(), city);
        long now = System.currentTimeMillis();
        int next = -1;
        for (int k : PrayerTimes.PRAYERS) if (t[k] > now) { next = k; break; }
        if (next == -1) next = PrayerTimes.FAJR; // بعد العشاء: فجر الغد

        LayoutInflater inf = LayoutInflater.from(this);
        for (int i = 0; i < 6; i++) {
            View card = inf.inflate(R.layout.item_prayer, row, false);
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) card.getLayoutParams();
            if (i > 0) lp.setMarginStart((int) (8 * dp));
            boolean isNext = i == next;
            card.setSelected(isNext);
            ((TextView) card.findViewById(R.id.p_name)).setText(NAMES[i]);
            TextView time = card.findViewById(R.id.p_time);
            time.setText(Lang.time(this, t[i]));
            if (t[i] < now && !isNext) time.setTextColor(getColor(R.color.ink_faint));
            TextView tag = card.findViewById(R.id.p_tag);
            if (isNext) {
                tag.setVisibility(View.VISIBLE);
                long at = t[i] > now ? t[i] : t[i] + 24 * 3600_000L;
                tag.setText(getString(R.string.prayer_next) + " · " + getString(R.string.prayer_in, Lang.left(this, at - now)));
            }
            int iq = PrayerTimes.IQAMA[i];
            ((TextView) card.findViewById(R.id.p_iqama)).setText(
                    iq == 0 ? " " : getString(R.string.prayer_iqama, Lang.time(this, t[i] + iq * 60_000L)));
            row.addView(card, lp);
        }
    }

    static String hijriToday(Context c) {
        try {
            android.icu.util.ULocale loc = new android.icu.util.ULocale(
                    Lang.isArabic(c) ? "ar_SA@calendar=islamic-umalqura" : "en@calendar=islamic-umalqura");
            android.icu.text.DateFormat f = android.icu.text.DateFormat.getDateInstance(
                    android.icu.text.DateFormat.LONG, loc);
            return f.format(new java.util.Date());
        } catch (Throwable e) {
            return "";
        }
    }

    // شريط السائق العائم (وضع هدهد) يختفي فوق شاشات المشغّل
    @Override
    protected void onResume() {
        super.onResume();
        OverlayService.suppress(true);
    }

    @Override
    protected void onPause() {
        super.onPause();
        OverlayService.suppress(false);
    }
}
