package com.pvzce.common.level;

/**
 * How long a card takes to come back, as the level measures it.
 *
 * <p>A card's cooldown is the content's own number (the plant's, the tool's) scaled by the
 * level's {@code pvzce:seed_cooldown_multiplier}. The multiplication lives here rather than
 * at either end of the wire because both ends need the same answer: the server charges it
 * when a card is spent, and the card bar draws the recharge against it. A client that
 * derived its own would fill the sweep at a different rate from the one the card actually
 * comes back at.
 *
 * <p>Zero stays zero: a card with no cooldown at all (the sun bank, the shovel) must not
 * acquire a one-tick one out of the rounding, which is also why the floor of one tick is
 * applied only once the product is known to be positive.
 */
public final class CardCooldown {
    /** What a level that says nothing about card cooldowns runs with. */
    public static final float DEFAULT_MULTIPLIER = 1F;

    /**
     * The ticks a card with this cooldown waits in a level with this multiplier.
     *
     * <p>A multiplier of zero - or a non-finite one, which no codec can produce but an
     * editor or a mod could - means "no cooldown", the same answer a card whose own
     * cooldown is zero gets. Rounded rather than truncated so a third of 90 ticks is 30
     * and not 29.
     */
    public static int effective(int baseTicks, float multiplier) {
        if (baseTicks <= 0 || !Float.isFinite(multiplier) || multiplier <= 0F) {
            return 0;
        }
        return Math.max(1, Math.round(baseTicks * multiplier));
    }

    private CardCooldown() {
    }
}
