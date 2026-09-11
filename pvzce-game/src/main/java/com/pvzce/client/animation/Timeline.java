package com.pvzce.client.animation;

/**
 * The one implementation of "where are we in this clip".
 *
 * <p>The loop and clamp maths existed three times with three different spellings:
 * {@code ControllerClip} used {@code time % duration}, {@code FlipbookClip} wrapped
 * in microsecond integers with a clamp to {@code total - 1e-6}, and
 * {@code AnimationPlayback} walked cycles with its own {@code floor(start/duration)
 * * duration}. Three copies of one rule is three chances to disagree about the
 * boundary at the end of a loop, so the rule lives here and every caller shares it.
 */
public final class Timeline {
    /** Clip time for sampling: wrapped into {@code [0, duration)} when looping. */
    public static double wrap(double time, float duration, boolean loop) {
        if (duration <= 0F) {
            return 0D;
        }
        if (!loop) {
            return Math.max(0D, Math.min(duration, time));
        }
        double wrapped = time % duration;
        return wrapped < 0D ? wrapped + duration : wrapped;
    }

    /**
     * Same as {@link #wrap} but pulled just inside the end of a non-looping clip, so
     * a sampler cannot land exactly on the boundary and read the frame after the
     * last one.
     */
    public static double wrapForSampling(double time, float duration, boolean loop) {
        if (duration <= 0F) {
            return 0D;
        }
        if (loop) {
            return wrap(time, duration, true);
        }
        return Math.max(0D, Math.min(duration - 1.0E-6D, time));
    }

    /** Index of the flipbook frame visible at {@code time}. */
    public static int frameAt(float[] delays, int frameCount, double time, boolean loop) {
        if (frameCount <= 0) {
            return -1;
        }
        float total = totalDuration(delays, frameCount);
        if (total <= 0F) {
            return 0;
        }
        double t = wrapForSampling(time, total, loop);
        float cursor = 0F;
        for (int i = 0; i < frameCount; i++) {
            cursor += delayAt(delays, i);
            if (t < cursor) {
                return i;
            }
        }
        return frameCount - 1;
    }

    /** Sum of the per-frame delays, with the default delay for missing entries. */
    public static float totalDuration(float[] delays, int frameCount) {
        float total = 0F;
        for (int i = 0; i < frameCount; i++) {
            total += delayAt(delays, i);
        }
        return total;
    }

    /** Start time of a frame within the clip. */
    public static float frameStart(float[] delays, int frame) {
        float cursor = 0F;
        for (int i = 0; i < frame; i++) {
            cursor += delayAt(delays, i);
        }
        return cursor;
    }

    /** Delay for frame {@code index}: one value repeats, otherwise clamp to the last. */
    public static float delayAt(float[] delays, int index) {
        if (delays == null || delays.length == 0) {
            return DEFAULT_FRAME_DELAY;
        }
        if (delays.length == 1) {
            return Math.max(MIN_FRAME_DELAY, delays[0]);
        }
        return Math.max(MIN_FRAME_DELAY, delays[Math.min(Math.max(0, index), delays.length - 1)]);
    }

    /** Delay used when a flipbook declares none. */
    public static final float DEFAULT_FRAME_DELAY = 0.1F;
    /** A frame may not be instantaneous, or the wrap maths divides by zero. */
    public static final float MIN_FRAME_DELAY = 0.0001F;

    private Timeline() {
    }
}
