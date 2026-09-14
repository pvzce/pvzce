package com.pvzce.common.core;

import com.pvzce.api.content.LevelDef;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.network.packet.SeedOption;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Builds the client-facing seed-pool option list for a level.
 *
 * <p>Shares {@link SlotResolver} with the server's card-bar builder, so the pool the player
 * chooses from and the bar the server actually grants can no longer disagree (they used to,
 * for any slot id without a matching {@code slots/*.json}).
 *
 * <p>Which cards land in the pool is the backpack's business: {@link #forLevel(LevelDef,
 * java.util.function.Predicate)} keeps only the cards the player owns, plus whatever the
 * level pins. Which of those are pinned is decided by {@link LevelDef#seedPlan}, not by
 * leaving cards out of the list.
 */
public final class SeedOptions {
    private SeedOptions() {
    }

    public static List<SeedOption> forLevel(LevelDef def) {
        return forLevel(def, null);
    }

    /**
     * The chooser pool for one player.
     *
     * <p>{@code owns} is the backpack: a card the player has not unlocked is not
     * offered. The level's own cards are always included even when they are not
     * owned, because a level may hand the player a card it wants them to use
     * (a tutorial lending a potato mine) and the chooser still has to draw it as
     * a locked card. {@code null} means "everything is owned", which is what the
     * tests and the editor's card-pool preview want.
     */
    public static List<SeedOption> forLevel(LevelDef def, java.util.function.Predicate<Identifier> owns) {
        List<SeedOption> result = new ArrayList<>();
        for (Identifier card : cardPool(def, owns)) {
            addResolved(result, card);
        }
        return List.copyOf(result);
    }

    /** Every card id the game can put in a bar: all slots, then tools and resources. */
    public static List<Identifier> allCards() {
        List<Identifier> ids = new ArrayList<>();
        BuiltInRegistries.SLOT_TYPES.keySet().stream().sorted().forEach(ids::add);
        return List.copyOf(ids);
    }

    /**
     * The card ids a level's chooser may show this player: every owned card in
     * registration order, then any card the level pins that the filter did not
     * already keep.
     *
     * <p>The tail matters. A level may name a plant id directly instead of its
     * slot ({@link SlotResolver} resolves both) and that id is not in
     * {@link #allCards()}, so a filter-only pool would drop a card the level
     * itself insists on.
     */
    public static List<Identifier> cardPool(LevelDef def, java.util.function.Predicate<Identifier> owns) {
        LinkedHashSet<Identifier> ids = new LinkedHashSet<>();
        for (Identifier card : allCards()) {
            if (owns == null || owns.test(card)) {
                ids.add(card);
            }
        }
        for (Identifier slot : def.slots()) {
            if (slot != null) {
                ids.add(slot);
            }
        }
        return List.copyOf(ids);
    }

    /** The ids of the cards the level pinned, capped at its slot count. */
    public static List<String> lockedSlotIds(LevelDef def) {
        return def.seedPlan(allCards()).lockedSlots().stream().map(Identifier::toString).toList();
    }

    /**
     * True when the chooser would have nothing to offer: every slot is pinned by the level,
     * or the backpack has nothing left that is not already pinned.
     *
     * <p>Both mean the same thing to the player - no cards to pick - and the answer belongs
     * here rather than in the screen because two callers need it before a screen exists: the
     * chooser decides whether to draw a panel at all, and the save prompt's "restart"
     * decides whether to open one. The server answers the same question a third time,
     * structurally, when it sanitises the submitted selection.
     *
     * @param pool       the chooser's option list, as the server sent it
     * @param maxSlots   the level's resolved card-slot count for this player
     * @param lockedSlot the level's own card ids; null counts as none
     */
    public static boolean hasNothingToChoose(List<SeedOption> pool, int maxSlots,
                                             java.util.Collection<String> lockedSlot) {
        java.util.Set<String> locked = lockedSlot == null
                ? java.util.Set.of() : new LinkedHashSet<>(lockedSlot);
        int slots = Math.max(0, maxSlots);
        if (slots - Math.min(slots, locked.size()) <= 0) {
            return true;
        }
        if (pool == null) {
            return true;
        }
        for (SeedOption option : pool) {
            if (!locked.contains(option.slotId())) {
                return false;
            }
        }
        return true;
    }

    private static void addResolved(List<SeedOption> out, Identifier card) {
        SlotResolver.resolve(card).ifPresent(resolved -> out.add(new SeedOption(
                resolved.slotId().toString(), resolved.kind().json(), resolved.content().toString(),
                resolved.icon().map(Object::toString).orElse(""), resolved.costSun())));
    }
}
