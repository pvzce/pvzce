package com.pvzce.server;

import com.pvzce.common.PvzceConstants;

/**
 * MC {@code ServerTickRateManager}-shaped tick controller.
 *
 * <p>Default target is 60tps. {@code /tick} can change the rate, freeze the
 * server, single-step a frozen server, or sprint a requested number of
 * ticks.</p>
 */
public final class PvzceTickRateManager {
    public static final float DEFAULT_TICK_RATE = PvzceConstants.TICKS_PER_SECOND;
    public static final float MIN_TICK_RATE = 1F;
    public static final float MAX_TICK_RATE = 10_000F;
    public static final int TICK_TIME_SAMPLES = 120;

    private long accumulator;
    private long tickCount;
    private long nanosPerTick = PvzceConstants.NANOS_PER_TICK;
    private float tickRate = DEFAULT_TICK_RATE;
    private boolean frozen;
    private boolean stepping;
    private int stepTicks;
    private int sprintTicks;
    private final long[] tickTimesNanos = new long[TICK_TIME_SAMPLES];
    private int tickTimeIndex;
    private int tickTimeCount;
    private long tickTimeSum;

    /**
     * Returns true when one game tick should run for the supplied frame
     * budget. Frozen servers only tick while a step is pending; sprinting
     * servers tick once per call and ignore the accumulator.
     */
    public boolean advance(long frameNanos) {
        if (stepping) {
            if (stepTicks > 0) {
                stepTicks--;
                return true;
            }
            stepping = false;
            frozen = true;
            return false;
        }
        if (frozen) {
            return false;
        }
        if (sprintTicks > 0) {
            sprintTicks--;
            return true;
        }
        accumulator += frameNanos;
        if (accumulator >= nanosPerTick) {
            accumulator -= nanosPerTick;
            return true;
        }
        return false;
    }

    /** Discards pending frame time after the server fell too far behind. */
    public void reset() {
        accumulator = 0;
    }

    /**
     * {@code /tick reset}: restore the default rate, unfreeze, and stop any
     * sprint/step that is in progress. Level time and entity state are not
     * touched.
     */
    public void resetTickRate() {
        sprintTicks = 0;
        stepping = false;
        stepTicks = 0;
        frozen = false;
        accumulator = 0;
        setTickRate(DEFAULT_TICK_RATE);
        clearTickTimes();
    }

    public void recordTickTime(long tickNanos) {
        int index = tickTimeIndex++ % TICK_TIME_SAMPLES;
        long old = tickTimesNanos[index];
        tickTimesNanos[index] = Math.max(0L, tickNanos);
        tickTimeSum += Math.max(0L, tickNanos) - old;
        tickTimeCount = Math.min(TICK_TIME_SAMPLES, tickTimeCount + 1);
    }

    public long averageTickTimeNanos() {
        return tickTimeCount == 0 ? 0L : tickTimeSum / tickTimeCount;
    }

    /** Sorted percentile (0..1, clamped) of the recorded tick times. */
    public long percentileTickTimeNanos(double percentile) {
        if (tickTimeCount == 0) {
            return 0L;
        }
        double p = Math.max(0D, Math.min(1D, percentile));
        int target = Math.max(0, Math.min(tickTimeCount - 1, (int) Math.round(p * (tickTimeCount - 1))));
        long[] copy = new long[tickTimeCount];
        System.arraycopy(tickTimesNanos, 0, copy, 0, tickTimeCount);
        java.util.Arrays.sort(copy);
        return copy[target];
    }

    public int tickTimeSampleCount() {
        return tickTimeCount;
    }

    public void clearTickTimes() {
        java.util.Arrays.fill(tickTimesNanos, 0L);
        tickTimeIndex = 0;
        tickTimeCount = 0;
        tickTimeSum = 0L;
    }

    public long nanosUntilNextTick() {
        if (frozen && !stepping) {
            return 1_000_000L;
        }
        if (sprintTicks > 0 || stepping) {
            return 0L;
        }
        return Math.max(0L, nanosPerTick - accumulator);
    }

    public long tickCount() {
        return tickCount;
    }

    public void onTick() {
        tickCount++;
    }

    public float tickRate() {
        return tickRate;
    }

    public long nanosPerTick() {
        return nanosPerTick;
    }

    public void setTickRate(float tickRate) {
        this.tickRate = Math.max(MIN_TICK_RATE, Math.min(MAX_TICK_RATE, tickRate));
        this.nanosPerTick = this.tickRate == DEFAULT_TICK_RATE
                ? PvzceConstants.NANOS_PER_TICK
                : Math.max(1L, Math.round(1_000_000_000L / this.tickRate));
        accumulator = 0;
    }

    public boolean isFrozen() {
        return frozen;
    }

    public void setFrozen(boolean frozen) {
        if (frozen) {
            stopSprinting();
            stopStepping();
            this.frozen = true;
        } else {
            this.frozen = false;
        }
    }

    public boolean isSprinting() {
        return sprintTicks > 0;
    }

    /** Schedules {@code ticks} sprint ticks; if frozen the server is unfrozen first. */
    public void requestSprint(int ticks) {
        sprintTicks = Math.max(0, sprintTicks + Math.max(0, ticks));
        frozen = false;
        stepping = false;
    }

    public void stopSprinting() {
        sprintTicks = 0;
    }

    public boolean isStepping() {
        return stepping;
    }

    /** Steps a frozen server; the server re-freezes automatically when done. */
    public boolean stepGameIfPaused(int ticks) {
        if (!frozen) {
            return false;
        }
        stepping = true;
        stepTicks = Math.max(1, ticks);
        frozen = false;
        return true;
    }

    public boolean stopStepping() {
        boolean wasStepping = stepping;
        stepping = false;
        stepTicks = 0;
        frozen = true;
        return wasStepping;
    }
}
