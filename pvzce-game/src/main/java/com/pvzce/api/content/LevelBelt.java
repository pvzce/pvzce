package com.pvzce.api.content;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.content.mechanic.MechanicData;
import com.pvzce.api.util.Identifier;

import java.util.List;
import java.util.Random;

/**
 * A conveyor belt: the cards a level hands out instead of a deck and a sun economy.
 *
 * <p>A belt level has no seed chooser, no sun and no card prices - the belt delivers
 * free cards at a fixed rate and stops while it is full, which is what makes Wall-nut
 * Bowling a different game rather than a normal level with cheaper plants. All of that
 * is data: {@code interval_ticks} is how often a card arrives, {@code capacity} how
 * many may sit on the belt at once, {@code initial_cards} how many are already there
 * when the level starts, and {@code cards} the weighted pool it draws from.
 *
 * <p>This record is the block of the {@code pvzce:conveyor} level mechanic, so it is
 * authored inside the level's {@code mechanics} list rather than as a top-level key:
 * <pre>{@code
 * "mechanics": [
 *   { "type": "pvzce:conveyor", "interval_ticks": 300, "capacity": 6, "initial_cards": 2,
 *     "cards": [ { "id": "pvzce:bowling_nut", "weight": 1 } ] }
 * ]
 * }</pre>
 */
public record LevelBelt(int intervalTicks, int capacity, int initialCards, List<BeltCard> cards)
        implements MechanicData {
    public static final int DEFAULT_INTERVAL_TICKS = 300;
    public static final int DEFAULT_CAPACITY = 6;
    public static final int DEFAULT_INITIAL_CARDS = 2;
    /** A belt longer than this is a wall of cards, not a belt. */
    public static final int MAX_CAPACITY = 12;

    /** One entry of the belt's pool: a card id (slot or plant) and its relative weight. */
    public record BeltCard(Identifier card, int weight) {
        public static final Codec<BeltCard> CODEC = RecordCodecBuilder.create(i -> i.group(
                Identifier.CODEC.fieldOf("id").forGetter(BeltCard::card),
                Codec.INT.optionalFieldOf("weight", 1).forGetter(BeltCard::weight)
        ).apply(i, BeltCard::new));

        public BeltCard {
            weight = Math.max(0, weight);
        }
    }

    public static final MapCodec<LevelBelt> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.INT.optionalFieldOf("interval_ticks", DEFAULT_INTERVAL_TICKS).forGetter(LevelBelt::intervalTicks),
            Codec.INT.optionalFieldOf("capacity", DEFAULT_CAPACITY).forGetter(LevelBelt::capacity),
            Codec.INT.optionalFieldOf("initial_cards", DEFAULT_INITIAL_CARDS).forGetter(LevelBelt::initialCards),
            BeltCard.CODEC.listOf().optionalFieldOf("cards", List.of()).forGetter(LevelBelt::cards)
    ).apply(i, LevelBelt::new));

    /** The block as a standalone object; used where a belt is not inside a mechanics list. */
    public static final Codec<LevelBelt> CODEC = MAP_CODEC.codec();

    public LevelBelt {
        // A zero interval would spawn a card every tick, and a belt wider than the screen
        // is neither drawable nor useful; both are clamped rather than rejected because
        // the level should still be playable, and LevelValidator reports the authoring
        // mistake separately.
        intervalTicks = Math.max(1, intervalTicks);
        capacity = Math.max(1, Math.min(MAX_CAPACITY, capacity));
        initialCards = Math.max(0, Math.min(capacity, initialCards));
        cards = List.copyOf(cards);
    }

    /** True when the belt can actually produce something. */
    public boolean producesCards() {
        return totalWeight() > 0;
    }

    public int totalWeight() {
        int total = 0;
        for (BeltCard card : cards) {
            total += card.weight();
        }
        return total;
    }

    /**
     * Draws one card id from the pool, weighted.
     *
     * <p>Pure in {@code random} so a test can pin the distribution, and the only place
     * the weights are turned into a choice - the belt class asks for a card and does not
     * know how the pool was written.
     */
    public Identifier pick(Random random) {
        int total = totalWeight();
        if (total <= 0) {
            return null;
        }
        int roll = random.nextInt(total);
        for (BeltCard card : cards) {
            roll -= card.weight();
            if (roll < 0) {
                return card.card();
            }
        }
        return cards.get(cards.size() - 1).card();
    }

    /** One message per authoring problem, for {@code LevelValidator}. */
    public List<String> validate() {
        List<String> errors = new java.util.ArrayList<>();
        if (cards.isEmpty()) {
            errors.add("conveyor declares no cards: the belt would stay empty forever");
        } else if (!producesCards()) {
            errors.add("conveyor lists cards but all weights are 0: the belt would stay empty forever");
        }
        if (initialCards <= 0 && !producesCards()) {
            errors.add("conveyor starts empty and can never produce a card");
        }
        return errors;
    }
}
