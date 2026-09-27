package com.pvzce.api.content;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.content.mechanic.MechanicData;
import com.pvzce.api.util.Identifier;

import java.util.List;
import java.util.Random;

/**
 * Seed rain: cards fall onto the lawn on a clock, and the player picks them up.
 *
 * <p>The mini-game's whole idea is that the deck is not the player's - it is the level's, and it
 * arrives a packet at a time, on cells nobody chose. What a packet holds is a <em>card</em> rather
 * than a plant, so it goes through the same door a broken vase's plant does
 * ({@code LevelServer.spawnCardDrop} + {@code PickUpCardC2S}), and it is planted for free for the
 * same reason: the rain already paid for it (see {@code LevelServer.plantHeldCard}).
 *
 * <p>This record is the block of the {@code pvzce:seed_rain} level mechanic:
 * <pre>{@code
 * "mechanics": [
 *   { "type": "pvzce:seed_rain", "interval_ticks": 420, "initial_delay_ticks": 300,
 *     "cards": [ { "id": "pvzce:pea_shooter", "weight": 3 },
 *                { "id": "pvzce:wall_nut", "weight": 2 } ] }
 * ]
 * }</pre>
 *
 * <p>Weights are the belt's ({@code LevelBelt.BeltCard} is the same idea one mechanic over), and
 * so is {@code max_count}: a pool that hands out a cherry bomb every time would make the level a
 * different one, and an author who wants exactly one writes it down.
 */
public record SeedRainData(int intervalTicks, int initialDelayTicks, List<Card> cards)
        implements MechanicData {
    /** How often a packet falls, in ticks. Four seconds at the reference rate. */
    public static final int DEFAULT_INTERVAL_TICKS = 240;
    /** How long the lawn is left alone at the start, so the player can look at it first. */
    public static final int DEFAULT_INITIAL_DELAY_TICKS = 300;
    /** Faster than this is a blizzard of packets, not rain. */
    public static final int MIN_INTERVAL_TICKS = 30;

    /** One card of the pool: what falls, how likely, and how many times. */
    public record Card(Identifier card, int weight, int maxCount) {
        /** {@code max_count} unwritten: this card may fall as often as the rain draws it. */
        public static final int UNLIMITED = -1;

        public static final Codec<Card> CODEC = RecordCodecBuilder.create(i -> i.group(
                Identifier.CODEC.fieldOf("id").forGetter(Card::card),
                Codec.INT.optionalFieldOf("weight", 1).forGetter(Card::weight),
                Codec.INT.optionalFieldOf("max_count", UNLIMITED).forGetter(Card::maxCount)
        ).apply(i, Card::new));

        public Card {
            weight = Math.max(0, weight);
            maxCount = maxCount < 0 ? UNLIMITED : maxCount;
        }

        /** True when one more of these may still fall after {@code dropped} copies. */
        public boolean canDropMore(int dropped) {
            return maxCount == UNLIMITED || dropped < maxCount;
        }
    }

    public static final MapCodec<SeedRainData> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.INT.optionalFieldOf("interval_ticks", DEFAULT_INTERVAL_TICKS)
                    .forGetter(SeedRainData::intervalTicks),
            Codec.INT.optionalFieldOf("initial_delay_ticks", DEFAULT_INITIAL_DELAY_TICKS)
                    .forGetter(SeedRainData::initialDelayTicks),
            Card.CODEC.listOf().optionalFieldOf("cards", List.of()).forGetter(SeedRainData::cards)
    ).apply(i, SeedRainData::new));

    /** The block as a standalone object; used where rain is not inside a mechanics list. */
    public static final Codec<SeedRainData> CODEC = MAP_CODEC.codec();

    public SeedRainData {
        // Clamped rather than rejected, the same way the belt clamps: a level with a silly
        // interval should still be playable, and the validator says so separately.
        intervalTicks = Math.max(MIN_INTERVAL_TICKS, intervalTicks);
        initialDelayTicks = Math.max(0, initialDelayTicks);
        cards = List.copyOf(cards);
    }

    public int totalWeight() {
        int total = 0;
        for (Card card : cards) {
            total += card.weight();
        }
        return total;
    }

    /**
     * Draws one card from the pool, weighted, skipping the ones that have run out.
     *
     * <p>Pure in {@code random} so a test can pin the distribution, and the only place the
     * weights become a choice - the mechanic asks for a card and never reads the pool itself.
     * {@code null} means "nothing left to drop", which is how a level whose every card has a
     * {@code max_count} stops raining instead of falling back to the first entry.
     */
    public Identifier pick(Random random, java.util.function.ToIntFunction<Identifier> dropped) {
        List<Card> available = new java.util.ArrayList<>();
        int total = 0;
        for (Card card : cards) {
            if (card.weight() > 0 && card.canDropMore(dropped.applyAsInt(card.card()))) {
                available.add(card);
                total += card.weight();
            }
        }
        if (total <= 0) {
            return null;
        }
        int roll = random.nextInt(total);
        for (Card card : available) {
            roll -= card.weight();
            if (roll < 0) {
                return card.card();
            }
        }
        return available.get(available.size() - 1).card();
    }

    /** One message per authoring problem, for {@code LevelMechanic.validate}. */
    public List<String> validate() {
        List<String> errors = new java.util.ArrayList<>();
        if (cards.isEmpty()) {
            errors.add("seed rain declares no cards: nothing would ever fall");
        } else if (totalWeight() <= 0) {
            errors.add("seed rain lists cards but all weights are 0: nothing would ever fall");
        }
        for (Card card : cards) {
            // A card that may never fall reads as "this level offers a cherry bomb" and never
            // does - the failure mode of a stray digit in max_count.
            if (card.maxCount() == 0) {
                errors.add("seed rain card '" + card.card() + "' has max_count 0, so it would"
                        + " never fall (leave max_count out for no limit)");
            }
        }
        return errors;
    }
}
