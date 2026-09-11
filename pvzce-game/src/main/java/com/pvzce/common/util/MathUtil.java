package com.pvzce.common.util;

/**
 * Small numeric helpers shared by the simulation and the client.
 *
 * <p>These used to be private copies in several classes ({@code ceilDiv} existed
 * three times, {@code lerp} five times, {@code clamp01} four times), which is how
 * two different "ease out" curves ended up in the UI.
 */
public final class MathUtil {
    public static int ceilDiv(int value, int divisor) {
        return Math.floorDiv(value + divisor - 1, divisor);
    }

    public static float lerp(float from, float to, float t) {
        return from + (to - from) * t;
    }

    public static float clamp01(float value) {
        return Math.max(0F, Math.min(1F, value));
    }

    public static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    public static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /** Cubic ease-out, the curve the UI tweens use. */
    public static float easeOutCubic(float t) {
        float inv = 1F - clamp01(t);
        return 1F - inv * inv * inv;
    }

    private MathUtil() {
    }
}
