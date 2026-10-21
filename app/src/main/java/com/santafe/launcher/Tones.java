package com.santafe.launcher;

import android.content.Context;
import android.media.AudioAttributes;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.util.Locale;

/**
 * طريقة تنبيه الصلاة، بلا موسيقى:
 *  voice      : صوت يقرأ التنبيه («حان الآن موعد أذان الظهر») بمحرّك النطق في الجهاز
 *  beep_voice : نبضات ثم الصوت
 *  pulse, tick, long, sonar, clear, soft : تنبيهات بدرجة صوت واحدة ثابتة، بلا لحن (tools/tones.py)
 *  silent     : إشعار مكتوب فقط
 */
final class Tones {
    private Tones() { }

    static final int VOICE = 0, BEEP_VOICE = 1, SILENT = 8;
    static final String[] STYLES = {"voice", "beep_voice", "pulse", "tick", "long", "sonar", "clear", "soft", "silent"};
    static final int[] NAMES = {R.string.tone_voice, R.string.tone_beep_voice, R.string.tone_pulse, R.string.tone_tick,
            R.string.tone_long, R.string.tone_sonar, R.string.tone_clear, R.string.tone_soft, R.string.tone_silent};

    /** نغمات الأنماط (للأنماط الصوتية والصامت: نبضات بديلة) */
    private static final int[][] SOUNDS = {
            {R.raw.tone_pulse_soon, R.raw.tone_pulse_adhan, R.raw.tone_pulse_iqama},
            {R.raw.tone_pulse_soon, R.raw.tone_pulse_adhan, R.raw.tone_pulse_iqama},
            {R.raw.tone_pulse_soon, R.raw.tone_pulse_adhan, R.raw.tone_pulse_iqama},
            {R.raw.tone_tick_soon, R.raw.tone_tick_adhan, R.raw.tone_tick_iqama},
            {R.raw.tone_long_soon, R.raw.tone_long_adhan, R.raw.tone_long_iqama},
            {R.raw.tone_sonar_soon, R.raw.tone_sonar_adhan, R.raw.tone_sonar_iqama},
            {R.raw.tone_clear_soon, R.raw.tone_clear_adhan, R.raw.tone_clear_iqama},
            {R.raw.tone_soft_soon, R.raw.tone_soft_adhan, R.raw.tone_soft_iqama},
            {R.raw.tone_pulse_soon, R.raw.tone_pulse_adhan, R.raw.tone_pulse_iqama},
    };

    /** الطريقة المختارة؛ الافتراضي الصوت، وأي قيمة قديمة (نغمات الإصدار ١.٧) تعود للافتراضي */
    static int current(Context c) {
        String s = Lang.prefs(c).getString("tone_style", "voice");
        if ("beep".equals(s)) s = "pulse"; // اسم الإصدار ١.٨
        for (int i = 0; i < STYLES.length; i++) if (STYLES[i].equals(s)) return i;
        return VOICE;
    }

    static void set(Context c, int style) {
        if (style < 0 || style >= STYLES.length) return;
        Lang.prefs(c).edit().putString("tone_style", STYLES[style]).apply();
    }

    /** هل النمط نغمة فقط (تُعطى لقناة الإشعار)؟ */
    static boolean isTone(int style) {
        return style != VOICE && style != BEEP_VOICE && style != SILENT;
    }

    static boolean speaks(int style) {
        return style == VOICE || style == BEEP_VOICE;
    }

    /** type: 0 قبل الأذان، 1 الأذان، 2 قبل الإقامة */
    static int sound(int style, int type) {
        return SOUNDS[style][type];
    }

    /** نص التنبيه المنطوق */
    static String spoken(Context c, int type, int prayer) {
        String name = c.getString(PrayerActivity.NAMES[prayer]);
        int res = type == AlertPlan.SOON ? R.string.say_soon : type == AlertPlan.ADHAN ? R.string.say_adhan : R.string.say_iqama;
        return c.getString(res, name);
    }

    /** يشغّل نغمة قصيرة من الموارد ويحرّرها عند الانتهاء */
    static void play(Context c, int res) {
        try {
            android.media.MediaPlayer mp = android.media.MediaPlayer.create(c, res);
            if (mp == null) return;
            mp.setOnCompletionListener(android.media.MediaPlayer::release);
            mp.start();
        } catch (Exception ignored) { }
    }

    /**
     * ينطق النص. إن لم يوجد محرّك نطق أو لغة الواجهة غير مدعومة فيه، يُستدعى fallback.
     * done يُستدعى مرة واحدة دائمًا: بعد انتهاء النطق، أو الفشل، أو بعد ٩ ثوانٍ كحد أقصى.
     */
    static void say(Context c, String text, Runnable fallback, Runnable done) {
        Context app = c.getApplicationContext();
        Locale loc = Lang.isArabic(c) ? new Locale("ar") : Locale.ENGLISH;
        Handler main = new Handler(Looper.getMainLooper());
        TextToSpeech[] tts = new TextToSpeech[1];
        boolean[] over = {false};
        Runnable finish = () -> {
            if (over[0]) return;
            over[0] = true;
            try { if (tts[0] != null) tts[0].shutdown(); } catch (Exception ignored) { }
            if (done != null) done.run();
        };
        Runnable fail = () -> {
            if (over[0]) return;
            if (fallback != null) fallback.run();
            main.postDelayed(finish, 3000); // وقت لإكمال النغمة البديلة
        };
        main.postDelayed(finish, 9000);
        try {
            tts[0] = new TextToSpeech(app, status -> main.post(() -> {
                TextToSpeech t = tts[0];
                if (over[0] || t == null) return;
                if (status != TextToSpeech.SUCCESS) { fail.run(); return; }
                int r = t.setLanguage(loc);
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) { fail.run(); return; }
                t.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
                t.setSpeechRate(0.9f);
                t.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String id) { }
                    @Override public void onDone(String id) { main.post(finish); }
                    @Override public void onError(String id) { main.post(fail); }
                });
                if (t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "prayer") != TextToSpeech.SUCCESS) fail.run();
            }));
        } catch (Exception e) {
            fail.run();
        }
    }
}
