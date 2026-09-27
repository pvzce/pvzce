package com.pvzce.common.level;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.pvzce.api.content.RhythmChartData;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceSounds;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;

/**
 * The four rhythm tiers, as data: the chart, the wave table and the level file, from one analysis.
 *
 * <p>One track, one tempo, four densities. The generator lives in {@code common} rather than in the
 * editor because both sides need the same answer: the editor writes the files with it, and the
 * shipped levels were written with it, so "what a tier is" has one definition - the same reason
 * every other piece of content has a single home.
 *
 * <h2>The chart follows the track, not just its tempo</h2>
 *
 * <p>What this used to be was a metronome: notes every {@code 1/density} beats from a BPM the four
 * tiers each invented (100, 110, 125, 140 for one 115-second track), over a level whose music was a
 * different song entirely. Nothing about that could line up, and nothing did - the notes ran at
 * their own speed against a track that was not the one they were written for.
 *
 * <p>What it is now is a chart for one song, and the song is analysed rather than assumed. The
 * tempo and the first beat come from the file (see {@code tools/rhythm_onsets.py}, which measures
 * both and prints the table below), and the table is the track's own accent pattern: one hex digit
 * per quarter beat, 0 for a beat nothing lands on and F for the loudest hits. Notes are placed on
 * the grid slots that really have something in the music, and a tier decides how many of them the
 * player has to play.
 *
 * <p>Everything here is deterministic: no clock, no random source, the same arguments give the same
 * file byte for byte, which is what lets a test pin the shipped levels against the generator.
 *
 * <h2>One level, one way of playing it</h2>
 *
 * <p>Every tier is played on <em>columns</em>, and on the five the keyboard's left hand owns. The
 * mode's picture is a note flying down its own column to the judgement line at the bottom of the
 * board, so the lane has to <em>be</em> a column; a chart that mixed the two shapes would answer to
 * two key layouts at once and could only be hinted in one place. The mechanic still supports row
 * lanes for a hand-written chart, and refuses a level that has both
 * ({@code RhythmMechanic.validate}).
 */
public final class RhythmCharts {
    /**
     * The track the four tiers are written against.
     *
     * <p>An analysis is a fact about one file, so the generator has exactly one: a level written for
     * any other event falls back to a metronome on the beat grid (see {@link #levelJson}).
     */
    public static final String MUSIC = PvzceSounds.MUSIC_ANCIENT_EGYPT_ULTIMATE_BATTLE.toString();
    /** The track's tempo, measured from the file: 110.00 BPM over its whole 346-beat length. */
    public static final double BPM = 110D;
    /**
     * The tick the track's first beat lands on.
     *
     * <p>{@code 0.2385 s} measured from the file's own start, which is fourteen ticks - the music
     * begins a quarter of a second in, and a chart that treated the file's first sample as beat zero
     * would be a fifth of a beat ahead of the drums for the whole song.
     */
    public static final int OFFSET_TICKS = 14;
    /**
     * The first beat a chart may use.
     *
     * <p>The track opens with two bars of intro, and a chart that started on beat one would ask for
     * a keypress a quarter of a second after the player pressed 开始. Beat eight is four and a half
     * seconds in, which is the whole grace period the mode gets.
     */
    public static final double FIRST_BEAT = 8D;
    /**
     * The beat the track's last bar lands on, and the end of the chart.
     *
     * <p>Not the file's length: the audio runs on for three and a half more seconds of silence,
     * which is a run-out rather than music. This is where the song stops, so it is where the level
     * stops - the chart's {@code end_beat}, and the tick the lawn is swept and the run is won.
     */
    public static final double END_BEAT = 340D;
    /**
     * The sun a tier opens with.
     *
     * <p>Half of what the mode shipped with, and the number the levels are generated at: a column
     * costs one card, so five hundred sun buys a full lawn of the cheap plants - the build phase is
     * about <em>where</em> the columns go, not about saving up. See {@code levelJson}'s parameter.
     */
    public static final int DEFAULT_INITIAL_SUN = 500;
    /** Quarter beats to the beat: the resolution the analysis is stored at. */
    private static final int SLOTS_PER_BEAT = 4;
    /**
     * The chart is cut into windows this many beats long, and a window with no note in it gets its
     * strongest beat.
     *
     * <p>Without it the song's breaks - and it has several, some of them two bars long - would be
     * twenty seconds of a level where the keyboard does nothing, which reads as a broken chart
     * rather than as a rest before the next run of notes picks up.
     */
    private static final int FILL_BEATS = 4;
    /** The default offset for a track with no analysis: four seconds of lead-in. */
    private static final int UNANALYSED_OFFSET_TICKS = 240;

    /**
     * One difficulty.
     *
     * @param suffix       the level file's name and the tier's identity
     * @param displayName  what the level list calls it
     * @param subdivision  quarter beats between two grid slots: 4 is one note per beat, 1 is four
     * @param notes        how many of the grid's slots this tier takes, strongest first
     * @param chords       how many of those notes also require a second lane at the same moment
     * @param lanes        the columns this tier's chart plays; every tier plays the keyboard's five
     * @param zombieSpeed  the level's {@code zombie_speed_multiplier}
     * @param spawnCadence the level's {@code zombie_spawn_speed_multiplier}, which <em>divides</em>
     *                     both the gap between waves and the gap between two zombies of one wave
     * @param waves        the wave table, as raw JSON
     */
    public record Tier(String suffix, String displayName, int subdivision, int notes, int chords,
                       List<Integer> lanes, double zombieSpeed, double spawnCadence, String waves) {
    }

    private static final String ROWS_JSON = "[0,1,2,3,4]";

    /**
     * The four wave tables: a horde, scaled by tier.
     *
     * <p>Every entry names all five rows, and counts are written so a wave with five of something
     * puts exactly one in each - the mode's pressure is that a row cannot be left alone, not that
     * one row gets a queue. The delays are written to fill the song: at each tier's own
     * {@code zombie_spawn_speed_multiplier} the table runs about ten thousand ticks, which is the
     * track's 340 beats at 110 BPM, so the chart and the horde end together.
     *
     * <p>The four are a curve on three axes at once - how fast the zombies walk, how fast they
     * arrive, and how many of them there are - because a tier that only changed the note count
     * would be the same level with a busier keyboard. Easy is the old pressure with twice the
     * traffic; expert is a lawn that has to be played nearly perfectly to hold.
     */
    private static final String[] WAVES = {
        "[{\"type\":\"small\",\"delay\":2400,\"spawn_interval\":260,\"entries\":["
                + entry("basic_zombie", 4) + "," + entry("conehead_zombie", 1) + "]},"
                + "{\"type\":\"small\",\"delay\":2300,\"spawn_interval\":240,\"entries\":["
                + entry("basic_zombie", 4) + "," + entry("conehead_zombie", 2) + "]},"
                + "{\"type\":\"small\",\"delay\":2200,\"spawn_interval\":220,\"entries\":["
                + entry("basic_zombie", 3) + "," + entry("conehead_zombie", 2) + ","
                + entry("buckethead_zombie", 1) + "]},"
                + "{\"type\":\"small\",\"delay\":2100,\"spawn_interval\":200,\"entries\":["
                + entry("conehead_zombie", 3) + "," + entry("buckethead_zombie", 2) + "]},"
                + "{\"type\":\"small\",\"delay\":2000,\"spawn_interval\":180,\"entries\":["
                + entry("basic_zombie", 4) + "," + entry("conehead_zombie", 3) + ","
                + entry("buckethead_zombie", 1) + "]},"
                + "{\"type\":\"final\",\"delay\":2000,\"warning_ticks\":750,\"spawn_interval\":160,"
                + "\"entries\":[" + entry("basic_zombie", 4) + "," + entry("conehead_zombie", 3)
                + "," + entry("buckethead_zombie", 2) + "]}]",
        "[{\"type\":\"small\",\"delay\":2000,\"spawn_interval\":240,\"entries\":["
                + entry("basic_zombie", 4) + "," + entry("conehead_zombie", 1) + "]},"
                + "{\"type\":\"small\",\"delay\":2000,\"spawn_interval\":220,\"entries\":["
                + entry("basic_zombie", 4) + "," + entry("conehead_zombie", 2) + "]},"
                + "{\"type\":\"small\",\"delay\":2000,\"spawn_interval\":210,\"entries\":["
                + entry("conehead_zombie", 3) + "," + entry("buckethead_zombie", 2) + "]},"
                + "{\"type\":\"small\",\"delay\":2000,\"spawn_interval\":200,\"entries\":["
                + entry("basic_zombie", 4) + "," + entry("conehead_zombie", 3) + ","
                + entry("buckethead_zombie", 1) + "]},"
                + "{\"type\":\"small\",\"delay\":2000,\"spawn_interval\":190,\"entries\":["
                + entry("conehead_zombie", 4) + "," + entry("buckethead_zombie", 2) + "]},"
                + "{\"type\":\"small\",\"delay\":2000,\"spawn_interval\":180,\"entries\":["
                + entry("basic_zombie", 5) + "," + entry("conehead_zombie", 3) + ","
                + entry("pole_vaulter_zombie", 1) + "]},"
                + "{\"type\":\"small\",\"delay\":2000,\"spawn_interval\":170,\"entries\":["
                + entry("conehead_zombie", 4) + "," + entry("buckethead_zombie", 3) + ","
                + entry("football_zombie", 1) + "]},"
                + "{\"type\":\"final\",\"delay\":2000,\"warning_ticks\":750,\"spawn_interval\":160,"
                + "\"entries\":[" + entry("basic_zombie", 5) + "," + entry("conehead_zombie", 3)
                + "," + entry("buckethead_zombie", 2) + "," + entry("football_zombie", 2) + "]}]",
        "[{\"type\":\"small\",\"delay\":2000,\"spawn_interval\":200,\"entries\":["
                + entry("conehead_zombie", 3) + "," + entry("buckethead_zombie", 1) + "]},"
                + "{\"type\":\"small\",\"delay\":2000,\"spawn_interval\":190,\"entries\":["
                + entry("basic_zombie", 4) + "," + entry("conehead_zombie", 3) + "]},"
                + "{\"type\":\"small\",\"delay\":2000,\"spawn_interval\":180,\"entries\":["
                + entry("conehead_zombie", 4) + "," + entry("buckethead_zombie", 2) + "]},"
                + "{\"type\":\"small\",\"delay\":2000,\"spawn_interval\":175,\"entries\":["
                + entry("basic_zombie", 4) + "," + entry("conehead_zombie", 3) + ","
                + entry("buckethead_zombie", 2) + "]},"
                + "{\"type\":\"small\",\"delay\":2000,\"spawn_interval\":170,\"entries\":["
                + entry("conehead_zombie", 4) + "," + entry("buckethead_zombie", 2) + ","
                + entry("pole_vaulter_zombie", 1) + "]},"
                + "{\"type\":\"small\",\"delay\":2000,\"spawn_interval\":165,\"entries\":["
                + entry("basic_zombie", 5) + "," + entry("conehead_zombie", 3) + ","
                + entry("buckethead_zombie", 2) + "]},"
                + "{\"type\":\"small\",\"delay\":2000,\"spawn_interval\":160,\"entries\":["
                + entry("conehead_zombie", 5) + "," + entry("buckethead_zombie", 3) + ","
                + entry("football_zombie", 1) + "]},"
                + "{\"type\":\"small\",\"delay\":2000,\"spawn_interval\":155,\"entries\":["
                + entry("basic_zombie", 5) + "," + entry("conehead_zombie", 4) + ","
                + entry("buckethead_zombie", 3) + "]},"
                + "{\"type\":\"final\",\"delay\":2000,\"warning_ticks\":750,\"spawn_interval\":150,"
                + "\"entries\":[" + entry("basic_zombie", 6) + "," + entry("conehead_zombie", 4)
                + "," + entry("buckethead_zombie", 3) + "," + entry("football_zombie", 2) + "]}]",
        "[{\"type\":\"small\",\"delay\":2000,\"spawn_interval\":170,\"entries\":["
                + entry("conehead_zombie", 3) + "," + entry("buckethead_zombie", 1) + "]},"
                + "{\"type\":\"small\",\"delay\":2000,\"spawn_interval\":165,\"entries\":["
                + entry("basic_zombie", 4) + "," + entry("conehead_zombie", 3) + ","
                + entry("buckethead_zombie", 1) + "]},"
                + "{\"type\":\"small\",\"delay\":2000,\"spawn_interval\":160,\"entries\":["
                + entry("conehead_zombie", 4) + "," + entry("buckethead_zombie", 2) + ","
                + entry("door_zombie", 1) + "]},"
                + "{\"type\":\"small\",\"delay\":2000,\"spawn_interval\":155,\"entries\":["
                + entry("basic_zombie", 5) + "," + entry("conehead_zombie", 4) + ","
                + entry("buckethead_zombie", 2) + "]},"
                + "{\"type\":\"small\",\"delay\":2000,\"spawn_interval\":150,\"entries\":["
                + entry("conehead_zombie", 5) + "," + entry("buckethead_zombie", 3) + "]},"
                + "{\"type\":\"small\",\"delay\":2000,\"spawn_interval\":145,\"entries\":["
                + entry("basic_zombie", 6) + "," + entry("conehead_zombie", 4) + ","
                + entry("football_zombie", 1) + "]},"
                + "{\"type\":\"small\",\"delay\":2000,\"spawn_interval\":140,\"entries\":["
                + entry("conehead_zombie", 6) + "," + entry("buckethead_zombie", 4) + ","
                + entry("door_zombie", 1) + "]},"
                + "{\"type\":\"small\",\"delay\":2000,\"spawn_interval\":135,\"entries\":["
                + entry("basic_zombie", 6) + "," + entry("conehead_zombie", 5) + ","
                + entry("buckethead_zombie", 3) + "]},"
                + "{\"type\":\"small\",\"delay\":2000,\"spawn_interval\":130,\"entries\":["
                + entry("conehead_zombie", 6) + "," + entry("buckethead_zombie", 4) + ","
                + entry("football_zombie", 2) + "]},"
                + "{\"type\":\"final\",\"delay\":2000,\"warning_ticks\":750,\"spawn_interval\":125,"
                + "\"entries\":[" + entry("basic_zombie", 6) + "," + entry("conehead_zombie", 5)
                + "," + entry("buckethead_zombie", 4) + "," + entry("football_zombie", 3) + "]}]",
    };

    /**
     * The six columns every tier is played on.
     *
     * <p>The lawn's first six, under the six keys the hands rest on: the mode is played at the
     * bottom of the board, and a note has to fly the whole way down its own column to the judgement
     * line for the picture to mean anything - which is why the lanes are the board's own columns
     * rather than six abstract buttons. The other three columns are ordinary lawn: plants there
     * still block and still get eaten, they just have no key.
     */
    private static final List<Integer> LANES = List.of(0, 1, 2, 3, 4, 5);

    /** The four tiers, in the order the level list shows them. */
    public static final Tier[] TIERS = {
        new Tier("easy", "节奏草坪 · 简单", 4, 96, 0, LANES, 2.0D, 1.4D, WAVES[0]),
        new Tier("normal", "节奏草坪 · 普通", 4, 152, 0, LANES, 2.2D, 1.6D, WAVES[1]),
        new Tier("hard", "节奏草坪 · 困难", 2, 300, 16, LANES, 2.4D, 1.8D, WAVES[2]),
        new Tier("expert", "节奏草坪 · 专家", 1, 460, 24, LANES, 2.6D, 2.0D, WAVES[3]),
    };

    private static String entry(String zombie, int count) {
        return "{\"id\":\"pvzce:" + zombie + "\",\"count\":" + count + ",\"rows\":" + ROWS_JSON + "}";
    }

    private RhythmCharts() {
    }

    // ------------------------------------------------------------------
    // The track's own accent pattern
    // ------------------------------------------------------------------

    /**
     * The analysed track's accent pattern: one hex digit per quarter beat from the file's start.
     *
     * <p>Produced by {@code tools/rhythm_onsets.py} - a spectral-flux onset envelope, the tempo and
     * phase fitted over the whole track, then the envelope sampled at every quarter beat and scaled
     * to 0..15. {@code 0} is a slot nothing lands on, {@code F} the loudest hits; the string is the
     * reason the charts can follow the music at all, because the game has no audio analysis of its
     * own to run (see the class javadoc).
     *
     * <p>The digits past {@link #END_BEAT} are cut: they belong to the file's silent run-out, and no
     * chart may use them.
     */
    private static final String ONSETS =
            "7050A07080609160A060B050B0707150A070906090916160A090B5805060C0A090A0"
            + "A050B070C090C0709030C0406183A0907030C3528150A070A050B4812250B070A020"
            + "B05060909040A040C03080000000100010000000100020071041415040307130A050"
            + "B040B040C240D050B050B030A040C040C130C0506130B040C050D0708060A230728"
            + "0E0A07250A220A0B0D0709050B1308080E08090609251A100510000020000010080"
            + "301400C340417040407040B050B050C050C050D050B050D030B050B050B040E040A"
            + "040B040E04060307130A050B040C030C030D040C050D020B040C040C030D1309130"
            + "C040D040B090A040C0407270A080A220B010B0000000000000005000002100000000"
            + "B060B030B060B050C140A04090709040C050F1206040C030B040C030C040C040A050"
            + "E040B050C060C030C05090408040C030C050A020C040A130C050B020B050A050C040"
            + "B040B050B150B05090509040B050A040B030C040A040C050B020C0509060D040B040"
            + "A15071706060805080808030A0616010806070405081704080808030909060408070"
            + "A07040909050A050804090409050A050505090706040C230B150B060B140A4822470"
            + "A0609030B06060B09030B040B0405050202080208010206040304030A04080109120"
            + "6020908240A07030A0309050507040409030A02060604010A0646045606020004000"
            + "70004000510050004000503070004000700040104100B0003000B0308100600050602"
            + "000400080009010B05090008000900040008000D00040074090704090509167316090"
            + "30B040B0593050A0609040907160413180B5844060C0A07070A150C0655070A030C0"
            + "40D0413150B0A080209140B1503160A050A472216090708040B06070908040A040A0"
            + "406080A040A040B050C0809050A0A06050A050000000000000000000000000000003"
            + "00";

    /**
     * The accents of {@code music}, or {@code null} when this generator has no analysis of it.
     *
     * <p>Null is not "silence": it is "no answer", and the chart falls back to a plain beat grid
     * (see {@link #levelJson}) rather than pretending some other song's accents are this one's.
     */
    private static String onsetsOf(String music) {
        return MUSIC.equals(music) ? ONSETS : null;
    }

    private static int slotOf(double beat) {
        return (int) Math.round(beat * SLOTS_PER_BEAT);
    }

    /** One quarter beat's accent, 0..15; a track with no analysis is flat. */
    private static int strength(String onsets, int slot) {
        if (onsets == null) {
            return 1;
        }
        if (slot < 0 || slot >= onsets.length()) {
            return 0;
        }
        return Character.digit(onsets.charAt(slot), 16);
    }

    /**
     * How hard the profiled track hits on this quarter beat, 0..15.
     *
     * <p>The generator's own input, exposed because it is the only way to ask the question the chart
     * claims to answer - "are these notes on the music" - without re-deriving the analysis: a test
     * reads a shipped tier's notes and checks them against this.
     */
    public static int onsetStrength(int slot) {
        return strength(ONSETS, slot);
    }

    // ------------------------------------------------------------------
    // The chart
    // ------------------------------------------------------------------

    /**
     * Where this tier's notes fall, as quarter-beat slots, ascending.
     *
     * <p>Three steps, and each one is a sentence about the music:
     *
     * <ol>
     *   <li><b>The grid.</b> Every {@code subdivision}-th quarter beat between {@link #FIRST_BEAT}
     *       and {@link #END_BEAT}. A tier's subdivision is its floor of difficulty: the easy tier
     *       can only ask for whole beats, the expert one can ask for sixteenths.</li>
     *   <li><b>The accents.</b> The tier's {@code notes} strongest slots of that grid, which is what
     *       makes a chart follow a song instead of a metronome: the notes land where the track
     *       actually hits. Ties break towards the earlier slot so the result is deterministic.</li>
     *   <li><b>The floor.</b> A {@link #FILL_BEATS}-beat window with no note in it gets its
     *       strongest slot, so the breaks do not become dead air.</li>
     * </ol>
     *
     * <p>A track with no analysis has no accents to follow, and its grid is spread evenly instead:
     * taking "the strongest of a flat table" would hand back whichever slots happened to come first
     * and bunched every note into the opening bars.
     */
    private static List<Integer> slots(Tier tier, String onsets) {
        int lo = slotOf(FIRST_BEAT);
        int hi = slotOf(END_BEAT);
        List<Integer> grid = new ArrayList<>();
        for (int slot = lo; slot < hi; slot += tier.subdivision()) {
            grid.add(slot);
        }
        TreeSet<Integer> chosen = new TreeSet<>();
        if (onsets == null) {
            int step = Math.max(1, grid.size() / Math.max(1, tier.notes()));
            for (int i = 0; i < grid.size(); i += step) {
                chosen.add(grid.get(i));
            }
        } else {
            List<Integer> byAccent = new ArrayList<>(grid);
            byAccent.sort(Comparator.<Integer>comparingInt(slot -> -strength(onsets, slot))
                    .thenComparingInt(slot -> slot));
            chosen.addAll(byAccent.subList(0, Math.min(tier.notes(), byAccent.size())));
        }
        int window = FILL_BEATS * SLOTS_PER_BEAT;
        for (int start = lo; start < hi; start += window) {
            int end = Math.min(hi, start + window);
            boolean empty = true;
            int best = -1;
            for (int slot = start; slot < end; slot += tier.subdivision()) {
                if (chosen.contains(slot)) {
                    empty = false;
                    break;
                }
                if (best < 0 || strength(onsets, slot) > strength(onsets, best)) {
                    best = slot;
                }
            }
            if (empty && best >= 0) {
                chosen.add(best);
            }
        }
        return List.copyOf(chosen);
    }

    /**
     * The slots that ask for two keys at once.
     *
     * <p>The strongest of a tier's notes, and only where nothing else is close: a two-key hit is
     * read off the music as a single louder event, so it belongs on the accents that stand alone
     * rather than inside a run, where it would be a wall of keys rather than an accent.
     */
    private static List<Integer> chordSlots(List<Integer> slots, Tier tier, String onsets) {
        if (tier.chords() <= 0) {
            return List.of();
        }
        List<Integer> chords = new ArrayList<>();
        for (int i = 0; i < slots.size() && chords.size() < tier.chords(); i++) {
            int slot = slots.get(i);
            boolean isolated = (i == 0 || slot - slots.get(i - 1) >= tier.subdivision() * 2)
                    && (i + 1 == slots.size()
                            || slots.get(i + 1) - slot >= tier.subdivision() * 2);
            if (!isolated) {
                continue;
            }
            if (strength(onsets, slot) >= 10) {
                chords.add(slot);
            }
        }
        return List.copyOf(chords);
    }

    /**
     * The chart's lanes: one per column this tier plays, notes in beats.
     *
     * <p>Which column a note lands on is a fixed pseudo-random draw rather than a rotation. A
     * rotation would be a pattern the player learns once and then plays from muscle memory - the
     * same five keys in the same order for three minutes - while the draw keeps the hands moving
     * and, on the denser tiers, occasionally asks for the same key twice in a row, which is a real
     * thing a chart does. The seed is the tier's own name, so the file is still reproducible byte
     * for byte.
     *
     * <p>The draw is deliberately independent of the music: the beat the note falls on is the
     * track's, and which column answers it is the level's - that is the whole of what "play the
     * lawn" means here.
     */
    private static List<RhythmChartData.Lane> lanes(Tier tier, String onsets) {
        List<Integer> slots = slots(tier, onsets);
        List<Integer> chords = chordSlots(slots, tier, onsets);
        java.util.Random random = new java.util.Random(tier.suffix().hashCode());
        List<List<Double>> notes = new ArrayList<>();
        for (int i = 0; i < tier.lanes().size(); i++) {
            notes.add(new ArrayList<>());
        }
        for (int slot : slots) {
            int lane = random.nextInt(tier.lanes().size());
            notes.get(lane).add(slot / (double) SLOTS_PER_BEAT);
            if (chords.contains(slot) && tier.lanes().size() > 1) {
                // The companion never lands on the column the first key is: two keys a player
                // reads as one event have to be two different keys, or the "chord" is one press.
                int other = random.nextInt(tier.lanes().size() - 1);
                if (other >= lane) {
                    other++;
                }
                notes.get(other).add(slot / (double) SLOTS_PER_BEAT);
            }
        }
        List<RhythmChartData.Lane> lanes = new ArrayList<>();
        for (int i = 0; i < tier.lanes().size(); i++) {
            lanes.add(new RhythmChartData.Lane(RhythmChartData.LaneKind.COL,
                    tier.lanes().get(i), List.copyOf(notes.get(i))));
        }
        return List.copyOf(lanes);
    }

    // ------------------------------------------------------------------
    // The level file
    // ------------------------------------------------------------------

    /**
     * The whole level file for one tier, as JSON text.
     *
     * @param music the track's sound event; the analysis is looked up by it, and a track this
     *              generator has none for is charted as a plain beat grid at {@code bpm}
     */
    public static String levelJson(Identifier id, String name, String music, double bpm, Tier tier,
                                   int volleys, int perfectSun, int initialSun) {
        String onsets = onsetsOf(music);
        // The analysed track's tempo is not the author's to choose: the notes are written against
        // the file's beat grid, and a different BPM would slide every one of them off the drums.
        double tempo = onsets == null ? bpm : BPM;
        double endBeat = onsets == null ? RhythmChartData.DEFAULT_END_BEAT : END_BEAT;
        JsonObject root = new JsonObject();
        root.addProperty("id", id.toString());
        root.addProperty("name", name);
        root.addProperty("description", "节奏草坪：音符踩在歌的鼓点上，等它飞到底下的判定线再按对应的列键，"
                + "那一列的植物就会替你打——PERFECT 打三次、GOOD 两次、FAIR 一次，按空了是 MISS。"
                + "列键 S D F J K L 对应左起六列，草坪下沿有提示；植物平时不会自己开火。"
                + "PERFECT 会从那一列的植物身上掉一颗阳光。"
                + (onsets == null ? "" : "歌放完就清场，撑到那一刻算你赢。")
                + "这一关不能加速——谱面是按这首歌的拍子写的。");
        root.addProperty("width", 9);
        root.addProperty("height", 5);
        root.add("scene", scene());
        root.add("teams", teams());
        root.addProperty("win_team", "pvzce:plant_team");
        root.add("playable_teams", strings("pvzce:plant_team"));
        root.add("rules", rules(tier));
        root.add("env_vars", new JsonObject());
        root.addProperty("wave_interval_end_multiplier", 1.0D);
        root.add("slots", strings("pvzce:sun", "pvzce:pea_shooter", "pvzce:snow_pea",
                "pvzce:repeater", "pvzce:wall_nut", "pvzce:shovel"));
        root.addProperty("max_seed_slots", 6);
        root.addProperty("seed_screen", false);
        root.addProperty("initial_sun", Math.max(0, initialSun));
        root.add("mechanics", mechanics(tempo, onsets, tier, endBeat, volleys, perfectSun));
        root.add("waves", com.google.gson.JsonParser.parseString(tier.waves()));
        root.add("music", music(music, onsets != null));
        root.addProperty("background", "pvzce:textures/gui/screen/level/background1");
        root.add("hidden_scene_elements", strings("pvzce:grass"));
        root.addProperty("disable_shaders", true);
        root.add("hints", hints(onsets != null));
        root.add("unlock_resources", unlockResources());
        // The level's own auto-pickup rather than the player's buff page: the sun a perfect note
        // drops is the mode's income, and a mode whose income needs clicking while the keyboard is
        // busy would be asking for two hands in two places. `player_choice` stays, so the player
        // still brings whatever buffs they own on top.
        root.add("buffs", strings("pvzce:player_choice", "pvzce:auto_collect"));
        root.add("rewards", rewards());
        return new GsonBuilder().setPrettyPrinting().create().toJson(root);
    }

    private static JsonObject scene() {
        JsonArray cells = new JsonArray();
        for (int y = 0; y < 5; y++) {
            for (int x = 0; x < 9; x++) {
                cells.add(x + "," + y);
            }
        }
        JsonObject scene = new JsonObject();
        scene.add("pvzce:grass", cells);
        return scene;
    }

    private static JsonArray teams() {
        JsonArray teams = new JsonArray();
        teams.add(team("pvzce:plant_team", "植物方", "survive_waves"));
        teams.add(team("pvzce:zombie_team", "僵尸方", "plant_side_lost"));
        return teams;
    }

    private static JsonObject team(String id, String name, String condition) {
        JsonObject team = new JsonObject();
        team.addProperty("id", id);
        team.addProperty("name", name);
        team.addProperty("win_condition", condition);
        return team;
    }

    private static JsonObject rules(Tier tier) {
        JsonObject rules = new JsonObject();
        rules.addProperty("pvzce:day_length", 0);
        rules.addProperty("pvzce:night_length", -1);
        rules.addProperty("pvzce:sun_spawn_interval_min", 0);
        rules.addProperty("pvzce:sun_spawn_interval_max", 0);
        rules.addProperty("pvzce:sun_spawn_initial_ticks", 0);
        rules.addProperty("pvzce:zombie_sun_drop_chance", 0.0D);
        // The horde: the tier's own two numbers, and the reason the wave tables below are written
        // as one shape rather than four. `zombie_speed_multiplier` is how fast the ones on the lawn
        // walk, `zombie_spawn_speed_multiplier` divides the gap between waves and between two
        // zombies of one wave (see `WaveDirector`) - so a tier that raises the second gets its
        // whole table faster without a second copy of it.
        rules.addProperty("pvzce:zombie_speed_multiplier", tier.zombieSpeed());
        rules.addProperty("pvzce:zombie_spawn_speed_multiplier", tier.spawnCadence());
        // No card cooldowns at all. The bar is six cards the player spends a thousand sun on before
        // the first beat, and a recharge in the middle of that would be the level asking the player
        // to stand and wait - which is the one thing a chart written against a clock cannot offer.
        rules.addProperty("pvzce:seed_cooldown_multiplier", 0.0D);
        // One card, one whole column - the price of one. The mode is played with both hands on the
        // keyboard and the eyes on the notes, so a lawn that has to be filled cell by cell is a
        // lawn the player does not have the attention for; and with five lanes and five rows, a
        // column is exactly what one key is about. Occupied cells are skipped, so a lone wall-nut
        // does not cost the player the other four (see `RULE_PLANT_WHOLE_COLUMN`).
        rules.addProperty("pvzce:plant_whole_column", true);
        return rules;
    }

    private static JsonArray mechanics(double bpm, String onsets, Tier tier, double endBeat,
                                       int volleys, int perfectSun) {
        JsonArray mechanics = new JsonArray();
        // The build phase is what makes a chart possible at all: the first note is on the track's
        // eighth beat, and the phase is how the player gets to arrange a lawn before the song and
        // the chart start together (see PreparationData's javadoc).
        JsonObject prep = new JsonObject();
        prep.addProperty("type", "pvzce:preparation");
        prep.addProperty("manual", true);
        prep.addProperty("ticks", 0);
        prep.addProperty("refund", true);
        mechanics.add(prep);

        JsonObject rhythm = new JsonObject();
        rhythm.addProperty("type", "pvzce:rhythm");
        rhythm.addProperty("bpm", bpm);
        rhythm.addProperty("offset_ticks", onsets == null ? UNANALYSED_OFFSET_TICKS : OFFSET_TICKS);
        // The three windows are not written: they are 5%, 10% and 15% of the flight, so the note's
        // picture and the judgement that reads it are the same number (see RhythmChartData).
        rhythm.addProperty("approach_ticks", RhythmChartData.DEFAULT_APPROACH_TICKS);
        rhythm.addProperty("perfect_sun", perfectSun);
        rhythm.addProperty("attack_volleys", volleys);
        // Nothing on this lawn attacks by itself: the keyboard is the only weapon (see
        // RhythmMechanic). Written out rather than left to the default so the file says it.
        rhythm.addProperty("plants_hold_fire", true);
        rhythm.addProperty("end_beat", endBeat);
        JsonArray lanes = new JsonArray();
        for (RhythmChartData.Lane lane : lanes(tier, onsets)) {
            JsonObject laneJson = new JsonObject();
            laneJson.addProperty("kind", lane.kind().json());
            laneJson.addProperty("index", lane.index());
            JsonArray notes = new JsonArray();
            for (double beat : lane.notes()) {
                notes.add(beat);
            }
            laneJson.add("notes", notes);
            lanes.add(laneJson);
        }
        rhythm.add("lanes", lanes);
        mechanics.add(rhythm);
        return mechanics;
    }

    /**
     * The level's music timeline: silence while the player arranges the lawn, then the track.
     *
     * <p>Two cues rather than one, and the second is why {@code MusicCue.Trigger} exists. The song
     * is the chart's clock, so it may only start on the tick the chart does - the tick the player
     * pressed 开始 - which is not a tick a level file can name in advance. The first cue silences
     * the theme the client opened with, so the build phase is quiet and the track has nothing to
     * fade out of.
     *
     * <p>Both fades are zero for the same reason: a fade is a start time that is not the cue's, and
     * a chart cannot wait for one.
     */
    private static JsonObject music(String event, boolean analysed) {
        JsonArray cues = new JsonArray();
        JsonObject silence = new JsonObject();
        silence.addProperty("trigger", "level_start");
        silence.addProperty("at_tick", 0);
        silence.addProperty("track", "background");
        silence.addProperty("stop", true);
        silence.addProperty("fade_seconds", 0.0D);
        cues.add(silence);

        JsonObject song = new JsonObject();
        song.addProperty("trigger", "waves_start");
        song.addProperty("at_tick", 0);
        song.addProperty("track", "background");
        song.addProperty("event", event);
        // No loop: the song's last bar is the level's last tick (the chart's `end_beat`), so a
        // track that came round again would be playing over a run that is already over.
        song.addProperty("loop", !analysed);
        song.addProperty("volume", 0.85D);
        song.addProperty("fade_seconds", 0.0D);
        cues.add(song);

        JsonObject music = new JsonObject();
        music.add("cues", cues);
        return music;
    }

    /**
     * The level's three lessons, one box each.
     *
     * <p>Ten seconds rather than fifteen: the mode's own keys are drawn over the box (the board's
     * bottom row is where both of them live), and a lesson that outstays the first notes is a lesson
     * covering the thing it is about.
     */
    private static final int HINT_TICKS = 600;

    private static JsonArray hints(boolean analysed) {
        JsonArray hints = new JsonArray();
        hints.add(hint("列键 S D F J K L：音符飞到下沿判定线时按，那一列的植物替你打；"
                + "早按或错过都是 MISS", HINT_TICKS));
        hints.add(hint("这一关的植物不会自己开火——不按谱，草坪就是站着挨打", HINT_TICKS));
        hints.add(hint(analysed
                ? "先把阵摆好，再按开始读谱——歌放完就清场"
                : "先把阵摆好，再按开始读谱", HINT_TICKS));
        JsonObject refused = new JsonObject();
        refused.addProperty("trigger", "on_card_refused");
        hints.add(refused);
        return hints;
    }

    private static JsonObject hint(String text, int ticks) {
        JsonObject hint = new JsonObject();
        hint.addProperty("trigger", "on_start");
        hint.addProperty("text", text);
        hint.addProperty("duration_ticks", ticks);
        return hint;
    }

    private static JsonObject unlockResources() {
        JsonObject resources = new JsonObject();
        resources.addProperty("pvzce:sun", true);
        return resources;
    }

    private static JsonArray strings(String... values) {
        JsonArray array = new JsonArray();
        for (String value : values) {
            array.add(value);
        }
        return array;
    }

    private static JsonObject rewards() {
        JsonObject unlock = new JsonObject();
        unlock.addProperty("type", "coins");
        unlock.addProperty("amount", 600);
        JsonArray firstClear = new JsonArray();
        firstClear.add(unlock);
        JsonObject repeat = new JsonObject();
        repeat.addProperty("type", "coins");
        repeat.addProperty("amount", 350);
        JsonArray repeats = new JsonArray();
        repeats.add(repeat);
        JsonObject rewards = new JsonObject();
        rewards.add("first_clear", firstClear);
        rewards.add("repeat", repeats);
        rewards.addProperty("coin_drop", "pvzce:coin_silver");
        rewards.addProperty("coin_drop_chance", 0.25D);
        rewards.addProperty("coin_drop_amount", 1);
        return rewards;
    }
}
