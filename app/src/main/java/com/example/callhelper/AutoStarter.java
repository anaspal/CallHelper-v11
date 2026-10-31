package com.example.callhelper;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.util.Log;

/**
 * التشغيل التلقائي بعد إعادة تشغيل الجوال. يُستدعى من أكثر من مدخل حتى لو منع النظام (مثل MIUI) أحدها:
 *  1) BootReceiver (BOOT_COMPLETED)
 *  2) InCallAccessibilityService.onServiceConnected  (النظام يربطها بنفسه عند الإقلاع)
 *  3) CallNotificationListener.onListenerConnected   (نفس الفكرة)
 * إذا فشل التشغيل نعرض إشعار "اضغط للتشغيل" كخطة احتياطية.
 */
public final class AutoStarter {
    private static final String TAG = "AutoStart";
    private AutoStarter() {}

    public static boolean enabled(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
        return sp.getBoolean(MainActivity.PREF_AUTOBOOT, false)
                && sp.getBoolean(MainActivity.PREF_WANT_RUNNING, false);
    }

    public static void tryStart(Context ctx, String why) {
        try {
            if (!enabled(ctx)) return;
            if (VoiceCommandService.isRunning()) return;
            if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
                    || ctx.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED
                    || ctx.checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "permissions missing, skip (" + why + ")");
                BootReceiver.showRestartNotification(ctx);
                return;
            }
            Log.d(TAG, "starting service from " + why);
            ctx.startForegroundService(new Intent(ctx, VoiceCommandService.class));
        } catch (Exception e) {
            Log.e(TAG, "start failed (" + why + ")", e);
            BootReceiver.showRestartNotification(ctx);
        }
    }
}
