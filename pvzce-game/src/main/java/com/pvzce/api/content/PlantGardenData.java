package com.pvzce.api.content;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.content.mechanic.MechanicData;
import com.pvzce.api.util.Identifier;

import java.util.List;

/**
 * The garden the player has to eat: I, Zombie's board, laid out round by round.
 *
 * <p>An ordinary level's plants are the player's and grow during the run. This mechanic is the
 * mirror image: the plants are the <em>opposition</em>, they are already there when the level
 * starts, and when the lawn is clear the next garden appears - which is what makes an endless
 * I, Zombie a puzzle rather than a survival run. Each round states its own pool and count, so the
 * difficulty curve is a list in the level file.
 *
 * <p>It is deliberately not {@code pvzce:scary_potter}: that one hides its contents in pots the
 * player breaks open, and its rounds turn over the same way. What the two share - "a round is over
 * when the lawn is clear, sweep it and lay out the next one" - is eight lines in each, and folding
 * them together would have produced one mechanic with a flag for "are the plants hidden".
 */
public record PlantGardenData(int refreshSun, int initialDelayTicks, List<Round> rounds)
        implements MechanicData {
    /** Sun handed back between rounds when the round does not say. */
    public static final int DEFAULT_REFRESH_SUN = 250;
    /** A beat to look at the new garden before the player has to act on it. */
    public static final int DEFAULT_INITIAL_DELAY_TICKS = 90;

    /**
     * One garden.
     *
     * @param pool   what may be planted in it; drawn from at random
     * @param count  how many plants the round puts down
     * @param sun    sun the player gets on top of what they saved
     * @param rows   the rows to plant in, or empty for all of them
     */
    public record Round(List<Identifier> pool, int count, int sun, List<Integer> rows) {
        public static final Codec<Round> CODEC = RecordCodecBuilder.create(i -> i.group(
                Identifier.CODEC.listOf().fieldOf("plants").forGetter(Round::pool),
                Codec.INT.optionalFieldOf("count", 6).forGetter(Round::count),
                Codec.INT.optionalFieldOf("sun", DEFAULT_REFRESH_SUN).forGetter(Round::sun),
                Codec.INT.listOf().optionalFieldOf("rows", List.of()).forGetter(Round::rows)
        ).apply(i, Round::new));

        public Round {
            pool = List.copyOf(pool);
            count = Math.max(0, count);
            sun = Math.max(0, sun);
            rows = List.copyOf(rows);
        }
    }

    public static final MapCodec<PlantGardenData> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.INT.optionalFieldOf("refresh_sun", DEFAULT_REFRESH_SUN)
                    .forGetter(PlantGardenData::refreshSun),
            Codec.INT.optionalFieldOf("initial_delay_ticks", DEFAULT_INITIAL_DELAY_TICKS)
                    .forGetter(PlantGardenData::initialDelayTicks),
            Round.CODEC.listOf().optionalFieldOf("rounds", List.of()).forGetter(PlantGardenData::rounds)
    ).apply(i, PlantGardenData::new));

    /** The block as a standalone object; used where a garden is not in a mechanics list. */
    public static final Codec<PlantGardenData> CODEC = MAP_CODEC.codec();

    public PlantGardenData {
        refreshSun = Math.max(0, refreshSun);
        initialDelayTicks = Math.max(0, initialDelayTicks);
        rounds = List.copyOf(rounds);
    }

    /** One message per authoring problem, for {@code LevelMechanic.validate}. */
    public List<String> validate() {
        List<String> errors = new java.util.ArrayList<>();
        if (rounds.isEmpty()) {
            errors.add("plant garden declares no rounds: the lawn would be empty and the level"
                    + " would end on its first tick");
        }
        for (int i = 0; i < rounds.size(); i++) {
            Round round = rounds.get(i);
            if (round.pool().isEmpty()) {
                errors.add("plant garden round " + (i + 1) + " has no plants to draw from");
            }
            if (round.count() <= 0) {
                errors.add("plant garden round " + (i + 1) + " plants nothing, so it is already"
                        + " over when it starts");
            }
        }
        return errors;
    }
}
