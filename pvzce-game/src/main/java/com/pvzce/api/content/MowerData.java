package com.pvzce.api.content;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.content.mechanic.MechanicData;
import com.pvzce.api.util.Identifier;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Which rows of a level have a mower, and what kind each one is.
 *
 * <p>The block of the {@code pvzce:mower} level mechanic. Every ordinary level has one
 * mower per row without saying anything - that is the original's lawn, and a player who
 * loses a row to a single zombie the game never warned them about has been cheated - so
 * this block only ever <em>narrows</em> that:
 *
 * <pre>
 * { "type": "pvzce:mower" }                       // every row, the ordinary mower
 * { "type": "pvzce:mower", "rows": [0, 4] }       // only the first and last row
 * { "type": "pvzce:mower", "rows": [] }           // none at all: Wall-nut Bowling
 * { "type": "pvzce:mower", "rows": [0, 1, 4, 5],
 *   "kinds": [ { "row": 2, "kind": "pvzce:pool_cleaner",
 *                "sound": "pvzce:sfx/ambient/pool_cleaner" },
 *              { "row": 3, "kind": "pvzce:pool_cleaner" } ] }
 * </pre>
 *
 * <p>An absent {@code rows} and an empty one mean different things on purpose: absent is
 * "the default, every row", empty is "the player asked for none". A codec cannot tell those
 * apart from a list alone, which is why the field is an {@link Optional}.
 *
 * <p><strong>Kinds.</strong> A row named in {@code kinds} has a mower of that kind, whether or
 * not {@code rows} also names it - the pool's water rows carry the original's pool cleaner
 * rather than a lawn mower, and a level has to be able to say so without a row appearing
 * twice. A row with no kind gets {@link #DEFAULT_KIND}, and being a content id rather than a
 * boolean it is also what a pack uses to add its own vehicle: nothing in the simulation
 * branches on the kind, and the client draws whichever animation the id names
 * ({@code animations/mechanic/<path>.json}).
 *
 */
public record MowerData(Optional<List<Integer>> rows, List<MowerKind> kinds) implements MechanicData {
    /** The mower a row gets when the level does not say. */
    public static final Identifier DEFAULT_KIND = Identifier.withDefaultNamespace("lawn_mower");

    public static final MapCodec<MowerData> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.INT.listOf().optionalFieldOf("rows").forGetter(MowerData::rows),
            MowerKind.CODEC.listOf().optionalFieldOf("kinds", List.of()).forGetter(MowerData::kinds)
    ).apply(i, MowerData::new));

    public static final Codec<MowerData> CODEC = MAP_CODEC.codec();

    /** The implicit default: every row of the level has a mower. */
    public static final MowerData EVERY_ROW = new MowerData(Optional.empty(), List.of());

    /** Every row, with one kind everywhere - the ordinary lawn. */
    public MowerData(Optional<List<Integer>> rows) {
        this(rows, List.of());
    }

    /**
     * The pool lawn's rig: ordinary mowers on the land rows, another kind in the water ones.
     *
     * <p>A factory rather than letting the caller build the {@link MowerKind} list itself, and it
     * is here for an initialization-order reason rather than a stylistic one: {@link MowerKind} is
     * a nested type whose own {@code CODEC} is read by {@link #MAP_CODEC}, so a caller that touched
     * {@code MowerKind} <em>before</em> anything touched {@code MowerData} would initialize the
     * nested class first, leave {@code CODEC} null, and break every later decode of a
     * {@code mower} block. Reaching the nested type only through the outer class removes the
     * possibility.
     *
     * @param landRows     the rows the default mower stands in
     * @param waterRows    the rows that get {@code waterKind}
     * @param waterKind    the content id the water rows use (a pool cleaner, in the shipped pool)
     * @param waterSound   what starting one plays, or empty for the default
     */
    public static MowerData poolRig(List<Integer> landRows, List<Integer> waterRows,
                                    Identifier waterKind, Optional<Identifier> waterSound) {
        List<MowerKind> kinds = new ArrayList<>();
        for (int row : waterRows) {
            kinds.add(new MowerKind(row, waterKind, waterSound));
        }
        return new MowerData(Optional.of(List.copyOf(landRows)), List.copyOf(kinds));
    }

    public MowerData {
        rows = rows == null ? Optional.empty() : rows.map(List::copyOf);
        kinds = kinds == null ? List.of() : List.copyOf(kinds);
    }

    /**
     * One row's mower: which kind, and what it sounds like when it starts.
     *
     * @param row   the row it waits in
     * @param kind  a content id naming its art ({@code lawn_mower}, {@code pool_cleaner}, ...)
     * @param sound what starting it plays; empty means the ordinary mower's own sound
     */
    public record MowerKind(int row, Identifier kind, Optional<Identifier> sound) {
        public static final Codec<MowerKind> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.INT.fieldOf("row").forGetter(MowerKind::row),
                Identifier.CODEC.optionalFieldOf("kind", DEFAULT_KIND).forGetter(MowerKind::kind),
                Identifier.CODEC.optionalFieldOf("sound").forGetter(MowerKind::sound)
        ).apply(i, MowerKind::new));

        public MowerKind {
            kind = kind == null ? DEFAULT_KIND : kind;
            sound = sound == null ? Optional.empty() : sound;
        }
    }

    /**
     * The rows that have a mower on a board {@code height} rows tall, ascending.
     *
     * <p>An absent list expands to every row here rather than at every use site: "which rows
     * have mowers" is asked by the simulation, the client renderer and the validator, and
     * three expansions of the same rule is how they drift apart. A row that only appears in
     * {@code kinds} counts as having one - that is what naming its kind means.
     */
    public List<Integer> rowsFor(int height) {
        List<Integer> result = new ArrayList<>();
        List<Integer> declared = rows.orElse(null);
        if (declared == null) {
            for (int y = 0; y < height; y++) {
                result.add(y);
            }
        } else {
            for (Integer row : declared) {
                if (row != null && row >= 0 && row < height && !result.contains(row)) {
                    result.add(row);
                }
            }
        }
        for (MowerKind kind : kinds) {
            if (kind.row() >= 0 && kind.row() < height && !result.contains(kind.row())) {
                result.add(kind.row());
            }
        }
        result.sort(Integer::compareTo);
        return List.copyOf(result);
    }

    /**
     * Which kind of mower a row has, and what it sounds like.
     *
     * <p>Later entries win, so a level that names a row twice says what an author reading it
     * top to bottom would expect.
     */
    public MowerKind kindFor(int row) {
        MowerKind found = new MowerKind(row, DEFAULT_KIND, Optional.empty());
        for (MowerKind kind : kinds) {
            if (kind.row() == row) {
                found = kind;
            }
        }
        return found;
    }

    /** Every row's mower as one map, for callers that want the whole rig at once. */
    public Map<Integer, MowerKind> byRow(int height) {
        Map<Integer, MowerKind> result = new LinkedHashMap<>();
        for (int row : rowsFor(height)) {
            result.put(row, kindFor(row));
        }
        return Map.copyOf(result);
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
        for (MowerKind kind : kinds) {
            if (kind.row() < 0 || kind.row() >= height) {
                errors.add("mower.kinds names row " + kind.row() + ", which this " + height
                        + "-row board does not have; that mower would never appear");
            }
        }
        return errors;
    }
}
