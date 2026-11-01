package com.santafe.launcher;

/**
 * آخر ما رآه المشغّل من الصوت والمكالمات، لعرضه في «فحص الكبسولتين» بالإعدادات.
 * في الذاكرة فقط (يُمسح بإعادة التشغيل)، وبلا محتوى: اسم الحزمة ونوع الإشعار وأسماء أزراره فقط.
 */
final class Diag {
    private Diag() { }

    static volatile long volumeAt;      // آخر تغيير صوت رأيناه
    static volatile String volumeFrom;  // "keys" من أزرار الصوت، "system" من بث النظام
    static volatile int volumeStream = -1;
    static volatile boolean keysIgnored; // أزرار الصوت لا تغيّر صوت أندرويد في هذه الشاشة: تُركت للنظام

    static volatile long callAt;        // آخر إشعار مكالمة (أو مرشّح لها) من تطبيق هاتف
    static volatile String callPkg, callCategory, callButtons;
    static volatile boolean callRecognized;

    static volatile long hiddenAt;      // آخر مرة أخفينا فيها شاشة المكالمة الأصلية
    static volatile String hiddenPkg;

    static void volume(String from, int stream) {
        volumeAt = System.currentTimeMillis();
        volumeFrom = from;
        volumeStream = stream;
    }

    static void call(String pkg, String category, String buttons, boolean recognized) {
        callAt = System.currentTimeMillis();
        callPkg = pkg;
        callCategory = category;
        callButtons = buttons;
        callRecognized = recognized;
    }
}
