package com.santafe.launcher;

/**
 * جدول تنبيهات الصلاة، بلا أي اعتماد على أندرويد حتى يُختبر وحده.
 * لكل صلاة من الخمس ثلاثة تنبيهات: قبل الأذان بعشر دقائق، عند الأذان، وقبل الإقامة بخمس دقائق.
 */
public final class AlertPlan {

    public static final int SOON = 0, ADHAN = 1, IQAMA = 2;
    static final long SOON_BEFORE = 10 * 60_000L, IQAMA_BEFORE = 5 * 60_000L;

    public static final class Event {
        public final long at; public final int type, prayer; public final long adhanAt;
        Event(long at, int type, int prayer, long adhanAt) { this.at = at; this.type = type; this.prayer = prayer; this.adhanAt = adhanAt; }
    }

    private AlertPlan() { }

    /**
     * أقرب تنبيه بعد اللحظة now.
     * today و tomorrow: أوقات اليوم والغد (ستة أوقات بترتيب PrayerTimes)، iqama: دقائق الإقامة لكل وقت.
     */
    public static Event next(long now, long[] today, long[] tomorrow, int[] iqama) {
        Event best = null;
        for (long[] day : new long[][]{today, tomorrow}) {
            for (int k : PrayerTimes.PRAYERS) {
                long adhan = day[k];
                long[] at = {adhan - SOON_BEFORE, adhan, adhan + iqama[k] * 60_000L - IQAMA_BEFORE};
                for (int type = 0; type < 3; type++) {
                    // تنبيه الإقامة يُتخطى إن كانت الإقامة بعد الأذان بخمس دقائق أو أقل (لا معنى له)
                    if (type == IQAMA && iqama[k] * 60_000L <= IQAMA_BEFORE) continue;
                    if (at[type] > now && (best == null || at[type] < best.at)) best = new Event(at[type], type, k, adhan);
                }
            }
            if (best != null) return best;
        }
        return best;
    }

    /**
     * مؤقت الإقامة: إن كنا بين أذان صلاة وإقامتها (أو في الدقيقة بعد الإقامة) يعيد {رقم الصلاة، المتبقي بالملّي}،
     * والمتبقي سالب أو صفر يعني «أقيمت الصلاة». وإلا null.
     */
    public static long[] iqamaWindow(long now, long[] today, int[] iqamaMinutes) {
        if (today == null) return null;
        for (int k : PrayerTimes.PRAYERS) {
            if (iqamaMinutes[k] <= 0) continue;
            long iq = today[k] + iqamaMinutes[k] * 60_000L;
            if (now >= today[k] && now < iq + 60_000L) return new long[]{k, iq - now};
        }
        return null;
    }
}
