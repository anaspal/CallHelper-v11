package com.example.callhelper;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.FileOutputStream;
import java.io.ObjectOutputStream;
import java.util.Arrays;

public class VoiceTrainerActivity extends Activity {
    private static final String TAG = "Trainer";
    private volatile int count = 0;
    private TextView status;
    private final TextView[] dots = new TextView[3];
    private Button recBtn;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.init(this);
        Ui.systemBars(this);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Ui.BG);
        sv.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        int p = Ui.dp(this, 18);
        root.setPadding(p, Ui.dp(this, 24), p, p);

        root.addView(Ui.text(this, "تسجيل صوتك", 28, Ui.TEXT, true));
        root.addView(Ui.text(this, "سجّل العبارة 3 مرات ليتعرّف التطبيق على صوتك", 14, Ui.MUTED, false),
                Ui.lp(this, Ui.WRAP, Ui.WRAP, 2, 18));

        LinearLayout tips = Ui.card(this);
        tips.addView(Ui.text(this, "نصائح", 18, Ui.TEXT, true));
        TextView tv = Ui.text(this,
                "• اختر عبارة قصيرة، مثال: «رد على المكالمة»\n"
                + "• كرّرها بنفس النبرة والسرعة كل مرة\n"
                + "• سجّل في مكان هادئ وقريباً من الجوال",
                14, Ui.MUTED, false);
        tv.setLineSpacing(Ui.dp(this, 4), 1f);
        tips.addView(tv, Ui.lp(this, Ui.WRAP, Ui.WRAP, 8, 0));
        root.addView(tips);

        LinearLayout rec = Ui.card(this);
        LinearLayout dotsRow = new LinearLayout(this);
        dotsRow.setOrientation(LinearLayout.HORIZONTAL);
        dotsRow.setGravity(Gravity.CENTER);
        for (int i = 0; i < 3; i++) {
            dots[i] = Ui.text(this, "●", 34, Ui.GRAY, false);
            dots[i].setPadding(Ui.dp(this, 8), 0, Ui.dp(this, 8), 0);
            dotsRow.addView(dots[i]);
        }
        rec.addView(dotsRow);

        status = Ui.text(this, "", 16, Ui.TEXT, true);
        status.setGravity(Gravity.CENTER);
        rec.addView(status, Ui.lp(this, Ui.MATCH, Ui.WRAP, 12, 14));

        recBtn = Ui.button(this, "", Ui.PRIMARY, Color.WHITE);
        recBtn.setOnClickListener(v -> record());
        rec.addView(recBtn, Ui.lp(this, Ui.MATCH, Ui.dp(this, 56), 0, 10));

        Button reset = Ui.button(this, "🗑️ حذف كل التسجيلات", Ui.CARD, Ui.RED);
        reset.setBackground(Ui.outline(this, Ui.CARD, Ui.RED, 14));
        reset.setOnClickListener(v -> resetAll());
        rec.addView(reset, Ui.lp(this, Ui.MATCH, Ui.dp(this, 48), 0, 0));
        root.addView(rec);

        sv.addView(root);
        setContentView(sv);
        refreshCount();

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
        }
    }

    private File templateFile(int i) { return new File(getFilesDir(), "template_" + i + ".mfcc"); }

    private void refreshCount() {
        int c = 0;
        for (int i = 1; i <= 3; i++) if (templateFile(i).exists()) c++;
        count = c;
        for (int i = 0; i < 3; i++) dots[i].setTextColor(i < c ? Ui.GREEN : Ui.GRAY);
        if (c >= 3) {
            status.setText("✅ تم تسجيل 3 عينات. ارجع وشغّل الخدمة");
            recBtn.setText("اكتمل التسجيل");
        } else {
            status.setText("سجّلت " + c + " من 3");
            recBtn.setText("🎙️ سجّل العينة " + (c + 1));
        }
        recBtn.setEnabled(c < 3);
        recBtn.setAlpha(c < 3 ? 1f : 0.5f);
    }

    private void resetAll() {
        for (int i = 1; i <= 3; i++) templateFile(i).delete();
        refreshCount();
        Toast.makeText(this, "تم الحذف", Toast.LENGTH_SHORT).show();
    }

    private void record() {
        if (count >= 3) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
            Toast.makeText(this, "امنح إذن الميكروفون ثم أعد المحاولة", Toast.LENGTH_SHORT).show();
            return;
        }
        recBtn.setEnabled(false);
        recBtn.setAlpha(0.5f);
        status.setText("🎤 تكلّم الآن... 3");
        new Thread(this::doRecord).start();
    }

    private void doRecord() {
        final int sr = MfccExtractor.SAMPLE_RATE;
        final short[] recorded = new short[sr * 3];
        int pos = 0;
        AudioRecord ar = null;
        try {
            int min = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            ar = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, sr, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(min, sr * 2 * 3));
            if (ar.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("init failed");
            ar.startRecording();
            short[] buf = new short[sr / 10];
            long end = System.currentTimeMillis() + 3000;
            int lastSec = 3;
            while (System.currentTimeMillis() < end && pos < recorded.length) {
                int r = ar.read(buf, 0, buf.length);
                if (r < 0) throw new IllegalStateException("read error " + r);
                if (r > 0) {
                    int n = Math.min(r, recorded.length - pos);
                    System.arraycopy(buf, 0, recorded, pos, n);
                    pos += n;
                }
                final int sec = (int) Math.ceil((end - System.currentTimeMillis()) / 1000.0);
                if (sec != lastSec && sec > 0) {
                    lastSec = sec;
                    runOnUiThread(() -> status.setText("🎤 تكلّم الآن... " + sec));
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "rec", e);
            final String msg = String.valueOf(e.getMessage());
            runOnUiThread(() -> { refreshCount(); status.setText("❌ فشل: " + msg); });
            return;
        } finally {
            if (ar != null) {
                try { ar.stop(); } catch (Exception ignored) {}
                try { ar.release(); } catch (Exception ignored) {}
            }
        }
        save(recorded, pos);
    }

    private void save(short[] pcm, int len) {
        short[] trimmed = MfccExtractor.trimSilence(Arrays.copyOf(pcm, len));
        if (trimmed == null) {
            runOnUiThread(() -> { refreshCount(); status.setText("❌ ما انسمع صوت واضح، حاول وتكلّم أقرب"); });
            return;
        }
        float[][] mfcc = MfccExtractor.extract(trimmed);
        if (mfcc.length == 0) {
            runOnUiThread(() -> { refreshCount(); status.setText("❌ قصير جداً"); });
            return;
        }
        int idx = 1;
        while (idx <= 3 && templateFile(idx).exists()) idx++;
        if (idx > 3) { runOnUiThread(this::refreshCount); return; }
        File f = templateFile(idx);
        try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(f))) {
            oos.writeObject(mfcc);
        } catch (Exception e) {
            Log.e(TAG, "save", e);
            f.delete();
            runOnUiThread(() -> { refreshCount(); status.setText("❌ فشل الحفظ"); });
            return;
        }
        runOnUiThread(() -> {
            refreshCount();
            Toast.makeText(this, "تم حفظ العينة", Toast.LENGTH_SHORT).show();
        });
    }
}
