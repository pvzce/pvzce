package com.pvzce.server;

import com.pvzce.api.content.LevelDef;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.core.SlotResolver;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A player's card bar and team membership. */
public final class PvzcePlayer {
    private final Team team;
    private final List<Slot> slots = new ArrayList<>();

    public PvzcePlayer(Team team) {
        this.team = team;
    }

    public Team team() {
        return team;
    }

    public void addSlot(Slot slot) {
        slots.add(slot);
    }

    /**
     * Replaces the whole bar.
     *
     * <p>Conveyor levels rebuild their bar whenever the belt changes: a belt card exists
     * only while it is on the belt, so there is no deck to tick cooldowns down on.
     */
    public void replaceSlots(List<Slot> newSlots) {
        slots.clear();
        slots.addAll(newSlots);
    }

    public List<Slot> slots() {
        return Collections.unmodifiableList(slots);
    }

    /**
     * The card with this index, or {@code null}.
     *
     * <p>Matched against {@link Slot#index()} rather than used as a list position. For an
     * ordinary bar the two are the same thing, but a belt hands out one id per card, so a
     * click that raced the belt names a card rather than a place in the queue - and either
     * finds that card or is refused, instead of spending whichever card slid into the slot.
     */
    public Slot slot(int index) {
        for (Slot slot : slots) {
            if (slot.index() == index) {
                return slot;
            }
        }
        return null;
    }

    /** Builds the plant player's card bar from the level's default slot list. */
    public static PvzcePlayer createPlantPlayer(Team team, LevelDef level) {
        return createPlantPlayer(team, level, level.slots());
    }

    /**
     * Builds the plant player's card bar from an explicit seed selection, in order.
     *
     * <p>Uses the same {@link SlotResolver} as the client's seed pool, so an id the
     * chooser offers is always a card the server grants.
     */
    public static PvzcePlayer createPlantPlayer(Team team, LevelDef level, List<Identifier> selectedSlots) {
        PvzcePlayer player = new PvzcePlayer(team);
        int index = 0;
        for (SlotResolver.ResolvedCard card : SlotResolver.resolveAll(selectedSlots)) {
            // A freshly built card bar is ready to use; the card's cooldown is
            // applied by LevelServer when the card is actually spent.
            player.addSlot(new Slot(index++, card.kind(), card.content(), card.costSun(), 0, card.uses()));
        }
        return player;
    }

    /** True when the player's current card bar contains the matching resource card. */
    public boolean hasResourceCard(Identifier resource) {
        for (Slot slot : slots) {
            if (slot.kind() == Slot.Kind.RESOURCE && slot.defId().equals(resource)) {
                return true;
            }
        }
        return false;
    }
}
