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
    WHACK_CURSOR(1 << 1),
    /**
     * The backdrop becomes the pool after dark.
     *
     * <p>The one effect that is a <em>picture</em> rather than a wash, and it is here because the
     * backdrop is the client's: the server rewrote the clock and that is what the lighting, the
     * mushrooms and the client's own day/night blend all read, but which texture is drawn came in
     * with the level payload and nothing on the wire could change it. A mutation that says "it is
     * night now" on a day level therefore arrived as a dark wash over a bright lawn.
     *
     * <p>Distinct from {@link #DARKNESS}, which is the haze drawn <em>on top</em> of whatever
     * backdrop is in force: a level that is already at night wants the haze without the swap.
     */
    POOL_NIGHT(1 << 2),
    /**
     * The board is under fog.
     *
     * <p>Not how the fog is drawn - the span and the lamps travel on the fog mechanic's own sync -
     * but how the <em>client</em> learns that it has to look again: the world overlays are built
     * once per level, and a mutation that rolls fog onto a lawn that had none would otherwise be a
     * darkening nothing ever draws.
     */
    FOG(1 << 3);

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

    /**
     * A backdrop this effect asks the client to draw instead of the level's own, or empty.
     *
     * <p>Returned rather than declared as a constant field so the id lives in the effect that
     * means it - the same "one fact, one place" rule the rest of the content follows.
     */
    public java.util.Optional<com.pvzce.api.util.Identifier> backdrop() {
        if (this != POOL_NIGHT) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(com.pvzce.api.util.Identifier.withDefaultNamespace(
                "textures/gui/screen/level/background4"));
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
