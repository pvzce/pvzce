package com.pvzce.common.level.mutation;

/**
 * Presentation a running set of mutations asks the client for.
 *
 * <p>A bit set rather than one boolean per effect: the client needs one answer per effect ("is
 * the board dark right now"), several mutations may want the same one, and a set composes by
 * union where booleans would need a rule about which mutation wins.
 *
 * <p>Kept to things the <em>client</em> owns. Anything that changes the simulation is a game
 * rule, and anything that changes what the server sends is the mutation's own business.
 */
public enum MutationEffects {
    /** Nothing extra. */
    NONE(0),
    /**
     * A dark haze over the board: the visual half of "it is night now".
     *
     * <p>The backdrop, the night lighting and the mushroom rule all follow the level's clock,
     * which the server rewrites. This is the standing wash the original puts over a night lawn,
     * and it is client-side because it is a drawing.
     */
    DARKNESS(1),
    /** The pointer carries the mallet, as in Whack-a-Zombie. */
    WHACK_CURSOR(1 << 1);

    private final int mask;

    MutationEffects(int mask) {
        this.mask = mask;
    }

    /** This effect's bit. */
    public int mask() {
        return mask;
    }

    /** True when a bit set produced by {@link #union} contains this effect. */
    public boolean isSet(int effects) {
        return (effects & mask) != 0;
    }

    /** The union of two effects, for folding a whole list into one bit set. */
    public static int union(int left, int right) {
        return left | right;
    }

    /** True when a bit set contains any of the given effects. */
    public static boolean anySet(int effects, MutationEffects... candidates) {
        for (MutationEffects effect : candidates) {
            if (effect.isSet(effects)) {
                return true;
            }
        }
        return false;
    }
}
