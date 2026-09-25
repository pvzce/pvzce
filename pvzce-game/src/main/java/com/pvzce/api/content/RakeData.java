package com.pvzce.api.content;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.content.mechanic.MechanicData;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The rake: a one-shot guard that flattens the first zombie to walk into it.
 *
 * <p>The original's shop item, and this one: a rake lies on the lawn a little way out from the
 * house, the first zombie to reach it is destroyed, and the rake is spent. It buys one zombie's
 * worth of time in one lane - the cheapest way to survive a bad opening.
 *
 * <p>The block of the {@code pvzce:rake} level mechanic, which is <b>implicit</b>: a world whose
 * profile owns the rake gets one on every level that does not declare this mechanic, placed at
 * random. A level that wants to decide for itself declares the block, and a level that wants no
 * rake at all writes {@code "rows": []} - the same three-way shape {@code MowerData} uses, and for
 * the same reason.
 *
 * @param rows which lanes a rake may appear in; empty means "one random lane", and an entry that
 *             is present but empty ({@code "rows": []}, or {@link #NONE}) means "nowhere"
 */
public record RakeData(Optional<List<Integer>> rows) implements MechanicData {
    /** Absent rows: one rake, in a lane the level picks at random. */
    public static final RakeData RANDOM = new RakeData(Optional.empty());
    /**
     * Explicitly nowhere.
     *
     * <p>A present-but-empty list, which is how a level says "not this one" and is deliberately
     * distinguishable from {@link #RANDOM}: "no answer" and "the answer is none" are different
     * statements and a record that folded them would take the choice away.
     */
    public static final RakeData NONE = new RakeData(Optional.of(List.of()));

    public static final MapCodec<RakeData> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.INT.listOf().optionalFieldOf("rows").forGetter(RakeData::rows)
    ).apply(i, RakeData::new));

    public static final Codec<RakeData> CODEC = MAP_CODEC.codec();

    public RakeData {
        rows = rows == null ? Optional.empty() : rows;
    }

    /** The lanes a rake may use on a board this tall, or an empty list for "nowhere". */
    public List<Integer> rowsFor(int height) {
        if (rows.isEmpty()) {
            List<Integer> all = new ArrayList<>(Math.max(0, height));
            for (int y = 0; y < height; y++) {
                all.add(y);
            }
            return all;
        }
        List<Integer> lanes = new ArrayList<>();
        for (int row : rows.get()) {
            if (row >= 0 && row < height) {
                lanes.add(row);
            }
        }
        return lanes;
    }

    /** One message per problem, for {@code LevelValidator}. */
    public List<String> validate(int height) {
        List<String> errors = new ArrayList<>();
        if (rows.isPresent()) {
            for (int row : rows.get()) {
                if (row < 0 || row >= height) {
                    errors.add("rake names row " + row + " on a " + height + "-row board");
                }
            }
        }
        return errors;
    }
}
