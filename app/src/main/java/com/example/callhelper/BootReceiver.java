package com.example.callhelper;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * بعد إقلاع الجوال: أندرويد 11+ لا يسمح لخدمة الميكروفون بالبدء من الخلفية،
 * فنعرض إشعاراً "اضغط للتشغيل"، والضغط عليه يفتح التطبيق ويشغّل الخدمة تلقائياً.
 * لا يظهر الإشعار إلا إذا كان المستخدم قد شغّل الخدمة سابقاً ولم يوقفها يدوياً.
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String CH = "callhelper_boot";

    @Override public void onReceive(Context ctx, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        String a = intent.getAction();
        boolean boot = Intent.ACTION_BOOT_COMPLETED.equals(a)
                || "android.intent.action.QUICKBOOT_POWERON".equals(a)
                || "com.htc.intent.action.QUICKBOOT_POWERON".equals(a)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(a);
        if (!boot) return;
        try {
            // الوضع التلقائي: نشغّل الخدمة مباشرة (والإشعار يظهر فقط إذا فشل ذلك)
            if (AutoStarter.enabled(ctx)) { AutoStarter.tryStart(ctx, "boot:" + a); return; }
            boolean want = ctx.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
                    .getBoolean(MainActivity.PREF_WANT_RUNNING, false);
            if (want) showRestartNotification(ctx);
        } catch (Exception ignored) {}
    }

    /** إشعار "اضغط للتشغيل". يُستعمل بعد الإقلاع، وأيضاً إذا قتل النظام الخدمة وتعذّرت إعادتها من الخلفية. */
    public static void showRestartNotification(Context ctx) {
        try {
            NotificationManager nm = ctx.getSystemService(NotificationManager.class);
            if (nm == null) return;
            nm.createNotificationChannel(new NotificationChannel(CH, "تشغيل بعد الإقلاع", NotificationManager.IMPORTANCE_DEFAULT));
            Intent open = new Intent(ctx, MainActivity.class);
            open.putExtra(MainActivity.EXTRA_AUTOSTART, true);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(ctx, 1, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification n = new Notification.Builder(ctx, CH)
                    .setSmallIcon(android.R.drawable.ic_menu_call)
                    .setContentTitle("CallHelper متوقف")
                    .setContentText("اضغط هنا لتشغيل الرد الصوتي")
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build();
            nm.notify(2, n);
        } catch (Exception ignored) {}
    }
}
