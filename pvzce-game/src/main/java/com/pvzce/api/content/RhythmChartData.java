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
 * player presses the key for that note's column while the note is still flying toward the
 * judgement line; a press inside the window counts, and a counted press makes <em>the plants in
 * that column</em> attack - each of them, its own attack, once per volley the verdict is worth.
 * That is why the lanes are the lawn's columns rather than five abstract buttons: the thing the
 * player is playing is the lawn, and a good run is one where the lawn is firing.
 *
 * <h2>The window is a fraction of the flight, not a number of ticks</h2>
 *
 * <p>{@link #approachTicks()} is how long a note is visible before it is due - the client draws it
 * that far above the judgement line - and the three windows are 5%, 10% and 15% of it
 * ({@link #PERFECT_FRACTION} and friends). That is the whole reason the judgement can be trusted
 * from the picture: at the moment a press is graded PERFECT the note is within a twentieth of its
 * flight of the line, which at any sane flight length is the few pixels a player reads as "on it".
 * An author who wants a tighter or looser mode changes the flight, not the window: one number
 * moves the notes and the judgement together, so they cannot drift apart.
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
 * { "type": "pvzce:rhythm", "bpm": 120, "offset_ticks": 120, "approach_ticks": 80,
 *   "lanes": [ { "kind": "col", "index": 2, "notes": [4, 8, 12] },
 *              { "kind": "col", "index": 4, "notes": [6, 14] } ] }
 * }</pre>
 */
public record RhythmChartData(double bpm, int offsetTicks, int approachTicks, int perfectSun,
                              int attackVolleys, boolean plantsHoldFire, double endBeat,
                              List<Lane> lanes) implements MechanicData {
    /** Where the first beat lands, in ticks. */
    public static final int DEFAULT_OFFSET_TICKS = 240;
    /**
     * How long a note flies toward the judgement line, in ticks.
     *
     * <p>Eighty ticks is a second and a third: long enough to read a note and move a finger,
     * short enough that a dense chart is not a wall of capsules on screen at once.
     */
    public static final int DEFAULT_APPROACH_TICKS = 80;
    /** The flight is clamped to this range, so a chart cannot ask for a window of zero or a minute. */
    public static final int MIN_APPROACH_TICKS = 10;
    public static final int MAX_APPROACH_TICKS = 600;
    /** Inside this share of the flight of the note is a PERFECT; see the class javadoc. */
    public static final double PERFECT_FRACTION = 0.05D;
    /** Ten percent: a GOOD note. */
    public static final double GOOD_FRACTION = 0.10D;
    /** Fifteen percent: a FAIR note, the last one that counts at all. */
    public static final double FAIR_FRACTION = 0.15D;
    /**
     * How much sun a PERFECT note drops, as one entity.
     *
     * <p>Not a number added to the bank: the sun falls out of a plant in the column the player
     * played, and picking it up is the ordinary collection the level's buffs decide. The mode
     * therefore pays in the game's own currency rather than in a counter nobody can see.
     */
    public static final int DEFAULT_PERFECT_SUN = 25;
    /**
     * How many times the column's plants attack on a PERFECT note.
     *
     * <p>One number, and the other two grades are derived from it (see {@link #volleys}): a chart
     * that wants a harsher or gentler reward moves this, and 3 - the shipped value - is the
     * 3/2/1 the mode is described in.
     */
    public static final int DEFAULT_ATTACK_VOLLEYS = 3;
    /**
     * The chart's own end, in beats, or {@code 0} for a chart that simply stops.
     *
     * <p>Not the last note's beat: a song's final note is rarely its final sound. This is where
     * the <em>track</em> ends - the beat the music's last bar lands on - and on a chart written
     * against an analysed track the generator sets it from that analysis.
     *
     * <p>When it is set, reaching it <em>ends the level</em>: the lawn is swept and the plant side
     * wins, whether or not every wave got to finish. That is the mode's ending - the song is the
     * clock, and a player still standing when it runs out has survived it - and it is why the field
     * means more than a full stop. A chart that wants the ordinary "the last wave was shot" ending
     * leaves it at zero.
     */
    public static final double DEFAULT_END_BEAT = 0D;
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
     * How well one note was played, which is also what it is worth.
     *
     * <p>Four states and not three: the mode is described as "three attacks, two, one, or none",
     * and a judgement that could only say "in time or not" would have no way to name the middle
     * two. {@link #MISS} is the fourth, and it is a verdict rather than the absence of one -
     * the note the player never pressed is shown as MISS, on the same line as the others.
     */
    public enum Grade {
        PERFECT, GOOD, FAIR, MISS;

        public String text() {
            return name();
        }
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
            Codec.INT.optionalFieldOf("approach_ticks", DEFAULT_APPROACH_TICKS)
                    .forGetter(RhythmChartData::approachTicks),
            Codec.INT.optionalFieldOf("perfect_sun", DEFAULT_PERFECT_SUN)
                    .forGetter(RhythmChartData::perfectSun),
            Codec.INT.optionalFieldOf("attack_volleys", DEFAULT_ATTACK_VOLLEYS)
                    .forGetter(RhythmChartData::attackVolleys),
            Codec.BOOL.optionalFieldOf("plants_hold_fire", true)
                    .forGetter(RhythmChartData::plantsHoldFire),
            Codec.DOUBLE.optionalFieldOf("end_beat", DEFAULT_END_BEAT)
                    .forGetter(RhythmChartData::endBeat),
            Lane.CODEC.listOf().optionalFieldOf("lanes", List.of()).forGetter(RhythmChartData::lanes)
    ).apply(i, RhythmChartData::new));

    /** The block as a standalone object; used where a chart is not inside a mechanics list. */
    public static final Codec<RhythmChartData> CODEC = MAP_CODEC.codec();

    public RhythmChartData {
        bpm = Math.max(MIN_BPM, Math.min(MAX_BPM, bpm));
        approachTicks = Math.max(MIN_APPROACH_TICKS, Math.min(MAX_APPROACH_TICKS, approachTicks));
        perfectSun = Math.max(0, perfectSun);
        attackVolleys = Math.max(0, attackVolleys);
        endBeat = Math.max(0D, endBeat);
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

    /** How far off the note a press may be and still be a PERFECT, in ticks. */
    public int perfectTicks() {
        return Math.max(1, (int) Math.round(approachTicks * PERFECT_FRACTION));
    }

    /** How far off it may be and still be a GOOD; never narrower than {@link #perfectTicks()}. */
    public int goodTicks() {
        return Math.max(perfectTicks(), (int) Math.round(approachTicks * GOOD_FRACTION));
    }

    /**
     * How far off it may be and count at all.
     *
     * <p>The last window, and the one a note is missed after: a note whose window closed with
     * nobody playing it is a MISS (see {@code RhythmMechanic.tick}).
     */
    public int fairTicks() {
        return Math.max(goodTicks(), (int) Math.round(approachTicks * FAIR_FRACTION));
    }

    /**
     * How well a press this far from the note counts.
     *
     * <p>Asked with a distance, never a signed offset: being early and being late are the same
     * mistake and worth the same attacks. A distance past every window is a MISS, which is what
     * the caller that has no press at all asks for directly.
     */
    public Grade gradeOf(double distanceTicks) {
        double distance = Math.abs(distanceTicks);
        if (distance <= perfectTicks()) {
            return Grade.PERFECT;
        }
        if (distance <= goodTicks()) {
            return Grade.GOOD;
        }
        return distance <= fairTicks() ? Grade.FAIR : Grade.MISS;
    }

    /**
     * How many times each plant in the lane attacks for this verdict.
     *
     * <p>A PERFECT is worth the chart's own {@link #attackVolleys()}, and the two lesser grades
     * are two thirds and one third of it, rounded up and never above the grade before: three
     * attacks become 3/2/1, which is the shape the mode is tuned around. A MISS is worth nothing -
     * that is what makes the last window matter.
     */
    public int volleys(Grade grade) {
        if (grade == null || grade == Grade.MISS) {
            return 0;
        }
        int good = Math.min(attackVolleys, ceilShare(attackVolleys, 2, 3));
        int fair = Math.min(good, ceilShare(attackVolleys, 1, 3));
        return switch (grade) {
            case PERFECT -> attackVolleys;
            case GOOD -> good;
            case FAIR -> fair;
            case MISS -> 0;
        };
    }

    /** {@code value * numerator / denominator}, rounded up, and never negative. */
    private static int ceilShare(int value, int numerator, int denominator) {
        return Math.max(0, (value * numerator + denominator - 1) / denominator);
    }

    /** The last tick anything happens on, or 0 for a chart with no notes. */
    public int lastTick() {
        int last = endTick();
        for (Lane lane : lanes) {
            for (int tick : ticksOf(lane)) {
                last = Math.max(last, tick);
            }
        }
        return last;
    }

    /**
     * The tick the chart ends on, or {@code -1} when it has no end of its own.
     *
     * <p>Distinct from {@link #lastTick()} on purpose: that one answers "when is the last note",
     * which is what a HUD draws, and this one answers "when is the song over", which is what ends
     * the level. A chart with no {@code end_beat} has no answer to the second question, and -1 is
     * how "no answer" is spelled here rather than 0, which is a real tick.
     */
    public int endTick() {
        return endBeat <= 0D ? -1 : tickOf(endBeat);
    }

    /** True when the chart was written with an end of its own (see {@link #DEFAULT_END_BEAT}). */
    public boolean hasEnd() {
        return endBeat > 0D;
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
