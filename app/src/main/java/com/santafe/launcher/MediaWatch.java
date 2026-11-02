package com.santafe.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.List;

/**
 * الصوت الشغّال الآن (يوتيوب، Brave، أي تطبيق له جلسة وسائط) لأزرار الصوت في شريط السائق.
 * يحتاج إذن «الوصول للإشعارات» نفسه الذي تستخدمه كبسولة المكالمات. لا يقرأ إلا حالة التشغيل.
 */
final class MediaWatch {
    private MediaWatch() { }

    interface Listener { void onMediaChanged(); }

    /** بعد الإيقاف المؤقت تبقى الأزرار دقيقة لتستأنف، ثم تختفي */
    private static final long KEEP_AFTER_PAUSE = 60_000;

    private static final List<Listener> listeners = new ArrayList<>();
    private static final Handler h = new Handler(Looper.getMainLooper());
    private static MediaSessionManager msm;
    private static ComponentName cn;
    private static boolean started;
    private static MediaController active;
    private static long lastPlaying;
    private static AudioManager am;

    private static final MediaSessionManager.OnActiveSessionsChangedListener sessions = MediaWatch::pick;

    private static final MediaController.Callback callback = new MediaController.Callback() {
        @Override public void onPlaybackStateChanged(PlaybackState s) { refreshPick(); }
        @Override public void onMetadataChanged(MediaMetadata m) { notifyAll_(); }
        @Override public void onSessionDestroyed() { refreshPick(); }
    };

    private static final Runnable recheck = MediaWatch::notifyAll_;

    static void add(Context c, Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
        start(c);
    }

    static void remove(Listener l) {
        listeners.remove(l);
        if (listeners.isEmpty()) stop();
    }

    /** يبدأ المتابعة (ويعيد المحاولة كل مرة حتى يُمنح الإذن) */
    private static void start(Context c) {
        if (started) return;
        Context app = c.getApplicationContext();
        am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        msm = (MediaSessionManager) app.getSystemService(Context.MEDIA_SESSION_SERVICE);
        cn = new ComponentName(app, CallWatcher.class);
        if (msm == null) return;
        try {
            msm.addOnActiveSessionsChangedListener(sessions, cn, h);
            started = true;
            pick(msm.getActiveSessions(cn));
        } catch (SecurityException e) { // لم يُمنح إذن الوصول للإشعارات بعد
            started = false;
        }
    }

    private static void stop() {
        if (msm != null && started) try { msm.removeOnActiveSessionsChangedListener(sessions); } catch (Exception ignored) { }
        started = false;
        setActive(null);
        h.removeCallbacks(recheck);
    }

    private static void refreshPick() {
        if (msm == null || !started) { notifyAll_(); return; }
        try { pick(msm.getActiveSessions(cn)); } catch (SecurityException e) { setActive(null); notifyAll_(); }
    }

    /** الشغّال أولًا، وإلا المتوقف مؤقتًا الأحدث */
    private static void pick(List<MediaController> list) {
        MediaController best = null;
        if (list != null) {
            for (MediaController c : list) if (state(c) == PlaybackState.STATE_PLAYING) { best = c; break; }
            if (best == null) for (MediaController c : list) {
                int s = state(c);
                if (s == PlaybackState.STATE_PAUSED || s == PlaybackState.STATE_BUFFERING) { best = c; break; }
            }
        }
        setActive(best);
        notifyAll_();
    }

    private static void setActive(MediaController c) {
        if (active != null && (c == null || !active.getSessionToken().equals(c.getSessionToken()))) {
            try { active.unregisterCallback(callback); } catch (Exception ignored) { }
        }
        if (c != null && (active == null || !active.getSessionToken().equals(c.getSessionToken()))) {
            c.registerCallback(callback, h);
        }
        active = c;
    }

    private static int state(MediaController c) {
        PlaybackState s = c == null ? null : c.getPlaybackState();
        return s == null ? PlaybackState.STATE_NONE : s.getState();
    }

    private static void notifyAll_() {
        h.removeCallbacks(recheck);
        if (playing()) lastPlaying = SystemClock.elapsedRealtime();
        else if (visible()) h.postDelayed(recheck, KEEP_AFTER_PAUSE + 200); // نخفي الأزرار بعد مهلة الإيقاف
        for (Listener l : new ArrayList<>(listeners)) l.onMediaChanged();
    }

    // ───────── للواجهة ─────────

    static boolean playing() {
        int s = state(active);
        return s == PlaybackState.STATE_PLAYING || s == PlaybackState.STATE_BUFFERING;
    }

    /** تظهر الأزرار: صوت شغّال، أو أوقف قبل أقل من دقيقة */
    static boolean visible() {
        if (active == null) return false;
        if (playing()) return true;
        return state(active) == PlaybackState.STATE_PAUSED && SystemClock.elapsedRealtime() - lastPlaying < KEEP_AFTER_PAUSE;
    }

    static String packageName() { return active == null ? null : active.getPackageName(); }

    static void toggle() {
        if (active == null) return;
        if (playing()) active.getTransportControls().pause(); else active.getTransportControls().play();
    }

    /** تقديم أو ترجيع بالثواني: إلى موضع محدد إن دعمه التطبيق، وإلا تقديم/ترجيع التطبيق نفسه */
    static void seek(long deltaMs) {
        MediaController c = active;
        PlaybackState s = c == null ? null : c.getPlaybackState();
        if (s == null) return;
        long actions = s.getActions();
        if ((actions & PlaybackState.ACTION_SEEK_TO) != 0 && s.getPosition() >= 0) {
            long pos = s.getPosition();
            if (s.getState() == PlaybackState.STATE_PLAYING)
                pos += (long) ((SystemClock.elapsedRealtime() - s.getLastPositionUpdateTime()) * s.getPlaybackSpeed());
            long target = Math.max(0, pos + deltaMs);
            MediaMetadata m = c.getMetadata();
            long dur = m == null ? 0 : m.getLong(MediaMetadata.METADATA_KEY_DURATION);
            if (dur > 0) target = Math.min(target, Math.max(0, dur - 1000));
            c.getTransportControls().seekTo(target);
        } else if (deltaMs > 0 && (actions & PlaybackState.ACTION_FAST_FORWARD) != 0) {
            c.getTransportControls().fastForward();
        } else if (deltaMs < 0 && (actions & PlaybackState.ACTION_REWIND) != 0) {
            c.getTransportControls().rewind();
        }
    }

    static boolean muted() {
        return am != null && am.isStreamMute(AudioManager.STREAM_MUSIC);
    }

    /** كتم صوت الوسائط أو إلغاؤه (تظهر معه كبسولة الصوت) */
    static void toggleMute() {
        if (am == null) return;
        try { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, 0); }
        catch (SecurityException ignored) { } // «عدم الإزعاج» يمنع
        h.postDelayed(MediaWatch::notifyAll_, 150);
    }
}
