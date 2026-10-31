package com.example.callhelper;

import android.content.Context;
import android.util.Log;
import java.io.File;
import java.io.FileInputStream;
import java.io.ObjectInputStream;
import java.util.ArrayList;
import java.util.List;

public final class TemplateStore {
    private TemplateStore() {}

    public static float[][][] load(Context c) {
        List<float[][]> list = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            File f = new File(c.getFilesDir(), "template_" + i + ".mfcc");
            if (!f.exists()) continue;
            try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(f))) {
                Object o = ois.readObject();
                if (o instanceof float[][]) {
                    float[][] m = (float[][]) o;
                    if (m.length > 0) list.add(m);
                }
            } catch (Exception e) { Log.e("TemplateStore", "load " + i, e); }
        }
        return list.toArray(new float[0][][]);
    }
}
