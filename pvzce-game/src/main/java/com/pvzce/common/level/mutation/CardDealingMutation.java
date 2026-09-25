package com.pvzce.common.level.mutation;

import com.pvzce.server.level.cardsource.CardSource;

/**
 * A mutation that hands the player a different card bar.
 *
 * <p>Separate from {@link Mutation} because only a handful of mutations do it and only one may do
 * it at a time: the manager picks the highest {@code cardSourcePrecedence} among the ones that
 * are acting and asks it for a source, and everything else is told nothing about cards.
 */
public interface CardDealingMutation {
    /**
     * The bar this mutation deals.
     *
     * <p>Called through {@code LevelServer}, which builds the context and installs the result -
     * so the player, the level's definition and the cards the player chose all arrive the same
     * way they do for a level mechanic's own card source.
     */
    MutationCardSource cardSource();
}
