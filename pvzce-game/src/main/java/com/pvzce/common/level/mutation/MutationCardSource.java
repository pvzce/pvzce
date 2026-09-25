package com.pvzce.common.level.mutation;

import com.pvzce.server.level.LevelServer;
import com.pvzce.server.level.cardsource.CardSource;

/**
 * Builds the card bar one mutation deals.
 *
 * <p>A named interface rather than a {@code CardSource} subclass so the manager can ask "what bar
 * would you deal" without holding one: the bar is built at the handoff and thrown away on
 * eviction, and a mutation that is suppressed must not have built one at all.
 */
@FunctionalInterface
public interface MutationCardSource {
    /**
     * The bar this mutation deals, or {@code null} when it has nothing to deal right now.
     *
     * @param level   the running level, for what a mutation knows that a context does not - the
     *                player's backpack, the board, the clock
     * @param context the player, the level definition and the cards the player chose: the same
     *                context a level mechanic's own card source is built from
     */
    CardSource createCardSource(LevelServer level, CardSource.Context context);
}
