package com.pvzce.server.level.cardsource;

import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.PlantDef;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.server.PvzcePlayer;
import com.pvzce.common.core.Slot;
import com.pvzce.server.level.LevelServer;

import java.util.List;
import java.util.Random;

/**
 * Where a level's cards come from: the bar, its clock, and what spending a card costs.
 *
 * <p>A level has exactly one card source. The ordinary deck ({@link DeckCardSource})
 * charges sun and runs cooldowns; a conveyor belt ({@link BeltCardSource}) hands out free
 * cards on a timer and runs no cooldowns at all. Before this existed those two were one
 * field - {@code LevelServer.belt}, null or not - plus five {@code belt != null} branches:
 * the level constructor, the per-tick slot sync, the placement path, the save file and the
 * level-creation path. Every one of them had to be found and edited again to add a third
 * way of dealing cards; now the third one is a mechanic that returns a different
 * implementation of this interface.
 *
 * <p>Implementations are handed the player whose bar they drive rather than creating it,
 * so {@code LevelServer.plantPlayer} stays the one bar the rest of the server reads.
 */
public interface CardSource {
    /** The ordinary deck's short name. */
    String KIND_DECK = "deck";
    /**
     * The name a bar reports while a mutation is dealing it.
     *
     * <p>Not the mutation's own name: the client has to choose a card bar to draw, and what it
     * needs to know is "this is not the level's bar any more" - the panel says which mutation did
     * it. It also keeps the level's own kind ({@link #kind()}) honest, because the level's
     * mechanic still declares whatever it declared even while a mutation has taken the bar over.
     */
    String KIND_MUTATED = "mutated";

    /** The bar, in the order the player sees it. */
    List<Slot> slots();

    /**
     * Advances this source's own clock and streams whatever changed to the client.
     *
     * @param tickCount the level's tick counter, for sources that stream on an interval
     */
    void tick(LevelServer level, LevelServer.ServerBridge bridge, int tickCount);

    /**
     * Takes payment for one card.
     *
     * @return false when the card was refused, in which case a message has already been
     *         sent and nothing was charged
     */
    boolean spend(LevelServer level, LevelServer.ServerBridge bridge, Slot slot, PlantDef plant);

    /** Bookkeeping once the plant exists: the belt rebuilds its bar, the deck does nothing. */
    void afterSpend(LevelServer level, LevelServer.ServerBridge bridge, Slot slot);

    /**
     * Hands the bar one card from outside the level - a vase the player broke open.
     *
     * <p>Asked of the source rather than appended to the bar, because on a level whose bar is a
     * <em>projection</em> of something else (a conveyor belt) a card appended behind the source's
     * back is a card the next rebuild drops. A source whose bar is a plain list answers by
     * appending it, which is the default here.
     *
     * @return true when the card reached the player
     */
    default boolean receiveCard(LevelServer level, LevelServer.ServerBridge bridge, Identifier cardId) {
        return level.addCardToBar(cardId);
    }

    /**
     * Writes this source's run state into the level save.
     *
     * <p>The default is "nothing of my own": a source whose state is the level's own records
     * - the deck, whose bar is written as {@code Slots} and whose selection is the player's -
     * has nothing to add here.
     */
    default void save(CompoundTag root) {
    }

    /** Reads back what {@link #save} wrote. Runs before the deck bar is restored. */
    default void restore(LevelServer level, CompoundTag root) {
    }

    /**
     * Writes the state of <em>this bar</em> into a block of its own.
     *
     * <p>The difference from {@link #save} is who owns the choice: {@code save} writes whatever
     * key the source likes into the level's own root, which is fine for the two sources a level
     * declares - there is at most one of them and the level knows which. A <b>mutation</b> installs
     * its own source mid-run, and its state has to live inside the mutation's block (a belt's queue
     * belongs to the mutation that is dealing it, not to the level), so the level needs a pair it
     * can call on any source and write wherever it is told.
     *
     * <p>Both halves default to nothing: the deck's state is the player's own selection and its
     * cooldowns, which the level already writes.
     */
    default void collectSave(CompoundTag target) {
    }

    /** Reads back what {@link #collectSave} wrote. Must tolerate a missing block. */
    default void applySave(LevelServer level, CompoundTag source) {
    }

    /**
     * True when the level deals its own cards: no seed chooser, and no prices.
     *
     * <p>The same flag exists on the mechanic ({@code LevelMechanic.dealsItsOwnCards})
     * because the level list and the client's entry flow ask it before an instance exists.
     */
    boolean dealsItsOwnCards();

    /** Short name for logs. */
    String kind();

    /** Everything a source may need when it is built; one record so signatures stay stable. */
    record Context(PvzcePlayer player, LevelDef def, List<com.pvzce.api.util.Identifier> selectedSlots,
                   Random random) {
        public Context {
            selectedSlots = selectedSlots == null ? List.of() : List.copyOf(selectedSlots);
        }
    }
}
