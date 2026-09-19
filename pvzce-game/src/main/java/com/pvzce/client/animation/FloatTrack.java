package com.pvzce.client.animation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Sorted scalar keyframes with per-keyframe easing.
 *
 * <p>The scalar sibling of {@link VectorTrack}, and the reason it exists rather than a
 * one-element vector: a controller part's alpha is a single number, and writing it as
 * {@code [a]} would make every JSON file carry an array for it. The sampling rule is
 * deliberately identical to {@link VectorTrack#sample} - including "the later keyframe
 * owns the segment's easing" - so a clip cannot behave differently depending on which
 * channel it is interpolating.
 */
public final class FloatTrack {
    private final List<Keyframe<Float>> keys;

    public FloatTrack(List<Keyframe<Float>> keys) {
        List<Keyframe<Float>> sorted = new ArrayList<>(keys);
        sorted.sort(Comparator.comparingDouble(Keyframe::time));
        this.keys = List.copyOf(sorted);
    }

    public boolean isEmpty() {
        return keys.isEmpty();
    }

    public float maxTime() {
        return keys.isEmpty() ? 0F : keys.get(keys.size() - 1).time();
    }

    public float sample(double time, float fallback) {
        if (keys.isEmpty()) {
            return fallback;
        }
        if (time <= keys.get(0).time()) {
            return keys.get(0).value();
        }
        int last = keys.size() - 1;
        if (time >= keys.get(last).time()) {
            return keys.get(last).value();
        }
        for (int i = 0; i < last; i++) {
            Keyframe<Float> a = keys.get(i);
            Keyframe<Float> b = keys.get(i + 1);
            if (time < b.time()) {
                float span = Math.max(0.000001F, b.time() - a.time());
                float t = (float) ((time - a.time()) / span);
                t = b.easing().apply(Math.max(0F, Math.min(1F, t)));
                return com.pvzce.common.util.MathUtil.lerp(a.value(), b.value(), t);
            }
        }
        return keys.get(last).value();
    }
}
