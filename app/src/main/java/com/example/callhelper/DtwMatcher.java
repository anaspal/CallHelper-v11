package com.example.callhelper;

import java.util.Arrays;

/**
 * مطابقة العبارة المسجّلة (template) مع مقطع حي (test).
 * المرحلة 1: subsequence DTW لإيجاد أفضل موضع للعبارة داخل المقطع (مع تتبّع بداية المسار).
 * شرط: طول الموضع الذي وجدته بين 0.5× و2× طول القالب (وإلا لا مطابقة).
 * المرحلة 2: نقارن القالب مع الموضع فقط بعد تطبيع المتوسط (CMN) على الاثنين.
 */
public class DtwMatcher {
    private static final float INF = Float.MAX_VALUE / 4;
    public static final float NO_MATCH = Float.MAX_VALUE;
    private static final float MIN_RATIO = 0.5f;
    private static final float MAX_RATIO = 2.0f;

    /** حساسية 0..100 (0 = صارم، 100 = متساهل) ← معامل يُضرب في الأساس. */
    public static float factorFromSens(int sens) {
        int s = Math.max(0, Math.min(100, sens));
        return 0.9f + (s / 100f) * 1.1f;
    }

    private static float frameDist(float[] a, float[] b) {
        float s = 0;
        for (int c = 0; c < a.length; c++) { float d = a[c] - b[c]; s += d * d; }
        return (float) Math.sqrt(s);
    }

    private static float[][] cmn(float[][] a) {
        if (a.length == 0) return a;
        int dim = a[0].length;
        float[] mean = new float[dim];
        for (float[] f : a) for (int c = 0; c < dim; c++) mean[c] += f[c];
        for (int c = 0; c < dim; c++) mean[c] /= a.length;
        float[][] out = new float[a.length][dim];
        for (int i = 0; i < a.length; i++) for (int c = 0; c < dim; c++) out[i][c] = a[i][c] - mean[c];
        return out;
    }

    private static final class Match { int start; int end; } // 1-based, شاملة

    private static Match subsequence(float[][] t, float[][] x) {
        int n = t.length, m = x.length;
        float[] prev = new float[m + 1], cur = new float[m + 1];
        int[] prevS = new int[m + 1], curS = new int[m + 1];
        Arrays.fill(prev, 0f); // الصف 0: بداية حرة
        for (int i = 1; i <= n; i++) {
            cur[0] = INF; curS[0] = 0;
            for (int j = 1; j <= m; j++) {
                float cost = frameDist(t[i - 1], x[j - 1]);
                float best; int bs;
                if (i == 1) {
                    best = prev[j - 1]; bs = j;
                    if (cur[j - 1] < best) { best = cur[j - 1]; bs = curS[j - 1]; }
                } else {
                    best = prev[j - 1]; bs = prevS[j - 1];
                    if (prev[j] < best) { best = prev[j]; bs = prevS[j]; }
                    if (cur[j - 1] < best) { best = cur[j - 1]; bs = curS[j - 1]; }
                }
                cur[j] = cost + best;
                curS[j] = bs;
            }
            float[] tf = prev; prev = cur; cur = tf;
            int[] ti = prevS; prevS = curS; curS = ti;
        }
        float bestCost = INF; int bestJ = -1;
        for (int j = 1; j <= m; j++) if (prev[j] < bestCost) { bestCost = prev[j]; bestJ = j; }
        if (bestJ < 0 || prevS[bestJ] <= 0) return null;
        Match mt = new Match();
        mt.start = prevS[bestJ];
        mt.end = bestJ;
        return mt;
    }

    private static float fullDtw(float[][] a, float[][] b) {
        int n = a.length, m = b.length;
        float[] prev = new float[m + 1], cur = new float[m + 1];
        Arrays.fill(prev, INF);
        prev[0] = 0;
        for (int i = 1; i <= n; i++) {
            cur[0] = INF;
            for (int j = 1; j <= m; j++) {
                float cost = frameDist(a[i - 1], b[j - 1]);
                cur[j] = cost + Math.min(prev[j], Math.min(cur[j - 1], prev[j - 1]));
            }
            float[] tf = prev; prev = cur; cur = tf;
        }
        return prev[m] / (n + m);
    }

    /** المسافة بين القالب وأفضل موضع له داخل المقطع. أصغر = أشبه. NO_MATCH = لا شبه. */
    public static float score(float[][] template, float[][] test) {
        if (template == null || test == null || template.length == 0 || test.length == 0) return NO_MATCH;
        if (template[0].length != test[0].length) return NO_MATCH;
        Match mt = subsequence(template, test);
        if (mt == null) return NO_MATCH;
        int len = mt.end - mt.start + 1;
        float ratio = (float) len / template.length;
        if (ratio < MIN_RATIO || ratio > MAX_RATIO) return NO_MATCH;
        float[][] region = Arrays.copyOfRange(test, mt.start - 1, mt.end);
        return fullDtw(cmn(template), cmn(region));
    }

    public static float minScore(float[][] test, float[][][] templates) {
        float best = NO_MATCH;
        if (templates == null) return best;
        for (float[][] t : templates) {
            float s = score(t, test);
            if (s < best) best = s;
        }
        return best;
    }

    /** أسوأ تكرار لعباراتك المسجّلة (الأكبر بين الأزواج). 0 = لا يمكن الحساب. */
    public static float baseScore(float[][][] templates) {
        if (templates == null || templates.length < 2) return 0f;
        float worst = 0f; boolean any = false;
        for (int i = 0; i < templates.length; i++) {
            for (int j = i + 1; j < templates.length; j++) {
                float s = Math.min(score(templates[i], templates[j]), score(templates[j], templates[i]));
                if (s >= NO_MATCH / 2) continue;
                any = true;
                if (s > worst) worst = s;
            }
        }
        return any ? worst : 0f;
    }
}
