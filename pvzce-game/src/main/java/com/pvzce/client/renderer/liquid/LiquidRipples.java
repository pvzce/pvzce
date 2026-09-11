package com.pvzce.client.renderer.liquid;

import java.util.Arrays;

/**
 * The active surface disturbances, as the shader sees them.
 *
 * <p>A ripple is a single expanding ring, so all it needs is a centre, an age and
 * a strength. Ages are advanced by wall-clock seconds rather than by server ticks:
 * a ripple is pure presentation, nothing in the simulation reads it back, and
 * tying it to the tick clock would make the water freeze whenever the game is
 * paused or the server is stepped.
 *
 * <p>The buffer is fixed at {@link LiquidShader#MAX_RIPPLES}. When it is full the
 * OLDEST ripple is dropped rather than the newest: a fresh splash is the one the
 * player is looking at, and a stale ring that is already fading is the cheapest
 * thing to lose. Uploading an array of a fixed size also keeps the uniform update
 * a single call every frame instead of one per ripple.
 */
public final class LiquidRipples {
    /** Seconds for a ring to expand from nothing to its full radius and fade out. */
    public static final float LIFETIME_SECONDS = 1.5F;

    private final float[] centreX = new float[LiquidShader.MAX_RIPPLES];
    private final float[] centreY = new float[LiquidShader.MAX_RIPPLES];
    private final float[] age = new float[LiquidShader.MAX_RIPPLES];
    private final float[] strength = new float[LiquidShader.MAX_RIPPLES];
    /** Insertion order, oldest first; used only to decide which slot to recycle. */
    private final long[] sequence = new long[LiquidShader.MAX_RIPPLES];
    private final float[] packed = new float[LiquidShader.MAX_RIPPLES * 4];
    private int count;
    private long nextSequence;
    private long dropped;

    /** Adds a ring, replacing the oldest one when the buffer is full. */
    public void add(float x, float y, float strength) {
        int slot = count < LiquidShader.MAX_RIPPLES ? count++ : oldestSlot();
        centreX[slot] = x;
        centreY[slot] = y;
        age[slot] = 0F;
        this.strength[slot] = Math.max(0F, strength);
        sequence[slot] = nextSequence++;
    }

    private int oldestSlot() {
        int slot = 0;
        for (int index = 1; index < count; index++) {
            if (sequence[index] < sequence[slot]) {
                slot = index;
            }
        }
        dropped++;
        return slot;
    }

    /** Advances every ring and retires the finished ones. */
    public void tick(float seconds) {
        if (seconds <= 0F) {
            return;
        }
        int live = 0;
        for (int index = 0; index < count; index++) {
            age[index] += seconds / LIFETIME_SECONDS;
            if (age[index] >= 1F) {
                continue;
            }
            if (live != index) {
                centreX[live] = centreX[index];
                centreY[live] = centreY[index];
                age[live] = age[index];
                strength[live] = strength[index];
                sequence[live] = sequence[index];
            }
            live++;
        }
        count = live;
    }

    public void clear() {
        count = 0;
        dropped = 0;
    }

    public int count() {
        return count;
    }

    /** How many rings were recycled because the buffer was full; for diagnostics. */
    public long dropped() {
        return dropped;
    }

    /**
     * Packs the rings for upload: {@code (x, y, age, strength)} each.
     *
     * <p>Also refreshes the age→shader mapping so the caller can upload once per
     * liquid pass instead of once per frame.
     */
    public float[] pack() {
        for (int index = 0; index < count; index++) {
            packed[index * 4] = centreX[index];
            packed[index * 4 + 1] = centreY[index];
            packed[index * 4 + 2] = age[index];
            packed[index * 4 + 3] = strength[index];
        }
        if (count < LiquidShader.MAX_RIPPLES) {
            Arrays.fill(packed, count * 4, packed.length, 0F);
        }
        return packed;
    }
}
