package com.pvzce.common.level.mutation;

import com.pvzce.server.level.LevelServer;

/**
 * A mutation that rewrites the cards <em>inside</em> the bar the level deals.
 *
 * <p>The other half of the card-bar takeover, next to {@link CardDealingMutation}. A dealer hands
 * the player a different bar (a conveyor belt); a rewriter keeps the bar and changes what is on it
 * ({@code slot_replace} swaps each plant for another with the same abilities). They are separate
 * interfaces because they answer different questions - "what bar is this" against "what is on the
 * bar" - and because only one of the two needs a {@code CardSource}.
 *
 * <p>What they share is that only one of them may act at a time, and that the winner is
 * <b>whichever arrived last</b>: a random-card rewrite that lands after a belt takes the bar over
 * from it, and a belt that lands after a rewrite puts the rewrite's work out of sight. See
 * {@link Mutation#suppressedBy}.
 *
 * <p>{@link #rewriteBar} exists because the level rebuilds its own bar from the player's chosen
 * cards whenever the bar's owner changes or a round is re-picked, and that rebuild knows nothing
 * about any mutation. Without a way to ask the rewriter to lay its work down again, the rewrite
 * would survive only until the next rebuild - which is exactly how a belt leaving the field used
 * to hand the player their original cards back.
 */
public interface RewriteBarMutation {
    /**
     * Re-applies this mutation's rewrite to whatever bar is standing now.
     *
     * @param state the object {@link Mutation#apply} (or {@code applyFromSave}) returned for the
     *              running activation
     */
    void rewriteBar(LevelServer level, Object state);
}
