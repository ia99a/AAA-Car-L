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
 *  voice  : صوت يقرأ التنبيه («حان الآن موعد أذان الظهر») بمحرّك النطق في الجهاز
 *  beep   : نبضات تنبيه بسيطة بنفس الدرجة، بلا لحن
 *  silent : إشعار مكتوب فقط
 */
final class Tones {
    private Tones() { }

    static final int VOICE = 0, BEEP = 1, SILENT = 2;
    static final String[] STYLES = {"voice", "beep", "silent"};
    static final int[] NAMES = {R.string.tone_voice, R.string.tone_beep, R.string.tone_silent};

    private static final int[] BEEPS = {R.raw.tone_beep_soon, R.raw.tone_beep_adhan, R.raw.tone_beep_iqama};

    /** الطريقة المختارة؛ الافتراضي الصوت، وأي قيمة قديمة (نغمات الإصدار ١.٧) تعود للافتراضي */
    static int current(Context c) {
        String s = Lang.prefs(c).getString("tone_style", "voice");
        for (int i = 0; i < STYLES.length; i++) if (STYLES[i].equals(s)) return i;
        return VOICE;
    }

    static void set(Context c, int style) {
        if (style < 0 || style >= STYLES.length) return;
        Lang.prefs(c).edit().putString("tone_style", STYLES[style]).apply();
    }

    /** type: 0 قبل الأذان، 1 الأذان، 2 قبل الإقامة */
    static int beep(int type) {
        return BEEPS[type];
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
