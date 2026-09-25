package com.pvzce.common.level.mutation;

import com.pvzce.api.content.LevelBelt;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.core.Slot;
import com.pvzce.common.core.SlotResolver;
import com.pvzce.server.level.LevelServer;
import com.pvzce.server.level.cardsource.BeltCardSource;
import com.pvzce.server.level.cardsource.CardSource;

import java.util.ArrayList;
import java.util.List;

/**
 * The card bar becomes a conveyor belt: free cards, on a clock, from the player's own plants.
 *
 * <p>A {@link CardDealingMutation}, so it competes for the bar with {@link SlotReplaceMutation} -
 * and the winner is whichever arrived last (see {@link Mutation#suppressedBy}). A belt <em>is</em>
 * the bar, so a rewrite that landed earlier would be rewriting something nobody can see; a rewrite
 * that lands <em>after</em> the belt takes the bar back and rewrites the player's own cards, which
 * is what the player asked for by having it appear later. The suppressed one stays on the panel and
 * takes effect again the moment the one above it is evicted.
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
    /**
     * The narrowest tray this belt draws, in cards.
     *
     * <p>The shipped belts hold six, and a mutation that reduced the player's plant cards to two
     * would otherwise draw a two-card tray that reads as a broken HUD. The capacity is the number
     * of plant cards being replaced whenever that is larger - which is what makes the tray as wide
     * as the bar it stands in for.
     */
    private static final int MIN_CAPACITY = 6;
    /** Cards already riding when it arrives, so the player can do something immediately. */
    private static final int INITIAL_CARDS = 3;

    @Override
    public Identifier id() {
        return PvzceIds.MUTATION_CONVEYOR;
    }

    @Override
    public MutationCardSource cardSource() {
        return (level, context) -> {
            LevelBelt belt = beltFor(context);
            return belt == null ? null : new BeltCardSource(context, belt);
        };
    }

    /**
     * The belt this mutation deals: the player's bar with its plant cards replaced by a belt.
     *
     * <p><b>Only the plant cards are replaced.</b> A tool or a resource card is not something a
     * belt can hand out on a timer - the shovel is how the player fixes a mistake and the sun card
     * is what the sun bank is drawn for - so they stay on the bar exactly as they were, and the
     * belt deals only the cards it took over. That is also what makes the tray as wide as the bar
     * it stands in for: the pool is one entry per replaced plant card.
     */
    static LevelBelt beltFor(CardSource.Context context) {
        List<Identifier> plants = plantCardsOf(context);
        if (plants.isEmpty()) {
            // Nothing to hand out: the mutation leaves the level's own bar standing rather than
            // replacing it with a belt that would never deal anything.
            return null;
        }
        List<LevelBelt.BeltCard> cards = new ArrayList<>(plants.size());
        for (Identifier plantId : plants) {
            cards.add(new LevelBelt.BeltCard(plantId, 1, LevelBelt.BeltCard.UNLIMITED));
        }
        return new LevelBelt(INTERVAL_TICKS, Math.max(MIN_CAPACITY, plants.size()),
                INITIAL_CARDS, cards);
    }

    /**
     * The plant cards of the bar this belt is replacing.
     *
     * <p>The bar the player chose, when the caller still has it - and otherwise the bar the player
     * is holding. The second case is what a resumed run uses: by then the level's bar is whatever
     * the save restored, which is the same set of cards, and a bar another mutation has already
     * shuffled is still a bar of the same plants.
     */
    private static List<Identifier> plantCardsOf(CardSource.Context context) {
        List<Identifier> source = context.selectedSlots().isEmpty()
                ? currentBar(context)
                : context.selectedSlots();
        List<Identifier> plants = new ArrayList<>();
        for (Identifier cardId : source) {
            SlotResolver.ResolvedCard card = SlotResolver.resolve(cardId).orElse(null);
            if (card != null && card.kind() == Slot.Kind.PLANT) {
                plants.add(card.slotId());
            }
        }
        return List.copyOf(plants);
    }

    /** The cards on the player's bar right now, by the card ids the bar was built from. */
    private static List<Identifier> currentBar(CardSource.Context context) {
        List<Identifier> cards = new ArrayList<>();
        for (Slot slot : context.player().slots()) {
            cards.add(slot.defId());
        }
        return List.copyOf(cards);
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
