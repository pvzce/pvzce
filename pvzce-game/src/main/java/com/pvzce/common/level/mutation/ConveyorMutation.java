package com.pvzce.common.level.mutation;

import com.pvzce.api.content.LevelBelt;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.server.level.LevelServer;
import com.pvzce.server.level.cardsource.BeltCardSource;
import com.pvzce.server.level.cardsource.CardSource;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * The card bar becomes a conveyor belt: free cards, on a clock, from the player's own plants.
 *
 * <p>Precedence 10, the highest in the catalogue, which is what makes it beat
 * {@link SlotReplaceMutation} when both are on the field: a belt <em>is</em> the bar, and a
 * mutation that rewrote the cards underneath it would be rewriting something nobody can see. The
 * suppressed one stays on the panel and takes effect again the moment the belt is evicted.
 *
 * <p>What the belt deals is rolled from the player's backpack rather than borrowed from a level's
 * own belt: the shipped belts are authored for their mini-game, and this one is improvised - a
 * player who has unlocked four plants should see those four.
 */
final class ConveyorMutation implements Mutation, CardDealingMutation {
    /**
     * How often the belt hands over a card.
     *
     * <p>The shipped belt levels use 150 ticks (2.5s); this one is a little slower, because it
     * arrives unasked on a lawn the player is already busy with.
     */
    private static final int INTERVAL_TICKS = 200;
    /** How many cards may ride the belt at once. */
    private static final int CAPACITY = 6;
    /** Cards already on it when it arrives, so the player can act immediately. */
    private static final int INITIAL_CARDS = 3;

    @Override
    public Identifier id() {
        return PvzceIds.MUTATION_CONVEYOR;
    }

    @Override
    public int cardSourcePrecedence() {
        return 10;
    }

    @Override
    public MutationCardSource cardSource() {
        return (level, context) -> {
            LevelBelt belt = beltFor(context, level.ownedCards());
            return belt == null ? null : new BeltCardSource(context, belt);
        };
    }

    /**
     * The belt this mutation deals, built from the cards the player owns.
     *
     * <p>The player's own selection says nothing here: the level's bar is being replaced, and
     * what an improvised belt carries is the whole backpack. An empty pool answers {@code null},
     * which leaves the level's own bar standing - a belt that deals nothing would read as the
     * mutation having broken the game rather than as the player owning nothing.
     */
    static LevelBelt beltFor(CardSource.Context context, Predicate<Identifier> ownsCard) {
        List<LevelBelt.BeltCard> cards = new ArrayList<>();
        for (Identifier plantId : BuiltInRegistries.PLANTS.keySet()) {
            if (ownsCard.test(plantId)) {
                cards.add(new LevelBelt.BeltCard(plantId, 1, LevelBelt.BeltCard.UNLIMITED));
            }
        }
        if (cards.isEmpty()) {
            return null;
        }
        return new LevelBelt(INTERVAL_TICKS, CAPACITY, INITIAL_CARDS, cards);
    }

    @Override
    public Object apply(LevelServer level, Mutation.Roll roll) {
        // Nothing to do here: the bar itself is installed by LevelServer the moment the manager
        // reports a new card-source owner, and this class's only job is to say what that bar is.
        return null;
    }

    /**
     * On a restore the belt is built again with the queue it was carrying.
     *
     * <p>Its contents are not this mutation's state but its <em>card source's</em>, which the level
     * saves and restores like any other bar (see {@code CardSource.collectSave}). That is also why
     * this method is the default: there is nothing of its own to put back.
     */
    @Override
    public Object applyFromSave(LevelServer level, Mutation.Roll roll) {
        return null;
    }
}
