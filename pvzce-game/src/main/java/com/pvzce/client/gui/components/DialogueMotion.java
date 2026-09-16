package com.pvzce.client.gui.components;

/**
 * The dialogue's staging maths, with no drawing and no wall clock of its own.
 *
 * <p>Three things move a portrait, and all three are pure functions of "how far into this
 * beat of wall time are we": the slide that brings it on and takes it off
 * ({@link #slideOffsetX}/{@link #slideOffsetY}), the one-shot shake a line can ask for
 * ({@link #shakeOffset}) and the size it settles at while a line is spoken
 * ({@link #scaleAt}). They live apart from {@code DialogueOverlay} for the same reason the
 * typewriter's timing lives in one place: the overlay owns the textures and the input, and
 * this owns "where is it right now", which is the half a test can pin.
 *
 * <p>Every duration is in nanos because that is the clock the client's UI runs on (world
 * animation runs on game ticks; a conversation is not the world).
 */
final class DialogueMotion {
    /** How long the portrait takes to slide on or off. */
    static final long SLIDE_NANOS = 350_000_000L;
    /** How long a line's shake lasts, start to stop. */
    static final long SHAKE_NANOS = 350_000_000L;
    /** How long the portrait takes to grow or shrink to a line's own size. */
    static final long SCALE_NANOS = 250_000_000L;
    /** A shake's peak offset, as a fraction of the window width, before {@code amount}. */
    static final float SHAKE_AMPLITUDE_RATIO = 0.012F;
    /** Back-and-forth cycles in one shake; one and a half is a shudder, not a vibration. */
    private static final float SHAKE_CYCLES = 1.5F;

    private DialogueMotion() {
    }

    /** Linear 0..1 progress of a timer that started at {@code startNanos}, clamped. */
    static float progress(long nowNanos, long startNanos, long durationNanos) {
        if (durationNanos <= 0L) {
            return 1F;
        }
        return Math.max(0F, Math.min(1F, (nowNanos - startNanos) / (float) durationNanos));
    }

    /**
     * Eased 0..1 progress of a slide.
     *
     * <p>Eased rather than linear: a portrait that starts and stops abruptly reads as a
     * skipped cutscene, while an ease-in-out reads as someone stepping in.
     */
    static float slideProgress(long nowNanos, long startNanos) {
        return ease(progress(nowNanos, startNanos, SLIDE_NANOS));
    }

    /** The same ease, for a size change. */
    static float scaleProgress(long nowNanos, long startNanos) {
        return ease(progress(nowNanos, startNanos, SCALE_NANOS));
    }

    /** Smoothstep; the project's one ease, shared with {@code DayNightCycle}. */
    static float ease(float t) {
        return com.pvzce.common.level.DayNightCycle.smoothstep(0F, 1F, t);
    }

    /**
     * How far the portrait still is from its place, horizontally, while it slides.
     *
     * <p>A left speaker comes in from the left edge and a right one from the right - the
     * nearest one, so the character walks in from the side they are standing on. The travel
     * is a whole window, which clears any portrait the layout can produce; only the
     * {@code x} axis moves here, and {@link #slideOffsetY} stays zero.
     *
     * @param progress 0 at the start of the slide, 1 when the portrait is in place
     * @param entering true while coming on screen, false while going off
     */
    static float slideOffsetX(float progress, boolean centered, boolean left, boolean entering,
                              float guiWidth) {
        if (centered) {
            return 0F;
        }
        float gone = entering ? 1F - progress : progress;
        return (left ? -1F : 1F) * gone * Math.max(1F, guiWidth);
    }

    /**
     * The vertical half of {@link #slideOffsetX}: a centred speaker has no side to come from,
     * so they rise from below and sink back down.
     *
     * <p>Negative is downwards here - GUI y grows upwards - which is the same edge either
     * way: off the bottom of the window.
     */
    static float slideOffsetY(float progress, boolean centered, boolean entering, float guiHeight) {
        if (!centered) {
            return 0F;
        }
        float gone = entering ? 1F - progress : progress;
        return -gone * Math.max(1F, guiHeight);
    }

    /**
     * A shaking line's horizontal offset, in GUI pixels; 0 once the shake is over.
     *
     * <p>A damped sine: the sine is the back-and-forth, the falling amplitude makes it settle
     * instead of stopping mid-swing, and it ends exactly at zero so the portrait does not jump
     * when the timer runs out.
     */
    static float shakeOffset(long nowNanos, long startNanos, float guiWidth, float amount) {
        float t = progress(nowNanos, startNanos, SHAKE_NANOS);
        if (t <= 0F || t >= 1F || amount <= 0F) {
            return 0F;
        }
        double angle = 2D * Math.PI * SHAKE_CYCLES * t;
        return (float) Math.sin(angle) * (1F - t) * SHAKE_AMPLITUDE_RATIO * amount * guiWidth;
    }

    /**
     * A line's portrait size: interpolated from the size the previous line left behind, or
     * the target at once when the line is not animating its size.
     */
    static float scaleAt(float from, float to, long nowNanos, long startNanos, boolean animate) {
        return animate ? from + (to - from) * scaleProgress(nowNanos, startNanos) : to;
    }
}
