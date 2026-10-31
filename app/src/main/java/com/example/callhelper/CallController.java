package com.example.callhelper;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.telecom.TelecomManager;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.KeyEvent;

public class CallController {
    private static final String TAG = "CallCtrl";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    // آخر حالة معروفة (احتياط فقط إذا تعذّر سؤال النظام مباشرة)
    private static volatile int sCallState = TelephonyManager.CALL_STATE_IDLE;

    public static void setCallState(int state) { sCallState = state; }

    private static volatile String sLastAnswerInfo = "";
    // لمنع تشغيل أكثر من سلسلة رد في نفس الرنّة (صوت + تلويح + زر في نفس الوقت)
    private static volatile long sLastAnswerStart = 0;
    private static final long ANSWER_COOLDOWN_MS = 3600;
    public static String lastAnswerInfo() { return sLastAnswerInfo; }

    /** حالة المكالمة الحقيقية من النظام، أو -1 إذا تعذّر. */
    @SuppressWarnings("deprecation")
    private static int liveState(Context ctx) {
        try {
            TelephonyManager tm = (TelephonyManager) ctx.getSystemService(Context.TELEPHONY_SERVICE);
            if (tm != null) return tm.getCallState();
        } catch (Exception e) { Log.w(TAG, "getCallState failed", e); }
        return -1;
    }

    /**
     * الرد على المكالمة بسلسلة طرق، كل طريقة تُجرَّب فقط إذا فشلت التي قبلها:
     * 1) TelecomManager.acceptRingingCall   2) زر سماعة الرأس (HEADSETHOOK)
     * 3) الضغط على زر "رد" في شاشة المكالمة عبر Accessibility
     */
    public static void answerCall(Context ctx) {
        final long nowMs = SystemClock.uptimeMillis();
        if (nowMs - sLastAnswerStart < ANSWER_COOLDOWN_MS) {
            Log.d(TAG, "answerCall ignored: a previous attempt is still running");
            return;
        }
        sLastAnswerStart = nowMs;
        final Context app = ctx.getApplicationContext();
        final StringBuilder info = new StringBuilder();
        sLastAnswerInfo = "";
        tryAccept(ctx, info);
        sLastAnswerInfo = info.toString();

        // سلسلة طرق: كل مرحلة تُنفَّذ فقط إذا بقي الجوال يرن
        later(500, app, "إشعار", () -> {
            boolean ok = CallNotificationListener.answerViaNotification();
            note(ok ? "الإشعار: تم" : "الإشعار: " + CallNotificationListener.lastInfo());
        });
        later(1100, app, "شاشة", () -> {
            boolean ok = InCallAccessibilityService.clickAnswer();
            note(ok ? "زر الشاشة: تم" : "زر الشاشة: ما لقى الزر");
        });
        later(1800, app, "سماعة", () -> { sendHeadsetHook(app); note("زر السماعة أُرسل"); });
        later(2500, app, "سحب", () -> {
            boolean ok = InCallAccessibilityService.swipeAnswer();
            note(ok ? "سحب الشاشة: تم" : "سحب الشاشة: ما لقى الزر");
        });
        later(3300, app, "إعادة", () -> {
            StringBuilder i2 = new StringBuilder();
            tryAccept(app, i2);
            InCallAccessibilityService.clickAnswer();
            note("إعادة: " + i2);
        });
    }

    private static void tryAccept(Context ctx, StringBuilder info) {
        if (Build.VERSION.SDK_INT >= 26
                && ctx.checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
            try {
                TelecomManager tm = (TelecomManager) ctx.getSystemService(Context.TELECOM_SERVICE);
                if (tm != null) { tm.acceptRingingCall(); info.append("acceptRingingCall أُرسل"); }
            } catch (Exception e) {
                Log.e(TAG, "acceptRingingCall", e);
                info.append("acceptRingingCall فشل: ").append(e.getClass().getSimpleName());
            }
        } else {
            info.append("إذن ANSWER_PHONE_CALLS غير ممنوح");
        }
    }

    public static void note(String s) { sLastAnswerInfo = sLastAnswerInfo + " | " + s; }

    private static void later(long delayMs, final Context app, final String name, final Runnable r) {
        MAIN.postDelayed(() -> {
            if (!ringing(app)) return;
            Log.w(TAG, "still ringing -> " + name);
            try { r.run(); } catch (Exception e) { Log.e(TAG, name, e); }
        }, delayMs);
    }

    /** إنهاء المكالمة (أو رفضها أثناء الرنين): TelecomManager.endCall ثم الضغط على زر الإنهاء في الشاشة. */
    public static void endCall(Context ctx) {
        final Context app = ctx.getApplicationContext();
        if (Build.VERSION.SDK_INT >= 28
                && ctx.checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
            try {
                TelecomManager tm = (TelecomManager) ctx.getSystemService(Context.TELECOM_SERVICE);
                if (tm != null) tm.endCall();
            } catch (Exception e) { Log.e(TAG, "endCall", e); }
        }
        MAIN.postDelayed(new Runnable() {
            @Override public void run() {
                int st = liveState(app);
                if (st < 0 || st == TelephonyManager.CALL_STATE_IDLE) return;
                Log.w(TAG, "call still active -> click End on screen");
                InCallAccessibilityService.clickDecline();
            }
        }, 600);
    }

    /** احتياط: زر سماعة الرأس (HEADSETHOOK) يرد على المكالمة في أغلب الأجهزة. */
    private static void sendHeadsetHook(Context ctx) {
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;
            long now = SystemClock.uptimeMillis();
            am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HEADSETHOOK, 0));
            am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_HEADSETHOOK, 0));
        } catch (Exception e) { Log.e(TAG, "headset hook", e); }
    }

    public static void toggleSpeaker(Context ctx) {
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;
        try { am.setSpeakerphoneOn(!am.isSpeakerphoneOn()); } catch (Exception e) { Log.e(TAG, "spk", e); }
    }

    public static boolean inCall(Context ctx) {
        int live = liveState(ctx);
        if (live >= 0) return live == TelephonyManager.CALL_STATE_OFFHOOK;
        if (sCallState == TelephonyManager.CALL_STATE_OFFHOOK) return true;
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        return am != null && am.getMode() == AudioManager.MODE_IN_CALL;
    }

    public static boolean ringing(Context ctx) {
        int live = liveState(ctx);
        if (live == TelephonyManager.CALL_STATE_RINGING) return true;
        if (live == TelephonyManager.CALL_STATE_OFFHOOK) return false;
        boolean ringtoneMode = false;
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            ringtoneMode = am != null && am.getMode() == AudioManager.MODE_RINGTONE;
        } catch (Exception ignored) {}
        // تعذّر سؤال النظام: نعتمد على آخر حالة معروفة أو وضع الصوت
        if (live < 0) return sCallState == TelephonyManager.CALL_STATE_RINGING || ringtoneMode;
        // النظام يقول "خامل": لا نصدّق وضع الصوت وحده (تطبيقات الاتصال عبر الإنترنت قد تضبطه)،
        // بل نطلب أن يؤكده أيضاً بثّ حالة الهاتف (يغطي الأجهزة ثنائية الشريحة)
        return sCallState == TelephonyManager.CALL_STATE_RINGING && ringtoneMode;
    }
}
