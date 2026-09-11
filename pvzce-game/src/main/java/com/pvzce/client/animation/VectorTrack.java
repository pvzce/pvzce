package com.pvzce.client.animation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Sorted float-vector keyframes with per-keyframe easing. */
public final class VectorTrack {
    private final List<Keyframe<float[]>> keys;

    public VectorTrack(List<Keyframe<float[]>> keys) {
        List<Keyframe<float[]>> sorted = new ArrayList<>(keys);
        sorted.sort(Comparator.comparingDouble(Keyframe::time));
        this.keys = List.copyOf(sorted);
    }

    public boolean isEmpty() {
        return keys.isEmpty();
    }

    public float maxTime() {
        return keys.isEmpty() ? 0F : keys.get(keys.size() - 1).time();
    }

    public float[] sample(double time, float[] fallback) {
        if (keys.isEmpty()) {
            return fallback == null ? null : fallback.clone();
        }
        if (time <= keys.get(0).time()) {
            return keys.get(0).value().clone();
        }
        int last = keys.size() - 1;
        if (time >= keys.get(last).time()) {
            return keys.get(last).value().clone();
        }
        for (int i = 0; i < last; i++) {
            Keyframe<float[]> a = keys.get(i);
            Keyframe<float[]> b = keys.get(i + 1);
            if (time < b.time()) {
                float span = Math.max(0.000001F, b.time() - a.time());
                float t = (float) ((time - a.time()) / span);
                t = b.easing().apply(Math.max(0F, Math.min(1F, t)));
                return lerp(a.value(), b.value(), t);
            }
        }
        return keys.get(last).value().clone();
    }

    private static float[] lerp(float[] a, float[] b, float t) {
        int length = Math.max(a.length, b.length);
        float[] out = new float[length];
        for (int i = 0; i < length; i++) {
            float av = i < a.length ? a[i] : 0F;
            float bv = i < b.length ? b[i] : 0F;
            out[i] = av + (bv - av) * t;
        }
        return out;
    }
}
