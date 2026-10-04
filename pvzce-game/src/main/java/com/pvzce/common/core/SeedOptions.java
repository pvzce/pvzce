package com.pvzce.common.core;

import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.PlantDef;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.network.packet.SeedOption;

import java.util.ArrayList;
import java.util.Comparator;
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

    /**
     * Every card id the game can put in a bar: all slots, then tools and resources.
     *
     * <p>In <em>registration</em> order, which for a plant card is its almanac number: the bag
     * and the chooser are read the way the original's are, where a plant's place in the list
     * is when the player met it. It used to be {@code keySet().stream().sorted()}, so the
     * Peashooter sat wherever the alphabet put it and the one plant everyone owns was never
     * the first card in the bag.
     */
    public static List<Identifier> allCards() {
        List<Identifier> ids = new ArrayList<>(BuiltInRegistries.SLOT_TYPES.keySet());
        ids.sort(Comparator.comparingInt(SeedOptions::cardRank).thenComparing(Identifier::toString));
        return List.copyOf(ids);
    }

    /**
     * Where a card sorts: its plant's almanac number, or the slot's registration order.
     *
     * <p>The three kinds get their own band so a card list is never interleaved by accident -
     * the bag shows one section per kind and reads each one in order.
     */
    private static int cardRank(Identifier cardId) {
        SlotResolver.ResolvedCard card = SlotResolver.resolve(cardId).orElse(null);
        if (card != null) {
            PlantDef plant = card.kind() == Slot.Kind.PLANT
                    ? BuiltInRegistries.PLANTS.get(card.content())
                    : null;
            if (plant != null) {
                return plant.order();
            }
        }
        com.pvzce.api.content.SlotDef slot = BuiltInRegistries.SLOT_TYPES.get(cardId);
        return KIND_BAND + Math.max(0, BuiltInRegistries.SLOT_TYPES.getId(slot));
    }

    /** The band non-plant cards sort in, above every almanac number. */
    private static final int KIND_BAND = 10_000;

    /**
     * The card ids a level's chooser may show this player: every owned card in
     * almanac order, including any card the level pins that the ownership filter did not
     * already keep.
     *
     * <p>The level pins matter. A level may name a plant id directly instead of its
     * slot ({@link SlotResolver} resolves both) and that id is not in
     * {@link #allCards()}, so a filter-only pool would drop a card the level
     * itself insists on.
     */
    public static List<Identifier> cardPool(LevelDef def, java.util.function.Predicate<Identifier> owns) {
        LinkedHashSet<Identifier> ids = new LinkedHashSet<>();
        boolean zombieSide = PvzceIds.ZOMBIE_TEAM.equals(def.humanTeam());
        for (Identifier card : allCards()) {
            if (!matchesSide(card, zombieSide)) {
                continue;
            }
            if (owns == null || owns.test(card)) {
                ids.add(card);
            }
        }
        for (Identifier slot : def.slots()) {
            if (slot != null) {
                ids.add(slot);
            }
        }
        com.pvzce.api.content.PreparationData preparation =
                com.pvzce.common.level.mechanic.LevelMechanics.dataOf(def,
                        PvzceIds.MECHANIC_PREPARATION, com.pvzce.api.content.PreparationData.class).orElse(null);
        if (preparation != null) {
            ids.removeIf(id -> preparation.excludedCards().contains(id)
                    || SlotResolver.resolve(id).map(card -> preparation.excludedCards()
                            .contains(card.content())).orElse(false));
        }
        List<Identifier> ordered = new ArrayList<>(ids);
        ordered.sort(Comparator.comparingInt(SeedOptions::cardRank).thenComparing(Identifier::toString));
        return List.copyOf(ordered);
    }

    /**
     * Whether a card belongs to the side being played.
     *
     * <p>Zombie cards are only offered on a level the player plays as the zombies, and plant cards
     * are only offered on the others. Without this the bag would show a Zombie between the
     * Peashooter and the Sunflower on every ordinary level - the two sides' cards live in one
     * registry, so "which cards are mine" cannot be read off the registry and has to be read off
     * the card kind.
     */
    private static boolean matchesSide(Identifier card, boolean zombieSide) {
        SlotResolver.ResolvedCard resolved = SlotResolver.resolve(card).orElse(null);
        if (resolved == null) {
            // A bare content id the level pinned: it is the level's business, not the side's.
            return true;
        }
        return (resolved.kind() == Slot.Kind.ZOMBIE) == zombieSide;
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

    /**
     * One level buff, in the shape the chooser and the payload already speak.
     *
     * <p>A buff is not a card, but it is offered the same way: an id, something to draw, and a
     * name. Reusing {@link SeedOption} rather than inventing a second four-string record is what
     * lets the buff page share the payload codec, the icon lookup and the hover tip with the card
     * page - and the fields that mean nothing for a buff (price, kind) are stated as such
     * ({@link #BUFF_KIND}, {@code NO_PRICE}) instead of being left empty for the reader to guess.
     *
     * <p>What each field means for a buff:
     * <ul>
     *   <li>{@code slotId} and {@code content} are the buff id - a buff has no card behind it.</li>
     *   <li>{@code icon} is left empty on purpose: the sprite belongs to the buff, and the server
     *       does not read the buff registry to fill a field it cannot check. The client resolves
     *       it from the same definition it renders everything else from.</li>
     *   <li>{@code costSun} is {@link com.pvzce.common.network.packet.SlotInfo#NO_PRICE}: a buff
     *       is switched on, not bought.</li>
     * </ul>
     */
    public static SeedOption buffOption(Identifier buff, boolean owned) {
        return new SeedOption(buff.toString(), BUFF_KIND, buff.toString(), "",
                owned ? NO_PRICE_FIELD : LOCKED_OPTION);
    }

    /** Every buff is offered, so the caller that does not know a backpack offers them all. */
    public static SeedOption buffOption(Identifier buff) {
        return buffOption(buff, true);
    }

    /**
     * The marker a {@code SeedOption} carries when the player may see it but not take it.
     *
     * <p>The price field is reused rather than adding a field to the packet: for a card, "what
     * does it cost" and "may I have it at all" are the same slot in the same card frame - a
     * padlocked card is not for sale - and the value is impossible as a real price, so an old
     * client that ignored it would draw a card with no price rather than a wrong one.
     */
    public static final int LOCKED_OPTION = -2;

    /** {@code SlotInfo.NO_PRICE}, spelled here so this class does not import the packet layer. */
    private static final int NO_PRICE_FIELD = -1;

    /**
     * The {@code kind} a buff option carries.
     *
     * <p>Its own value rather than {@code "plant"} or {@code "resource"}: the card painter uses
     * the kind to decide what the footer says, and a buff is neither bought with sun nor collected
     * off the lawn.
     */
    public static final String BUFF_KIND = "buff";
}
