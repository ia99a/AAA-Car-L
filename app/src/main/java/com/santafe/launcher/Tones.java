package com.santafe.launcher;

import android.content.Context;

/** مجموعات نغمات تنبيهات الصلاة. كل مجموعة ثلاث نغمات: قبل الأذان، الأذان، قبل الإقامة. */
final class Tones {
    private Tones() { }

    static final String[] STYLES = {"bell", "qanun", "ney", "crystal", "wind", "marimba"};

    static final int[] NAMES = {R.string.tone_bell, R.string.tone_qanun, R.string.tone_ney,
            R.string.tone_crystal, R.string.tone_wind, R.string.tone_marimba};

    private static final int[][] SOUNDS = {
            {R.raw.tone_bell_soon, R.raw.tone_bell_adhan, R.raw.tone_bell_iqama},
            {R.raw.tone_qanun_soon, R.raw.tone_qanun_adhan, R.raw.tone_qanun_iqama},
            {R.raw.tone_ney_soon, R.raw.tone_ney_adhan, R.raw.tone_ney_iqama},
            {R.raw.tone_crystal_soon, R.raw.tone_crystal_adhan, R.raw.tone_crystal_iqama},
            {R.raw.tone_wind_soon, R.raw.tone_wind_adhan, R.raw.tone_wind_iqama},
            {R.raw.tone_marimba_soon, R.raw.tone_marimba_adhan, R.raw.tone_marimba_iqama},
    };

    /** رقم المجموعة المختارة (أي قيمة غير معروفة تعود للجرس) */
    static int current(Context c) {
        String s = Lang.prefs(c).getString("tone_style", "bell");
        for (int i = 0; i < STYLES.length; i++) if (STYLES[i].equals(s)) return i;
        return 0;
    }

    static void set(Context c, int style) {
        if (style < 0 || style >= STYLES.length) return;
        Lang.prefs(c).edit().putString("tone_style", STYLES[style]).apply();
    }

    /** type: 0 قبل الأذان، 1 الأذان، 2 قبل الإقامة */
    static int sound(int style, int type) {
        return SOUNDS[style][type];
    }
}
