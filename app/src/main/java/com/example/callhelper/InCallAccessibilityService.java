package com.example.callhelper;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class InCallAccessibilityService extends AccessibilityService {
    private static final String TAG = "A11y";

    private static volatile boolean sConnected = false;
    private static volatile InCallAccessibilityService sInstance = null;
    private static volatile long sLastKeyTime = 0;
    private static volatile int sLastKeyCode = 0;
    private static volatile int sKeyCount = 0;
    private static volatile String sLastKeyInfo = "";

    public static boolean isConnected() { return sConnected; }
    public static long lastKeyTime() { return sLastKeyTime; }
    public static int lastKeyCode() { return sLastKeyCode; }
    public static int keyCount() { return sKeyCount; }
    public static String lastKeyInfo() { return sLastKeyInfo; }

    /** يضغط زر "رد" في شاشة/إشعار المكالمة. يرجع true إذا وجد زراً وضغطه. */
    public static boolean clickAnswer() {
        InCallAccessibilityService s = sInstance;
        return s != null && s.clickButton(true);
    }

    /** احتياط لشاشات المكالمة التي تتطلب سحب زر الرد (شاشة القفل في بعض الأجهزة). */
    public static boolean swipeAnswer() {
        InCallAccessibilityService s = sInstance;
        return s != null && s.swipeOnButton();
    }

    /** يضغط زر "إنهاء/رفض" في شاشة/إشعار المكالمة. */
    public static boolean clickDecline() {
        InCallAccessibilityService s = sInstance;
        return s != null && s.clickButton(false);
    }

    private final android.os.Handler UI = new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean consumedUp = false;
    private boolean consumedDown = false;

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        try {
            AccessibilityServiceInfo info = getServiceInfo();
            if (info != null) {
                // نضمن تفعيل اعتراض الأزرار وقراءة النوافذ من الكود حتى لو تجاهلها النظام من ملف XML
                info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
                        | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                        | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
                setServiceInfo(info);
            }
        } catch (Exception e) { Log.w(TAG, "setServiceInfo failed", e); }
        sInstance = this;
        sConnected = true;
        Log.d(TAG, "service connected");
        AutoStarter.tryStart(this, "a11y");
    }

    @Override public boolean onUnbind(Intent intent) {
        sConnected = false;
        sInstance = null;
        Log.d(TAG, "service unbound");
        return super.onUnbind(intent);
    }

    @Override public void onDestroy() {
        sConnected = false;
        sInstance = null;
        super.onDestroy();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {}
    @Override public void onInterrupt() {}

    @Override public boolean onKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (code != KeyEvent.KEYCODE_VOLUME_UP && code != KeyEvent.KEYCODE_VOLUME_DOWN) return super.onKeyEvent(event);
        boolean up = (code == KeyEvent.KEYCODE_VOLUME_UP);

        // ACTION_UP والتكرار: نبتلعها فقط إذا ابتلعنا الـ DOWN الأصلي
        if (event.getAction() == KeyEvent.ACTION_UP) {
            boolean was = up ? consumedUp : consumedDown;
            if (up) consumedUp = false; else consumedDown = false;
            return was;
        }
        if (event.getRepeatCount() > 0) return up ? consumedUp : consumedDown;

        // مهم: النظام ينتظر ردنا 500ms فقط، وإن تأخرنا يمرّر الزر لنفسه فيكتم الرنين.
        // لذلك نقرّر بسرعة ونرجع فوراً، ونؤجّل العمل الثقيل (الرد/الإنهاء) إلى Handler.
        final boolean ringing = CallController.ringing(this);
        final boolean inCall = CallController.inCall(this);
        sLastKeyTime = System.currentTimeMillis();
        sLastKeyCode = code;
        sKeyCount = sKeyCount + 1;
        sLastKeyInfo = (up ? "رفع الصوت" : "خفض الصوت") + " | رنين=" + (ringing ? "نعم" : "لا")
                + " | مكالمة=" + (inCall ? "نعم" : "لا");
        Log.d(TAG, "key " + sLastKeyInfo);

        if (!ringing && !inCall) return super.onKeyEvent(event);

        // خيار "ترك الأزرار للنظام أثناء المكالمة": بعد الرد (OFFHOOK) لا نتدخل إطلاقاً
        if (inCall && !ringing && getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
                .getBoolean(MainActivity.PREF_KEYS_PASS_IN_CALL, false)) {
            return super.onKeyEvent(event);
        }

        if (up) {
            if (ringing) {
                UI.post(() -> { CallController.answerCall(InCallAccessibilityService.this); vibrate(80); });
            } else {
                UI.post(() -> { CallController.toggleSpeaker(InCallAccessibilityService.this); vibrate(40); });
            }
            consumedUp = true;
            return true;
        }
        if (inCall) {
            UI.post(() -> { CallController.endCall(InCallAccessibilityService.this); vibrate(80); });
            consumedDown = true;
            return true;
        }
        // خفض الصوت أثناء الرنين فقط: نترك النظام يكتم الرنين كالعادة
        return super.onKeyEvent(event);
    }

    // ---------------- الضغط على أزرار شاشة المكالمة (خطة احتياطية) ----------------

    private static final String[] ANSWER_LABELS = {
            "answer", "accept", "answer call", "accept call",
            "رد", "الرد", "الرد على المكالمة", "رد على المكالمة", "قبول", "قبول المكالمة"};
    private static final String[] DECLINE_LABELS = {
            "decline", "reject", "end call", "end", "hang up", "hangup",
            "إنهاء", "إنهاء المكالمة", "إنهاء الاتصال", "رفض", "رفض المكالمة"};
    private static final String[] ANSWER_IDS = {"answer", "accept"};
    private static final String[] DECLINE_IDS = {"decline", "reject", "end_call", "endcall", "hangup", "disconnect", "end_button", "endbutton"};
    private static final String[] ID_EXCLUDE = {"message", "sms", "hold", "swap", "merge", "speaker", "mute"};
    private static final String[] PKG_HINTS = {"call", "dialer", "phone", "telecom", "systemui", "contacts"};

    private List<AccessibilityNodeInfo> collectRoots() {
        List<AccessibilityNodeInfo> roots = new ArrayList<>();
        try {
            List<AccessibilityWindowInfo> wins = getWindows();
            if (wins != null) {
                for (AccessibilityWindowInfo w : wins) {
                    AccessibilityNodeInfo r = w.getRoot();
                    if (r != null) roots.add(r);
                }
            }
        } catch (Exception e) { Log.w(TAG, "getWindows failed", e); }
        try {
            AccessibilityNodeInfo active = getRootInActiveWindow();
            if (active != null) roots.add(active);
        } catch (Exception e) { Log.w(TAG, "getRootInActiveWindow failed", e); }
        return roots;
    }

    private AccessibilityNodeInfo findNode(boolean answer) {
        for (AccessibilityNodeInfo r : collectRoots()) {
            if (!isCallRelatedPackage(r)) continue;
            AccessibilityNodeInfo n = findMatch(r, answer, 0);
            if (n != null) return n;
        }
        return null;
    }

    private AccessibilityNodeInfo findMatch(AccessibilityNodeInfo n, boolean answer, int depth) {
        if (n == null || depth > 40) return null;
        if (matches(n, answer)) return n;
        int count = n.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo f = findMatch(n.getChild(i), answer, depth + 1);
            if (f != null) return f;
        }
        return null;
    }

    private boolean clickButton(boolean answer) {
        AccessibilityNodeInfo n = findNode(answer);
        if (n == null) { Log.w(TAG, "no " + (answer ? "answer" : "decline") + " button found"); return false; }
        AccessibilityNodeInfo t = n;
        int up = 0;
        while (t != null && !t.isClickable() && up < 4) { t = t.getParent(); up++; }
        if (t != null && t.isClickable() && t.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            Log.d(TAG, "clicked " + (answer ? "answer" : "decline"));
            return true;
        }
        // فشل الضغط البرمجي: نلمس مكان الزر على الشاشة بإيماءة
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        if (r.isEmpty()) return false;
        Path p = new Path();
        p.moveTo(r.exactCenterX(), r.exactCenterY());
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 80)).build();
        boolean ok = dispatchGesture(g, null, null);
        Log.d(TAG, "tap gesture at " + r.centerX() + "," + r.centerY() + " -> " + ok);
        return ok;
    }

    private boolean swipeOnButton() {
        AccessibilityNodeInfo n = findNode(true);
        if (n == null) return false;
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        if (r.isEmpty()) return false;
        float x = r.exactCenterX(), y = r.exactCenterY();
        Path p = new Path();
        p.moveTo(x, y);
        p.lineTo(x, Math.max(10f, y - 600f));
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 350)).build();
        return dispatchGesture(g, null, null);
    }

    private boolean isCallRelatedPackage(AccessibilityNodeInfo root) {
        CharSequence p = root.getPackageName();
        if (p == null) return false;
        String s = p.toString().toLowerCase(Locale.ROOT);
        for (String h : PKG_HINTS) if (s.contains(h)) return true;
        return false;
    }

    private boolean matches(AccessibilityNodeInfo n, boolean answer) {
        String[] labels = answer ? ANSWER_LABELS : DECLINE_LABELS;
        String t = norm(n.getText());
        String d = norm(n.getContentDescription());
        for (String l : labels) {
            if (l.equals(t) || l.equals(d)) return true;
            if (l.length() >= 6 && d != null && d.contains(l)) return true;
        }
        String id = null;
        try { id = n.getViewIdResourceName(); } catch (Exception ignored) {}
        if (id != null) {
            id = id.toLowerCase(Locale.ROOT);
            for (String ex : ID_EXCLUDE) if (id.contains(ex)) return false;
            for (String k : (answer ? ANSWER_IDS : DECLINE_IDS)) if (id.contains(k)) return true;
        }
        return false;
    }

    private static String norm(CharSequence cs) {
        return cs == null ? null : cs.toString().trim().toLowerCase(Locale.ROOT);
    }

    private void vibrate(int ms) {
        try {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null) return;
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
            else v.vibrate(ms);
        } catch (Exception ignored) {}
    }
}
