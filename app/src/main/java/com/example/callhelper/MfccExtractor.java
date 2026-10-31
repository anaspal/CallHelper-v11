package com.example.callhelper;

import java.util.Arrays;

public class MfccExtractor {
    public static final int SAMPLE_RATE = 16000;
    public static final int FRAME_LEN = 400;
    public static final int FRAME_HOP = 160;
    public static final int FFT_SIZE  = 512;
    public static final int NUM_MELS  = 26;
    public static final int NUM_CEPS  = 13;
    /** أقل طاقة صوت (RMS) نعتبرها كلاماً. مشتركة بين الخدمة وشاشة الاختبار. */
    public static final float MIN_RMS = 900f;
    private static final float[] HAMMING = new float[FRAME_LEN];
    private static final float[][] MEL_BANK = new float[NUM_MELS][FFT_SIZE / 2 + 1];
    private static final float[][] DCT = new float[NUM_CEPS][NUM_MELS];

    static {
        for (int i = 0; i < FRAME_LEN; i++) HAMMING[i] = (float) (0.54 - 0.46 * Math.cos(2 * Math.PI * i / (FRAME_LEN - 1)));
        for (int c = 0; c < NUM_CEPS; c++)
            for (int m = 0; m < NUM_MELS; m++)
                DCT[c][m] = (float) Math.cos(Math.PI * c * (m + 0.5) / NUM_MELS);
        double melMin = hz2mel(50), melMax = hz2mel(8000);
        double[] pts = new double[NUM_MELS + 2];
        for (int i = 0; i < pts.length; i++) pts[i] = melMin + i * (melMax - melMin) / (NUM_MELS + 1);
        for (int m = 0; m < NUM_MELS; m++) {
            double a = pts[m], b = pts[m + 1], c = pts[m + 2];
            for (int k = 0; k < MEL_BANK[m].length; k++) {
                double freq = (double) k * SAMPLE_RATE / FFT_SIZE;
                double mel = hz2mel(freq);
                if (mel <= a || mel >= c) continue;
                double w = (mel <= b) ? (mel - a) / (b - a) : (c - mel) / (c - b);
                MEL_BANK[m][k] = (float) w;
            }
        }
    }

    private static double hz2mel(double hz) { return 2595.0 * Math.log10(1.0 + hz / 700.0); }

    /** يقصّ الصمت من بداية ونهاية التسجيل. يرجع null إذا لم يوجد كلام. */
    public static short[] trimSilence(short[] pcm) {
        if (pcm == null) return null;
        int hop = FRAME_HOP;
        int n = pcm.length / hop;
        if (n == 0) return null;
        double[] e = new double[n];
        double peak = 0;
        for (int f = 0; f < n; f++) {
            double s = 0;
            for (int i = 0; i < hop; i++) { double v = pcm[f * hop + i]; s += v * v; }
            e[f] = Math.sqrt(s / hop);
            if (e[f] > peak) peak = e[f];
        }
        double thr = Math.max(300.0, peak * 0.1);
        int first = -1, last = -1;
        for (int f = 0; f < n; f++) {
            if (e[f] >= thr) { if (first < 0) first = f; last = f; }
        }
        if (first < 0) return null;
        first = Math.max(0, first - 8);
        last = Math.min(n - 1, last + 8);
        short[] out = Arrays.copyOfRange(pcm, first * hop, (last + 1) * hop);
        return out.length >= FRAME_LEN ? out : null;
    }

    public static float[][] extract(short[] pcm) {
        if (pcm == null || pcm.length < FRAME_LEN) return new float[0][];
        float[] pre = new float[pcm.length];
        float prev = 0f;
        for (int i = 0; i < pcm.length; i++) {
            float cur = pcm[i] / 32768f;
            pre[i] = cur - 0.97f * prev;   // pre-emphasis الصحيح (كان: (cur - prev) * 0.97)
            prev = cur;
        }
        int numFrames = (pcm.length - FRAME_LEN) / FRAME_HOP + 1;
        float[][] mfcc = new float[numFrames][NUM_CEPS];
        float[] re = new float[FFT_SIZE];
        float[] im = new float[FFT_SIZE];
        for (int f = 0; f < numFrames; f++) {
            int start = f * FRAME_HOP;
            for (int i = 0; i < FFT_SIZE; i++) {
                re[i] = (i < FRAME_LEN) ? pre[start + i] * HAMMING[i] : 0f;
                im[i] = 0f;
            }
            fft(re, im);
            float[] logMel = new float[NUM_MELS];
            for (int m = 0; m < NUM_MELS; m++) {
                float sum = 0;
                for (int k = 0; k < MEL_BANK[m].length; k++) sum += MEL_BANK[m][k] * (re[k] * re[k] + im[k] * im[k]);
                logMel[m] = (float) Math.log(sum + 1e-10f);
            }
            mfcc[f][0] = 0f; // نهمل المعامل الصفري (طاقة الإطار)
            for (int c = 1; c < NUM_CEPS; c++) {
                float s = 0;
                float[] row = DCT[c];
                for (int m = 0; m < NUM_MELS; m++) s += logMel[m] * row[m];
                mfcc[f][c] = s;
            }
        }
        return mfcc;
    }

    private static void fft(float[] re, float[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                float t = re[i]; re[i] = re[j]; re[j] = t;
                t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2 * Math.PI / len;
            float wr = (float) Math.cos(ang), wi = (float) Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                float cr = 1, ci = 0;
                for (int k = 0; k < len / 2; k++) {
                    float ur = re[i + k], ui = im[i + k];
                    float vr = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci;
                    float vi = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr;
                    re[i + k] = ur + vr; im[i + k] = ui + vi;
                    re[i + k + len / 2] = ur - vr; im[i + k + len / 2] = ui - vi;
                    float ncr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = ncr;
                }
            }
        }
    }
}
