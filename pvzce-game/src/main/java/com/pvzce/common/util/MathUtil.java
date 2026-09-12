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
    /**
     * Smooth start and end, for transitions that both begin and stop at rest.
     *
     * <p>Lived as a private copy in {@code ChooseSeedsScreen} until the reward drop
     * needed the same curve; easings belong here (see the conventions section of
     * {@code docs/当前项目架构.md}).
     */
    public static float easeInOut(float value) {
        float t = clamp01(value);
        return t < 0.5F
                ? 2F * t * t
                : 1F - (float) Math.pow(-2F * t + 2F, 2F) / 2F;
    }

    public static float easeOutCubic(float t) {
        float inv = 1F - clamp01(t);
        return 1F - inv * inv * inv;
    }

    private MathUtil() {
    }
}
