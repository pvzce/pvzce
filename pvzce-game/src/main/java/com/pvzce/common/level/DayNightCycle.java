package com.pvzce.common.level;

/**
 * The day/night cycle as pure functions.
 *
 * <p>The server needs a hard yes/no ("is it night right now?" for grave spawning)
 * and the client needs a smooth 0..1 factor to crossfade its lighting, but both
 * must agree on where day ends and night begins. Keeping both derivations in one
 * class - instead of one in {@code PvzceClock} and a divergent copy in
 * {@code ClientLevel} - is what makes those two answers consistent.
 */
public final class DayNightCycle {
    /** Dusk/dawn crossfade is capped to this many ticks. */
    public static final int MAX_FADE_WINDOW = 120;
    /** Shortest possible crossfade, so a 2-tick day still fades rather than snapping. */
    public static final int MIN_FADE_WINDOW = 1;

    /** The cycle length; 0 when the level is permanently day. */
    public static long cycleLength(int dayLength, int nightLength) {
        if (dayLength <= 0 || nightLength <= 0) {
            return 0L;
        }
        return (long) dayLength + nightLength;
    }

    /** Tick within the current cycle, matching {@code dayTicks % cycle}. */
    public static long cyclePosition(long dayTicks, int dayLength, int nightLength) {
        long cycle = cycleLength(dayLength, nightLength);
        return cycle <= 0 ? 0L : Math.floorMod(dayTicks, cycle);
    }

    /**
     * Whether the level is at night for its whole run.
     *
     * <p>Written as "there is no day, and there is a night": {@code day_length: 0} with a
     * positive {@code night_length}. The night's own length is then only a number the file has
     * to give - with no day to come back to, the cycle never leaves the night - which is what
     * the original's Night area is. The other three shapes stay as they were:
     * {@code night_length < 0} is "never night" (every Day level), both positive is a cycle,
     * and neither is a level that simply never gets dark.
     */
    public static boolean alwaysNight(int dayLength, int nightLength) {
        return dayLength <= 0 && nightLength > 0;
    }

    /**
     * Hard day/night answer. A level is night when it has no day at all
     * ({@link #alwaysNight}), or when it has both a positive day and a positive night length
     * and the clock is past the day; {@code nightLength < 0} means "never night".
     */
    public static boolean isNight(long dayTicks, int dayLength, int nightLength) {
        if (alwaysNight(dayLength, nightLength)) {
            return true;
        }
        if (dayLength <= 0 || nightLength <= 0) {
            return false;
        }
        return cyclePosition(dayTicks, dayLength, nightLength) >= dayLength;
    }

    /**
     * Smooth 0..1 night factor: 0 in full day, 1 in full night, crossfading across
     * a window of {@link #fadeWindow} ticks on either side of the boundary.
     */
    public static float nightBlend(long dayTicks, int dayLength, int nightLength) {
        if (alwaysNight(dayLength, nightLength)) {
            // No dusk to cross: a level with no day opens dark, on its first frame.
            return 1F;
        }
        long cycle = cycleLength(dayLength, nightLength);
        if (cycle <= 0) {
            return 0F;
        }
        long pos = cyclePosition(dayTicks, dayLength, nightLength);
        int window = fadeWindow(dayLength, nightLength);
        float dusk = pos <= dayLength - window
                ? 0F
                : (pos < dayLength + window ? smoothstep(dayLength - window, dayLength + window, pos) : 1F);
        float dawnFade = pos >= cycle - window ? 1F - smoothstep(cycle - window, cycle, pos) : 1F;
        return Math.max(0F, Math.min(1F, dusk * dawnFade));
    }

    public static int fadeWindow(int dayLength, int nightLength) {
        int shortest = Math.min(dayLength, nightLength);
        return Math.max(MIN_FADE_WINDOW, Math.min(MAX_FADE_WINDOW, shortest / 4));
    }

    /** Number of completed day/night cycles (used by {@code /time query day}). */
    public static long dayCount(long dayTicks, int dayLength, int nightLength) {
        long cycle = cycleLength(dayLength, nightLength);
        return cycle <= 0 ? 0L : dayTicks / cycle;
    }

    /** Hermite smoothstep; the single implementation shared by every fade. */
    public static float smoothstep(float edge0, float edge1, float value) {
        float t = Math.max(0F, Math.min(1F, (value - edge0) / Math.max(0.001F, edge1 - edge0)));
        return t * t * (3F - 2F * t);
    }

    private DayNightCycle() {
    }
}
