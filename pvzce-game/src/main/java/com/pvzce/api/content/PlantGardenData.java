package com.pvzce.api.content;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.content.mechanic.MechanicData;
import com.pvzce.api.util.Identifier;

import java.util.List;

/**
 * A pre-planted garden. Brain puzzles use a random formation within the left columns;
 * endless puzzles regenerate it after all lane objectives are eaten. The rounds list
 * remains the data-driven clearing mode for authored gardens without brains.
 */
public record PlantGardenData(int refreshSun, int initialDelayTicks, List<Round> rounds, boolean brains, boolean endless, int columns, List<Round> formation)
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
            Round.CODEC.listOf().optionalFieldOf("rounds", List.of()).forGetter(PlantGardenData::rounds),
            Codec.BOOL.optionalFieldOf("brains", false).forGetter(PlantGardenData::brains),
            Codec.BOOL.optionalFieldOf("endless", false).forGetter(PlantGardenData::endless),
            Codec.INT.optionalFieldOf("columns", 4).forGetter(PlantGardenData::columns),
            Round.CODEC.listOf().optionalFieldOf("formation", List.of()).forGetter(PlantGardenData::formation)
    ).apply(i, PlantGardenData::new));

    /** The block as a standalone object; used where a garden is not in a mechanics list. */
    public static final Codec<PlantGardenData> CODEC = MAP_CODEC.codec();

    public PlantGardenData(int refreshSun, int initialDelayTicks, List<Round> rounds) {
        this(refreshSun, initialDelayTicks, rounds, false, false, 4, List.of());
    }

    /** The original reserves the left columns for cardboard plants; bungees land among them. */
    public boolean canPlaceZombie(Identifier zombie, int x) {
        return !brains || (zombie.equals(Identifier.withDefaultNamespace("bungee_zombie"))
                ? x < columns : x >= columns);
    }

    public PlantGardenData {
        refreshSun = Math.max(0, refreshSun);
        initialDelayTicks = Math.max(0, initialDelayTicks);
        rounds = List.copyOf(rounds);
        formation = List.copyOf(formation);
    }

    /** One message per authoring problem, for {@code LevelMechanic.validate}. */
    public List<String> validate() {
        List<String> errors = new java.util.ArrayList<>();
        if (brains) {
            if (columns < 1 || columns > 8) errors.add("plant garden columns must be between 1 and 8");
            if (!endless && formation.isEmpty()) errors.add("brain puzzle needs a formation");
            for (Round group : formation) {
                if (group.pool().size() != 1 || group.count() <= 0)
                    errors.add("brain garden formation groups need one plant id and a positive count");
                if (group.rows().stream().anyMatch(row -> row < 0 || row >= 5))
                    errors.add("brain garden formation row is outside the lawn");
            }
            return errors;
        }
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
