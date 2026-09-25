package com.pvzce.common.level;

/**
 * One capability's countdown clock, at the plant's current rate.
 *
 * <p>The reason this is an object rather than a number: a capability counts its own cooldown down
 * by asking "how many ticks of progress did I just earn", and two capabilities on one plant (a
 * Sun-shroom is a producer, a Peashooter that also produces is both) must not share the answer -
 * whatever the first one spends, the second never sees. One clock per capability is what makes
 * "each of them runs at 1.5x" true instead of "the two of them together run at 1.5x".
 *
 * <p>The arithmetic is exact rather than rounded per tick: an accumulator that added
 * {@code round(rate)} every tick would drift, and one that rounded down would make a plant at 1.5x
 * fire no faster at all. Steps are derived from the tick count instead, so 1.5 delivers three steps
 * every two ticks and 4/3 - what being watered is worth - delivers four every three, forever.
 */
public final class RateClock {
    private int ticks;
    private int steps;

    /**
     * One tick of progress.
     *
     * @param rate how many steps per tick this clock earns; 1 is the plant's own written speed,
     *              so a rate of 1 earns exactly one step per tick
     * @return the whole steps earned this tick, which may be zero or more than one
     */
    public int step(float rate) {
        ticks++;
        // Steps so far, truncated from the exact product: the fraction is carried in `ticks`
        // rather than lost, which is what makes a fractional rate come out even in the long run.
        int total = (int) (ticks * Math.max(0F, rate));
        int gain = Math.max(0, total - steps);
        steps = total;
        return gain;
    }
}
