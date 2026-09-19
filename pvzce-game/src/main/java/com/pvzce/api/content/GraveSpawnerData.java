package com.pvzce.api.content;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.content.mechanic.MechanicData;
import com.pvzce.api.util.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * Graves that keep giving up their dead while the level runs.
 *
 * <p>The block of the {@code pvzce:grave_spawner} level mechanic, and the original's
 * Whack-a-Zombie: 2-5's zombies come out of the gravestones rather than off the road, a player
 * who smashes the graves gets them back, and the level's pressure comes from the lawn being
 * crowded with holes rather than from a wave table.
 *
 * <pre>
 * { "type": "pvzce:grave_spawner",
 *   "min_graves": 5, "initial_graves": 9, "interval": 420,
 *   "zombies": ["pvzce:basic_zombie", "pvzce:conehead_zombie"],
 *   "min_x": 4, "max_x": 8 }
 * </pre>
 *
 * <p>Distinct from the {@code graves_spawn_night} rule, which is the other thing gravestones do
 * and which every night level gets by default: <em>that</em> one opens the graves that are
 * already on the lawn once, at the last wave. A level may have both.
 *
 * @param zombies       what a grave raises; an empty list means the level's final wave decides,
 *                      which is what a level that only wants the graves to be a threat late
 *                      should write. An entry naming an unregistered zombie is reported by the
 *                      validator and skipped when a grave comes up
 * @param minGraves     how many graves the level keeps on the lawn; smashing one below this
 *                      count makes a new one rise. Zero means the graves never come back, which
 *                      is the same as not declaring the mechanic at all
 * @param initialGraves how many graves are raised when the level starts; -1 (or unwritten)
 *                      means "as many as {@code minGraves}", so a block that only cares about
 *                      the steady state does not have to write it twice
 * @param interval      ticks between one grave raising a zombie and the next
 * @param minX          first column of the region graves may appear in
 * @param maxX          last column of it, inclusive; -1 (or unwritten) means the level's last
 */
public record GraveSpawnerData(List<Identifier> zombies, int minGraves, int initialGraves,
                               int interval, int minX, int maxX) implements MechanicData {
    /** {@code initial_graves} unwritten: raise as many as {@code min_graves}. */
    public static final int INITIAL_AS_MINIMUM = -1;
    /** {@code max_x} unwritten: the level's own last column. */
    public static final int MAX_X_UNSET = -1;
    /** How fast a grave can raise zombies when nothing says otherwise: once every 7 seconds. */
    public static final int DEFAULT_INTERVAL = 420;

    public static final MapCodec<GraveSpawnerData> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Identifier.CODEC.listOf().optionalFieldOf("zombies", List.of()).forGetter(GraveSpawnerData::zombies),
            Codec.INT.optionalFieldOf("min_graves", 0).forGetter(GraveSpawnerData::minGraves),
            Codec.INT.optionalFieldOf("initial_graves", INITIAL_AS_MINIMUM)
                    .forGetter(GraveSpawnerData::initialGraves),
            Codec.INT.optionalFieldOf("interval", DEFAULT_INTERVAL).forGetter(GraveSpawnerData::interval),
            Codec.INT.optionalFieldOf("min_x", 0).forGetter(GraveSpawnerData::minX),
            Codec.INT.optionalFieldOf("max_x", MAX_X_UNSET).forGetter(GraveSpawnerData::maxX)
    ).apply(i, GraveSpawnerData::new));

    public static final Codec<GraveSpawnerData> CODEC = MAP_CODEC.codec();

    public GraveSpawnerData {
        zombies = zombies == null ? List.of() : List.copyOf(zombies);
        minGraves = Math.max(0, minGraves);
        initialGraves = initialGraves < 0 ? minGraves : initialGraves;
        interval = Math.max(1, interval);
        minX = Math.max(0, minX);
    }

    /** The region a new grave may appear in, clamped to a board {@code width} columns wide. */
    public int maxXFor(int width) {
        int last = Math.max(0, width - 1);
        return maxX == MAX_X_UNSET ? last : Math.min(maxX, last);
    }

    /** How many graves the level opens with. */
    public int initialFor() {
        return Math.max(initialGraves, minGraves);
    }

    /** True when this block can never raise a grave, and is therefore worth reporting. */
    public boolean inert() {
        return minGraves <= 0 && zombies.isEmpty();
    }

    /** One message per problem, for {@code LevelValidator}. */
    public List<String> validate(int width) {
        List<String> errors = new ArrayList<>();
        if (initialGraves < minGraves) {
            errors.add("grave_spawner.initial_graves (" + initialGraves
                    + ") is below min_graves (" + minGraves + "), which can never be reached");
        }
        if (minX > maxXFor(width)) {
            errors.add("grave_spawner has an empty region: min_x " + minX
                    + " is past max_x " + maxXFor(width) + " on a " + width + "-column board");
        }
        if (zombies.isEmpty() && minGraves > 0) {
            errors.add("grave_spawner raises graves but lists no zombies: nothing would ever"
                    + " come out of them");
        }
        return errors;
    }
}
