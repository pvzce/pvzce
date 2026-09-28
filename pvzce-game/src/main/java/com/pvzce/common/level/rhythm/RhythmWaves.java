package com.pvzce.common.level.rhythm;

import com.pvzce.api.content.EndlessScheduleDef;
import com.pvzce.api.content.RhythmChartData;
import com.pvzce.api.content.WaveDef;
import com.pvzce.common.level.endless.EndlessWaves;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The rhythm levels' zombies: one song's worth of waves, generated from the chart's own clock.
 *
 * <p>These levels write no wave table. What comes, how many and how tightly is a function of two
 * things - the shared schedule ({@code EndlessSchedules.rhythmLawn}, which is where the roster and
 * its unlocks live) and the level's one number, the chart's {@code difficulty} - and the clock they
 * are read against is the song's.
 *
 * <h2>The round is a position in the song</h2>
 *
 * <p>The generator underneath ({@link EndlessWaves}) is the endless levels' own, and what makes it
 * serve a three-minute track is the meaning of its "round": here it is
 * <em>progress × ramp × difficulty</em>, not a count of how many waves have gone by. A song a third
 * of the way through at difficulty 1 is at round eleven of the schedule's ramp; the same moment at
 * difficulty 2.2 is at round twenty-four, which is where the footballs are. Nothing else changes:
 * the pool's unlocks, the count, the spawn interval and the health growth are all read off that
 * number, so one coefficient moves the whole curve and the level has nothing else to say.
 *
 * <p><b>Monotonic by construction.</b> The chart's clock only runs forward, so the round only
 * climbs - which is what keeps "the roster only ever gets stronger" true. A wave generated late can
 * never send a weaker pool than one generated early.
 *
 * <h2>What the track is allowed to send</h2>
 *
 * <p>Whatever the schedule's pool holds. This class does not filter it further, and the shipped
 * schedule is the answer to "what fits a lawn played from a keyboard" - see its comment for what
 * was left out and why.
 *
 * <h2>Rows</h2>
 *
 * <p>Handed straight to the endless generator, which keeps water-capable zombies on water rows and
 * everything else off them. The shipped rhythm board has no water, so every wave comes out dry -
 * and a hand-written pool level with a chart would still be safe.
 */
public final class RhythmWaves {
    /**
     * How many waves a rhythm level says one round holds.
     *
     * <p>An accounting detail rather than a game rule: the director walks a round's waves and rolls
     * over, and the rollover is what asks an endless level to re-pick its cards. A round of this
     * many waves covers a track of any length the levels write, and {@code LevelServer} rolls one
     * over silently if it ever happens. The number is small enough that a rollover is cheap, and
     * the level draws no meter, so nothing on screen counts them.
     */
    public static final int WAVES_PER_ROUND = 12;

    /** How many waves of the opening bar a level list entry previews. */
    private static final int PREVIEW_WAVES = 3;

    private RhythmWaves() {
    }

    /**
     * Where in the schedule's ramp the song is, as a round number.
     *
     * <p>The whole difficulty rule, and one line of arithmetic: progress through the chart times
     * the ramp's length times the level's coefficient. The ramp length is the schedule's own
     * {@code count_ramp_rounds}, because that is the axis its pool and its counts were authored
     * against - so a coefficient of 1 walks the schedule exactly once over the song, and one of 2.2
     * has walked it twice over and is into the capped part of every curve by the last bar.
     *
     * @param schedule  the level's schedule; ramp length comes from it
     * @param chartTick the tick of the chart the level is at; negative before it has started
     * @return the round, one-based
     */
    public static int roundFor(EndlessScheduleDef schedule, RhythmChartData chart, int chartTick) {
        if (schedule == null || chart == null || !chart.generatesWaves() || !chart.hasEnd()) {
            return 1;
        }
        double progress = Math.max(0D, Math.min(1D, chartTick / (double) chart.endTick()));
        double ramp = Math.max(1D, schedule.countRampRounds());
        return 1 + (int) Math.round(progress * ramp * chart.difficulty());
    }

    /**
     * The rounds a coefficient walks the ramp in, end to end.
     *
     * <p>What the coefficient <em>means</em>, in one number: the four shipped tiers span roughly
     * eighteen, thirty, forty-five and sixty-six rounds, which is "how far down the schedule's
     * roster the last bar of the song is".
     *
     * @return the rounds the song covers, or 0 when this chart generates no waves
     */
    public static int roundsInSong(RhythmChartData chart, EndlessScheduleDef schedule) {
        if (chart == null || schedule == null || !chart.generatesWaves()) {
            return 0;
        }
        return (int) Math.round(Math.max(1, schedule.countRampRounds()) * chart.difficulty());
    }

    /**
     * What the first bar of the song sends, for a level list entry.
     *
     * <p>Shapes only, and the same shape every time: a preview a player browses must not change
     * every time the list is redrawn, so the dice are seeded from nothing at all. It answers the
     * question the seed chooser asks - "what am I up against on this level" - for a level whose
     * answer is otherwise "whatever the song is at when it reaches you".
     *
     * <p>Always the first bar, at the schedule's own opening round rather than at the level's
     * difficulty: a preview of bar one is the same for every tier, and that is the truth - the
     * tiers differ in where they <em>end</em>, not in what walks in first.
     */
    public static List<WaveDef> preview(EndlessScheduleDef schedule, RhythmChartData chart,
                                        int height) {
        if (schedule == null || chart == null || !chart.generatesWaves()) {
            return List.of();
        }
        List<WaveDef> waves = new ArrayList<>();
        for (int index = 0; index < PREVIEW_WAVES; index++) {
            WaveDef wave = EndlessWaves.wave(schedule, 1, index, landRows(height), List.of(),
                    new Random(0L));
            if (wave == null) {
                break;
            }
            waves.add(wave);
        }
        return List.copyOf(waves);
    }

    /** Every row of a board with no water: what a table-less level's preview is drawn against. */
    private static List<Integer> landRows(int height) {
        List<Integer> rows = new ArrayList<>();
        for (int y = 0; y < Math.max(0, height); y++) {
            rows.add(y);
        }
        return rows;
    }

    /**
     * One wave of the song, at the point the level has reached.
     *
     * <p>Delegates the shaping to the endless generator - the roster for that round, one entry per
     * zombie so each carries the round's health scale, the huge-wave shape and its own interval -
     * and only supplies the round, which is the part that is the rhythm mode's.
     *
     * @param schedule  the level's schedule, resolved by the caller
     * @param chart     the chart being played
     * @param chartTick the tick of the chart the level is at
     * @param index     which wave of the round this is, for the huge-wave pattern
     * @param landRows  the rows an ordinary walker may use
     * @param waterRows the rows a floater may use; empty on a board with no water
     * @param random    the level's dice, so a resumed run generates the same waves
     * @return the wave, or {@code null} when there is nothing to send
     */
    public static WaveDef wave(EndlessScheduleDef schedule, RhythmChartData chart, int chartTick,
                               int index, List<Integer> landRows, List<Integer> waterRows,
                               Random random) {
        if (schedule == null || chart == null || !chart.generatesWaves()) {
            return null;
        }
        int round = roundFor(schedule, chart, chartTick);
        return EndlessWaves.wave(schedule, round, index, landRows, waterRows, random);
    }
}
