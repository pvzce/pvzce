package com.pvzce.api.content;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.content.mechanic.MechanicData;
import com.pvzce.api.util.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * Gravestones scattered over part of the lawn when the level starts.
 *
 * <p>The block of the {@code pvzce:grave_field} level mechanic, and the original's night
 * lawns: 2-1 opens with four graves, 2-7 with eleven, 2-10 with thirteen, and they are in a
 * different place every time. They are scenery that happens to block planting - the
 * {@code graves_spawn_night} rule is what opens them, once, at the final wave - so a level
 * using this mechanic and nothing else has an ordinary night level with a cluttered lawn.
 *
 * <pre>
 * { "type": "pvzce:grave_field", "count": 7, "min_x": 4, "max_x": 8 }
 * </pre>
 *
 * <p>Distinct from {@code pvzce:grave_spawner}, which is Whack-a-Zombie: that one raises
 * <em>more</em> graves as the level runs and lets zombies climb out of them. A level may have
 * both, and 2-5 does - this one lays out the opening board, the spawner keeps it up.
 *
 * @param count  how many graves to stand up when the level starts
 * @param minX   first column graves may appear in
 * @param maxX   last column of it, inclusive; -1 (or unwritten) means the level's last column
 * @param region the half of the lawn furthest from the house, as a fraction of the width, used
 *               when {@code min_x} is unwritten. The original scatters them over the right
 *               half of the lawn, and "the right half" is a fraction rather than a column
 *               because a level may be wider than the original's nine
 * @param designs which tombstone drawings to mix, cycled in order. Empty (the default) means
 *               the built-in four; a pack with its own headstones names them here
 */
public record GraveFieldData(int count, int minX, int maxX, float region,
                             List<Identifier> designs) implements MechanicData {
    /** {@code min_x} unwritten: start at {@link #region} of the board's width. */
    public static final int MIN_X_UNSET = -1;
    /** {@code max_x} unwritten: the level's own last column. */
    public static final int MAX_X_UNSET = -1;
    /**
     * Where the original's graves start: the far half of the lawn.
     *
     * <p>Half, not "column four": the original's lawn is nine columns wide and its graves
     * appear in columns four through eight, which is the half away from the house. A wider
     * lawn written by a pack means the same thing by this number and would not by a column.
     */
    public static final float DEFAULT_REGION = 0.5F;

    public static final MapCodec<GraveFieldData> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.INT.optionalFieldOf("count", 0).forGetter(GraveFieldData::count),
            Codec.INT.optionalFieldOf("min_x", MIN_X_UNSET).forGetter(GraveFieldData::minX),
            Codec.INT.optionalFieldOf("max_x", MAX_X_UNSET).forGetter(GraveFieldData::maxX),
            Codec.FLOAT.optionalFieldOf("region", DEFAULT_REGION).forGetter(GraveFieldData::region),
            Identifier.CODEC.listOf().optionalFieldOf("designs", List.of())
                    .forGetter(GraveFieldData::designs)
    ).apply(i, GraveFieldData::new));

    public static final Codec<GraveFieldData> CODEC = MAP_CODEC.codec();

    public GraveFieldData {
        count = Math.max(0, count);
        region = Math.min(1F, Math.max(0F, region));
        designs = designs == null ? List.of() : List.copyOf(designs);
    }

    /** The first column graves may stand in, on a board {@code width} columns wide. */
    public int minXFor(int width) {
        if (minX != MIN_X_UNSET) {
            return Math.min(Math.max(0, minX), Math.max(0, width - 1));
        }
        // Rounded down, so a nine-column lawn starts at four (the original's) and an odd
        // width splits at the middle cell rather than one past it.
        return Math.min((int) Math.floor(width * region), Math.max(0, width - 1));
    }

    /** The last column graves may stand in, on a board {@code width} columns wide. */
    public int maxXFor(int width) {
        int last = Math.max(0, width - 1);
        return maxX == MAX_X_UNSET ? last : Math.min(maxX, last);
    }

    /** How many cells the region holds, which is the most graves that can stand in it. */
    public int capacity(int width, int height) {
        int columns = maxXFor(width) - minXFor(width) + 1;
        return Math.max(0, columns) * Math.max(0, height);
    }

    /** One message per authoring problem, for {@code LevelValidator}. */
    public List<String> validate(int width, int height) {
        List<String> errors = new ArrayList<>();
        if (count <= 0) {
            errors.add("grave_field asks for no graves: the mechanic would do nothing");
        }
        if (capacity(width, height) <= 0) {
            errors.add("grave_field has an empty region: columns " + minXFor(width) + ".."
                    + maxXFor(width) + " on a " + width + "-column board");
        } else if (count > capacity(width, height)) {
            errors.add("grave_field asks for " + count + " graves in a region of only "
                    + capacity(width, height) + " cells, so some would never be placed");
        }
        return errors;
    }
}
