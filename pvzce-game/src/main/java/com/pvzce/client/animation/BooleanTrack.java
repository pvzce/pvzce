package com.pvzce.client.animation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Sorted boolean keyframes; the value steps at each keyframe time. */
public final class BooleanTrack {
    private final List<Keyframe<Boolean>> keys;

    public BooleanTrack(List<Keyframe<Boolean>> keys) {
        List<Keyframe<Boolean>> sorted = new ArrayList<>(keys);
        sorted.sort(Comparator.comparingDouble(Keyframe::time));
        this.keys = List.copyOf(sorted);
    }

    public boolean isEmpty() {
        return keys.isEmpty();
    }

    public float maxTime() {
        return keys.isEmpty() ? 0F : keys.get(keys.size() - 1).time();
    }

    public boolean sample(double time, boolean fallback) {
        if (keys.isEmpty()) {
            return fallback;
        }
        boolean value = keys.get(0).value();
        for (Keyframe<Boolean> key : keys) {
            if (time < key.time()) {
                break;
            }
            value = key.value();
        }
        return value;
    }
}
