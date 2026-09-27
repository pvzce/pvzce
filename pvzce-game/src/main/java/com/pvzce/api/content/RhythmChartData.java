package com.pvzce.api.content;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.content.mechanic.MechanicData;

import java.util.ArrayList;
import java.util.List;

/**
 * A rhythm chart: notes on a grid, played with the keyboard, that make the lawn attack.
 *
 * <p>The mode is one loop. A note is due at a tick the chart works out from the track's BPM; the
 * player presses the key for that note's row or column; a press inside the window counts, and a
 * counted press makes <em>that lane</em> attack - a whole row, or a whole column. That is why the
 * lanes are rows and columns rather than five abstract buttons: the thing the player is playing is
 * the lawn, and a good run is one where the lawn is firing.
 *
 * <h2>Where the judgement happens</h2>
 *
 * <p>The client judges and the server scores, which is the shape the project decided on before any
 * of this was written (see {@code 决策记录.md} Q69). The client knows how far off the beat the
 * key was pressed - it has the frame the key arrived and a smooth clock - and that number is what
 * "perfect" means; the server owns the chart, so it is the one that can say whether the note exists,
 * whether it has already been hit, and whether the press was anywhere near it. A client that lies
 * about its verdict still has to name a note that is due, and can only do that once per note.
 *
 * <h2>The file</h2>
 *
 * <p>A chart is not a separate resource: it is the {@code pvzce:rhythm} block inside a level file,
 * which is what makes "four difficulties" four level files that differ in one list. Notes are
 * <em>beats</em>, not ticks, so a chart survives a BPM change and can be written by hand:
 * <pre>{@code
 * { "type": "pvzce:rhythm", "bpm": 120, "offset_ticks": 120,
 *   "lanes": [ { "kind": "row", "index": 2, "notes": [4, 8, 12] },
 *              { "kind": "col", "index": 5, "notes": [6, 14] } ] }
 * }</pre>
 */
public record RhythmChartData(double bpm, int offsetTicks, int perfectTicks, int goodTicks,
                              int perfectSun, int attackDamage, List<Lane> lanes)
        implements MechanicData {
    /** Where the first beat lands, in ticks. */
    public static final int DEFAULT_OFFSET_TICKS = 240;
    /** Inside this many ticks of the note is a perfect hit. */
    public static final int DEFAULT_PERFECT_TICKS = 4;
    /** Inside this many is a hit at all; outside it is a miss. */
    public static final int DEFAULT_GOOD_TICKS = 9;
    /** The original's reward shape: a perfect note is worth a sun to the player. */
    public static final int DEFAULT_PERFECT_SUN = 1;
    /** What one counted note does to its lane. */
    public static final int DEFAULT_ATTACK_DAMAGE = 300;
    /** Below this the chart is not a chart, and the tick arithmetic divides by nearly nothing. */
    public static final double MIN_BPM = 20D;
    public static final double MAX_BPM = 400D;

    /** {@code "row"} or {@code "col"}: which way the lane runs. */
    public enum LaneKind {
        ROW, COL;

        public String json() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }

        public static LaneKind fromJson(String value) {
            return "col".equalsIgnoreCase(value) || "column".equalsIgnoreCase(value) ? COL : ROW;
        }

        public static final Codec<LaneKind> CODEC =
                Codec.STRING.xmap(LaneKind::fromJson, LaneKind::json);
    }

    /**
     * One lane's timeline.
     *
     * @param kind  whether the lane is a row or a column
     * @param index which row or column, zero-based
     * @param notes beat numbers, ascending; a note may be any real number, so a chart can swing
     */
    public record Lane(LaneKind kind, int index, List<Double> notes) {
        public static final Codec<Lane> CODEC = RecordCodecBuilder.create(i -> i.group(
                LaneKind.CODEC.optionalFieldOf("kind", LaneKind.ROW).forGetter(Lane::kind),
                Codec.INT.fieldOf("index").forGetter(Lane::index),
                Codec.DOUBLE.listOf().optionalFieldOf("notes", List.of()).forGetter(Lane::notes)
        ).apply(i, Lane::new));

        public Lane {
            notes = List.copyOf(notes);
        }
    }

    public static final MapCodec<RhythmChartData> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.DOUBLE.optionalFieldOf("bpm", 120D).forGetter(RhythmChartData::bpm),
            Codec.INT.optionalFieldOf("offset_ticks", DEFAULT_OFFSET_TICKS)
                    .forGetter(RhythmChartData::offsetTicks),
            Codec.INT.optionalFieldOf("perfect_ticks", DEFAULT_PERFECT_TICKS)
                    .forGetter(RhythmChartData::perfectTicks),
            Codec.INT.optionalFieldOf("good_ticks", DEFAULT_GOOD_TICKS)
                    .forGetter(RhythmChartData::goodTicks),
            Codec.INT.optionalFieldOf("perfect_sun", DEFAULT_PERFECT_SUN)
                    .forGetter(RhythmChartData::perfectSun),
            Codec.INT.optionalFieldOf("attack_damage", DEFAULT_ATTACK_DAMAGE)
                    .forGetter(RhythmChartData::attackDamage),
            Lane.CODEC.listOf().optionalFieldOf("lanes", List.of()).forGetter(RhythmChartData::lanes)
    ).apply(i, RhythmChartData::new));

    /** The block as a standalone object; used where a chart is not inside a mechanics list. */
    public static final Codec<RhythmChartData> CODEC = MAP_CODEC.codec();

    public RhythmChartData {
        bpm = Math.max(MIN_BPM, Math.min(MAX_BPM, bpm));
        perfectTicks = Math.max(1, perfectTicks);
        // A good window narrower than the perfect one would make "perfect" unreachable, and an
        // author who wrote it that way meant the other order.
        goodTicks = Math.max(perfectTicks, goodTicks);
        perfectSun = Math.max(0, perfectSun);
        attackDamage = Math.max(0, attackDamage);
        lanes = List.copyOf(lanes);
    }

    /** One beat, in ticks, at this chart's tempo. */
    public double ticksPerBeat() {
        // 60 ticks to the second, and 60/bpm seconds to the beat.
        return 60D * 60D / bpm;
    }

    /** The tick a note falls on. */
    public int tickOf(double beat) {
        return offsetTicks + (int) Math.round(beat * ticksPerBeat());
    }

    /** Every note of one lane, as ticks, in the order they are played. */
    public List<Integer> ticksOf(Lane lane) {
        List<Integer> ticks = new ArrayList<>();
        for (double beat : lane.notes()) {
            ticks.add(tickOf(beat));
        }
        return List.copyOf(ticks);
    }

    /** The last tick anything happens on, or 0 for a chart with no notes. */
    public int lastTick() {
        int last = 0;
        for (Lane lane : lanes) {
            for (int tick : ticksOf(lane)) {
                last = Math.max(last, tick);
            }
        }
        return last;
    }

    /** One message per authoring problem, for {@code LevelMechanic.validate}. */
    public List<String> validate() {
        List<String> errors = new ArrayList<>();
        if (lanes.isEmpty()) {
            errors.add("rhythm chart has no lanes: there would be nothing to play");
        }
        int notes = 0;
        for (Lane lane : lanes) {
            notes += lane.notes().size();
            if (lane.notes().isEmpty()) {
                errors.add("rhythm lane " + lane.kind().json() + " " + lane.index()
                        + " has no notes, so its key does nothing all level");
            }
            if (lane.index() < 0) {
                errors.add("rhythm lane " + lane.kind().json() + " names a negative index");
            }
        }
        if (notes == 0) {
            errors.add("rhythm chart has no notes at all");
        }
        return errors;
    }
}
