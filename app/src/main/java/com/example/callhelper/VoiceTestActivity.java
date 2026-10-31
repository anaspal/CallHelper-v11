package com.example.callhelper;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import java.util.Locale;

public class VoiceTestActivity extends Activity {
    private static final String TAG = "VoiceTest";

    private TextView stateTv, scoreTv, thrTv, bestTv;
    private TextView keyTv, testTv;
    private Button testBtn;
    private TextView notifTv;
    private int testStep = 0;          // 0 = لا اختبار، 1 = ننتظر رفع الصوت، 2 = ننتظر خفض الصوت
    private long stepStart = 0;
    private int baseCount = 0;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            boolean conn = InCallAccessibilityService.isConnected();
            long t = InCallAccessibilityService.lastKeyTime();
            String txt = "خدمة الأزرار: " + (conn ? "✅ متصلة" : "❌ غير متصلة") + "\n";
            if (t == 0) {
                txt += "لم يصل أي زر بعد.";
            } else {
                long sec = (System.currentTimeMillis() - t) / 1000;
                txt += "آخر زر وصل: " + InCallAccessibilityService.lastKeyInfo() + " (قبل " + sec + " ث)"
                        + (CallController.lastAnswerInfo().isEmpty() ? "" : "\nآخر محاولة رد: " + CallController.lastAnswerInfo());
            }
            keyTv.setText(txt);
            if (notifTv != null) notifTv.setText(CallNotificationListener.isConnected()
                    ? "✅ مفعّل ويشتغل" : "❌ غير مفعّل: فعّل CallHelper من «الوصول إلى الإشعارات»");
            tickKeyTest();
            uiHandler.postDelayed(this, 200);
        }
    };
    private Button toggle;
    private volatile boolean listening = false;
    private float[][][] templates = new float[0][][];
    private float base = 0f;
    private volatile float factor = 1.2f;
    private volatile float best = Float.MAX_VALUE;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.init(this);
        Ui.systemBars(this);
        templates = TemplateStore.load(this);
        base = DtwMatcher.baseScore(templates);
        int sens = getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE).getInt(MainActivity.PREF_SENS, 30);
        factor = DtwMatcher.factorFromSens(sens);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Ui.BG);
        sv.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        int p = Ui.dp(this, 18);
        root.setPadding(p, Ui.dp(this, 24), p, p);

        root.addView(Ui.text(this, "اختبار البصمة", 28, Ui.TEXT, true));
        root.addView(Ui.text(this, "قل عبارتك وشوف النتيجة مباشرة، وغيّر الحساسية حسب ما يناسبك", 14, Ui.MUTED, false),
                Ui.lp(this, Ui.WRAP, Ui.WRAP, 2, 18));

        LinearLayout live = Ui.card(this);
        stateTv = Ui.text(this, "اضغط «ابدأ الاختبار»", 22, Ui.TEXT, true);
        stateTv.setGravity(Gravity.CENTER);
        live.addView(stateTv, Ui.lp(this, Ui.MATCH, Ui.WRAP, 6, 8));
        scoreTv = Ui.text(this, "", 16, Ui.TEXT, false);
        scoreTv.setGravity(Gravity.CENTER);
        live.addView(scoreTv, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 4));
        thrTv = Ui.text(this, "", 14, Ui.MUTED, false);
        thrTv.setGravity(Gravity.CENTER);
        live.addView(thrTv, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 4));
        bestTv = Ui.text(this, "", 14, Ui.MUTED, false);
        bestTv.setGravity(Gravity.CENTER);
        live.addView(bestTv, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 12));
        toggle = Ui.button(this, "ابدأ الاختبار", Ui.PRIMARY, android.graphics.Color.WHITE);
        toggle.setOnClickListener(v -> { if (listening) stopListening(); else startListening(); });
        live.addView(toggle, Ui.lp(this, Ui.MATCH, Ui.dp(this, 54), 0, 0));
        root.addView(live);

        LinearLayout keyCard = Ui.card(this);
        keyCard.addView(Ui.text(this, "اختبار أزرار الصوت", 18, Ui.TEXT, true));
        testTv = Ui.text(this, "اضغط «ابدأ اختبار الأزرار» ثم اتبع التعليمات", 16, Ui.TEXT, true);
        testTv.setGravity(Gravity.CENTER);
        testTv.setLineSpacing(Ui.dp(this, 4), 1f);
        keyCard.addView(testTv, Ui.lp(this, Ui.MATCH, Ui.WRAP, 12, 12));
        testBtn = Ui.button(this, "ابدأ اختبار الأزرار", Ui.PRIMARY, android.graphics.Color.WHITE);
        testBtn.setOnClickListener(v -> startKeyTest());
        keyCard.addView(testBtn, Ui.lp(this, Ui.MATCH, Ui.dp(this, 52), 0, 10));
        keyTv = Ui.text(this, "", 14, Ui.MUTED, false);
        keyTv.setLineSpacing(Ui.dp(this, 4), 1f);
        keyCard.addView(keyTv, Ui.lp(this, Ui.WRAP, Ui.WRAP, 8, 0));
        TextView note = Ui.text(this, "للاختبار الكامل: اتصل على جوالك من رقم آخر واضغط رفع الصوت أثناء الرنين، فيرد على المكالمة.", 13, Ui.MUTED, false);
        keyCard.addView(note, Ui.lp(this, Ui.WRAP, Ui.WRAP, 10, 0));
        root.addView(keyCard);

        LinearLayout notifCard = Ui.card(this);
        notifCard.addView(Ui.text(this, "الرد عبر إشعار المكالمة (طريقة إضافية)", 18, Ui.TEXT, true));
        notifTv = Ui.text(this, "", 14, Ui.MUTED, false);
        notifCard.addView(notifTv, Ui.lp(this, Ui.WRAP, Ui.WRAP, 8, 0));
        Button notifBtn = Ui.button(this, "تفعيل الوصول للإشعارات", Ui.PRIMARY, android.graphics.Color.WHITE);
        notifBtn.setOnClickListener(v -> {
            try { startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)); }
            catch (Exception e) { Toast.makeText(this, "تعذّر فتح الإعدادات", Toast.LENGTH_LONG).show(); }
        });
        notifCard.addView(notifBtn, Ui.lp(this, Ui.MATCH, Ui.dp(this, 52), 0, 10));
        root.addView(notifCard);

        LinearLayout gestCard = Ui.card(this);
        gestCard.addView(Ui.text(this, "الرد بدون زر الصوت", 18, Ui.TEXT, true));
        gestCard.addView(Ui.text(this, "زر الصوت وقت الرنين يمسكه النظام ولا يوصل للتطبيق. هاي بدائل تشتغل وقت الرنين (تحتاج الخدمة شغّالة من الدائرة).", 13, Ui.MUTED, false), Ui.lp(this, Ui.WRAP, Ui.WRAP, 6, 6));
        android.content.SharedPreferences gsp = getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
        android.widget.Switch waveSw = new android.widget.Switch(this);
        waveSw.setText("لوّح بيدك فوق حساس القرب (أعلى الشاشة)");
        waveSw.setTextSize(15);
        waveSw.setTextColor(Ui.TEXT);
        waveSw.setChecked(gsp.getBoolean(MainActivity.PREF_WAVE, true));
        waveSw.setOnCheckedChangeListener((btn, on) -> gsp.edit().putBoolean(MainActivity.PREF_WAVE, on).apply());
        gestCard.addView(waveSw, Ui.lp(this, Ui.MATCH, Ui.WRAP, 8, 8));
        android.widget.Switch shakeSw = new android.widget.Switch(this);
        shakeSw.setText("هزّ الجوال 3 هزّات");
        shakeSw.setTextSize(15);
        shakeSw.setTextColor(Ui.TEXT);
        shakeSw.setChecked(gsp.getBoolean(MainActivity.PREF_SHAKE, false));
        shakeSw.setOnCheckedChangeListener((btn, on) -> gsp.edit().putBoolean(MainActivity.PREF_SHAKE, on).apply());
        gestCard.addView(shakeSw, Ui.lp(this, Ui.MATCH, Ui.WRAP, 8, 8));
        root.addView(gestCard);

        LinearLayout sensCard = Ui.card(this);
        sensCard.addView(Ui.text(this, "الحساسية", 18, Ui.TEXT, true));
        SeekBar seek = new SeekBar(this);
        seek.setMax(100);
        seek.setProgress(sens);
        sensCard.addView(seek, Ui.lp(this, Ui.MATCH, Ui.WRAP, 10, 2));
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.HORIZONTAL);
        labels.addView(Ui.text(this, "صارم", 13, Ui.MUTED, false), new LinearLayout.LayoutParams(0, Ui.WRAP, 1f));
        labels.addView(Ui.text(this, "متساهل", 13, Ui.MUTED, false));
        sensCard.addView(labels);
        root.addView(sensCard);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int prog, boolean fromUser) {
                factor = DtwMatcher.factorFromSens(prog);
                updateThreshold();
                if (fromUser) {
                    getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE).edit()
                            .putInt(MainActivity.PREF_SENS, prog).apply();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });

        LinearLayout hint = Ui.card(this);
        hint.addView(Ui.text(this, "كيف تضبطها؟", 18, Ui.TEXT, true));
        TextView ht = Ui.text(this,
                "• قل عبارتك: لازم تصير الحالة ✅ مطابق\n"
                + "• قل كلاماً آخر أو اصنع ضجيجاً: لازم تبقى ❌\n"
                + "• العبارة ما تنجح؟ حرّك الحساسية نحو «متساهل»\n"
                + "• تنجح مع أي كلام؟ حرّكها نحو «صارم»\n"
                + "• الرقم الأصغر يعني تشابه أكبر",
                14, Ui.MUTED, false);
        ht.setLineSpacing(Ui.dp(this, 4), 1f);
        hint.addView(ht, Ui.lp(this, Ui.WRAP, Ui.WRAP, 8, 0));
        root.addView(hint);

        sv.addView(root);
        setContentView(sv);
        updateThreshold();
    }

    @Override protected void onResume() {
        super.onResume();
        uiHandler.post(poll);
    }

    @Override protected void onPause() {
        super.onPause();
        listening = false;
        uiHandler.removeCallbacks(poll);
    }

    private void startKeyTest() {
        if (!InCallAccessibilityService.isConnected()) {
            testTv.setTextColor(Ui.RED);
            testTv.setText("❌ خدمة الأزرار غير متصلة\nفعّل CallHelper من إعدادات Accessibility أولاً");
            return;
        }
        testStep = 1;
        beginStep("👆 اضغط زر رفع الصوت الآن");
    }

    private void beginStep(String message) {
        stepStart = System.currentTimeMillis();
        baseCount = InCallAccessibilityService.keyCount();
        testTv.setTextColor(Ui.TEXT);
        testTv.setText(message);
        testBtn.setEnabled(false);
        testBtn.setAlpha(0.5f);
    }

    private void finishKeyTest(boolean ok, String message) {
        testStep = 0;
        testTv.setTextColor(ok ? Ui.GREEN : Ui.RED);
        testTv.setText(message);
        testBtn.setEnabled(true);
        testBtn.setAlpha(1f);
        testBtn.setText("أعد اختبار الأزرار");
    }

    private void tickKeyTest() {
        if (testStep == 0) return;
        int expected = (testStep == 1) ? KeyEvent.KEYCODE_VOLUME_UP : KeyEvent.KEYCODE_VOLUME_DOWN;
        int now = InCallAccessibilityService.keyCount();
        if (now != baseCount) {
            baseCount = now;
            if (InCallAccessibilityService.lastKeyCode() == expected) {
                if (testStep == 1) {
                    testStep = 2;
                    beginStep("✅ رفع الصوت وصل، تمام!\n👇 الآن اضغط زر خفض الصوت");
                } else {
                    finishKeyTest(true, "✅✅ الزرّان يشتغلان، تمام!");
                }
                return;
            }
            testTv.setTextColor(Ui.TEXT);
            testTv.setText(testStep == 1
                    ? "وصل زر ثاني.\n👆 اضغط زر رفع الصوت"
                    : "وصل زر ثاني.\n👇 اضغط زر خفض الصوت");
        }
        if (System.currentTimeMillis() - stepStart > 12000) {
            finishKeyTest(false, "❌ ما وصل أي زر خلال 12 ثانية\nأطفئ CallHelper من إعدادات Accessibility وشغّله من جديد، وتأكد أن الشاشة شغّالة");
        }
    }

    private void updateThreshold() {
        if (templates.length < 2) {
            thrTv.setText("سجّل عينتين على الأقل أولاً");
        } else if (base <= 0f) {
            thrTv.setText("تسجيلاتك غير متقاربة، أعد التسجيل بنفس النبرة والسرعة");
        } else {
            thrTv.setText(String.format(Locale.US, "الحد المسموح: %.1f", base * factor));
        }
    }

    private void startListening() {
        if (templates.length < 2) {
            Toast.makeText(this, "سجّل عينتين على الأقل أولاً", Toast.LENGTH_LONG).show();
            return;
        }
        if (base <= 0f) {
            Toast.makeText(this, "تسجيلاتك غير متقاربة، أعد التسجيل", Toast.LENGTH_LONG).show();
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
            return;
        }
        listening = true;
        best = Float.MAX_VALUE;
        toggle.setText("إيقاف الاختبار");
        stateTv.setText("🎤 تكلّم...");
        new Thread(this::loop, "voice-test").start();
    }

    private void stopListening() {
        listening = false;
        toggle.setText("ابدأ الاختبار");
    }

    private void loop() {
        final int sr = MfccExtractor.SAMPLE_RATE;
        AudioRecord ar = null;
        try {
            int min = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            ar = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, sr, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(min, sr * 2 * 2));
            if (ar.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("init failed");
            ar.startRecording();
            short[] chunk = new short[sr / 4];
            short[] window = new short[sr * 2];
            int filled = 0;
            while (listening) {
                int r = ar.read(chunk, 0, chunk.length);
                if (r < 0) break;
                if (r == 0) continue;
                System.arraycopy(window, r, window, 0, window.length - r);
                System.arraycopy(chunk, 0, window, window.length - r, r);
                filled = Math.min(window.length, filled + r);
                if (filled < window.length) continue;
                if (rms(window) < MfccExtractor.MIN_RMS) {
                    runOnUiThread(() -> {
                        stateTv.setText("🔇 صمت... قل عبارتك");
                        stateTv.setTextColor(Ui.MUTED);
                        scoreTv.setText("");
                    });
                    continue;
                }
                float score = DtwMatcher.minScore(MfccExtractor.extract(window), templates);
                if (score < best) best = score;
                final float sc = score;
                final float thr = base * factor;
                final float bst = best;
                runOnUiThread(() -> showScore(sc, thr, bst));
            }
        } catch (Exception e) {
            Log.e(TAG, "loop", e);
            final String msg = String.valueOf(e.getMessage());
            runOnUiThread(() -> stateTv.setText("❌ " + msg));
        } finally {
            if (ar != null) {
                try { ar.stop(); } catch (Exception ignored) {}
                try { ar.release(); } catch (Exception ignored) {}
            }
            listening = false;
            runOnUiThread(() -> toggle.setText("ابدأ الاختبار"));
        }
    }

    private void showScore(float sc, float thr, float bst) {
        boolean noShape = sc >= DtwMatcher.NO_MATCH / 2;
        boolean match = !noShape && sc < thr;
        stateTv.setText(match ? "✅ مطابق" : "❌ غير مطابق");
        stateTv.setTextColor(match ? Ui.GREEN : Ui.TEXT);
        scoreTv.setText(noShape ? "المسافة الحالية: لا تشابه" : String.format(Locale.US, "المسافة الحالية: %.1f", sc));
        thrTv.setText(String.format(Locale.US, "الحد المسموح: %.1f", thr));
        bestTv.setText(bst >= DtwMatcher.NO_MATCH / 2 ? "" : String.format(Locale.US, "أقل مسافة وصلت: %.1f", bst));
    }

    private float rms(short[] s) {
        double sum = 0;
        for (short v : s) sum += (double) v * v;
        return (float) Math.sqrt(sum / s.length);
    }
}
