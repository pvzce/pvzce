package com.pvzce.api.content;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.content.mechanic.MechanicData;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Which rows of a level have a lawn mower.
 *
 * <p>The block of the {@code pvzce:mower} level mechanic. Every ordinary level has one
 * mower per row without saying anything - that is the original's lawn, and a player who
 * loses a row to a single zombie the game never warned them about has been cheated - so
 * this block only ever <em>narrows</em> that:
 *
 * <pre>
 * { "type": "pvzce:mower" }                  // every row (the same as not declaring it)
 * { "type": "pvzce:mower", "rows": [0, 4] }  // only the first and last row
 * { "type": "pvzce:mower", "rows": [] }      // none at all: Wall-nut Bowling
 * </pre>
 *
 * <p>An absent {@code rows} and an empty one mean different things on purpose: absent is
 * "the default, every row", empty is "the player asked for none". A codec cannot tell those
 * apart from a list alone, which is why the field is an {@link Optional}.
 */
public record MowerData(Optional<List<Integer>> rows) implements MechanicData {
    public static final MapCodec<MowerData> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.INT.listOf().optionalFieldOf("rows").forGetter(MowerData::rows)
    ).apply(i, MowerData::new));

    public static final Codec<MowerData> CODEC = MAP_CODEC.codec();

    /** The implicit default: every row of the level has a mower. */
    public static final MowerData EVERY_ROW = new MowerData(Optional.empty());

    public MowerData {
        rows = rows == null ? Optional.empty() : rows.map(List::copyOf);
    }

    /**
     * The rows that have a mower on a board {@code height} rows tall, ascending.
     *
     * <p>An absent list expands to every row here rather than at every use site: "which rows
     * have mowers" is asked by the simulation, the client renderer and the validator, and
     * three expansions of the same rule is how they drift apart.
     */
    public List<Integer> rowsFor(int height) {
        List<Integer> declared = rows.orElse(null);
        if (declared == null) {
            List<Integer> all = new ArrayList<>(Math.max(0, height));
            for (int y = 0; y < height; y++) {
                all.add(y);
            }
            return List.copyOf(all);
        }
        List<Integer> valid = new ArrayList<>(declared.size());
        for (Integer row : declared) {
            if (row != null && row >= 0 && row < height && !valid.contains(row)) {
                valid.add(row);
            }
        }
        return List.copyOf(valid);
    }

    /** True when this block would give the level no mower at all. */
    public boolean none(int height) {
        return rowsFor(height).isEmpty();
    }

    /** One message per row that cannot exist on this board, for {@code LevelValidator}. */
    public List<String> validate(int height) {
        List<String> errors = new ArrayList<>();
        for (Integer row : rows.orElse(List.of())) {
            if (row == null) {
                errors.add("mower.rows contains a null row");
            } else if (row < 0 || row >= height) {
                errors.add("mower.rows lists row " + row + ", which this " + height
                        + "-row board does not have; the mower would never appear");
            }
        }
        return errors;
    }
}
