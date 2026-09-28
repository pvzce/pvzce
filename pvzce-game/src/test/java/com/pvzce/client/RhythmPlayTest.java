package com.pvzce.client;

import com.pvzce.api.content.RhythmChartData;
import com.pvzce.common.network.packet.RhythmHitC2S;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The client's half of a rhythm level, played against a hand-written chart and nothing else.
 *
 * <p>This class is where the mode's feedback lives - which note a key was for, how well it went,
 * what is still in the air, and what went by unplayed - and all of it is arithmetic on a chart and
 * a clock. Pinning it here is what the film of the finished HUD needed: it showed {@code MISS 127}
 * one frame after the chart started, because the clock this class reads is interpolated between
 * heartbeats and one heartbeat corrected it by nine thousand ticks.
 */
class RhythmPlayTest {
    /** One column of two notes, fifteen ticks to the beat: due on tick 100 and on tick 220. */
    private static RhythmChartData chart() {
        return new RhythmChartData(240D, 100, RhythmChartData.DEFAULT_APPROACH_TICKS,
                RhythmChartData.DEFAULT_PERFECT_SUN, RhythmChartData.DEFAULT_ATTACK_VOLLEYS,
                true, 0D,
                List.of(new RhythmChartData.Lane(RhythmChartData.LaneKind.COL, 2,
                        List.of(0D, 8D))));
    }

    /** The anchor the server sends when the player starts the waves; the charts above count from it. */
    private static final double START = 0D;

    /**
     * The message the server anchors a chart with.
     *
     * <p>One shape for the whole run - the anchor and every number beside it - so a test that only
     * cares where the chart starts still hands over a complete status: an empty bar, no hits and no
     * firepower, which is what every run opens with (see {@code RhythmMechanic.Status}).
     */
    private static com.pvzce.common.level.mechanic.RhythmMechanic.Status anchor(double tick) {
        return new com.pvzce.common.level.mechanic.RhythmMechanic.Status(
                (int) tick, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0);
    }

    /** A playhead the server has anchored, the way the screen's frame loop gets one. */
    private static RhythmPlay started() {
        RhythmPlay play = RhythmPlay.of(chart());
        play.tick(0D, anchor(START));
        return play;
    }

    /** Runs the clock forward one tick at a time, the way a frame loop does. */
    private static void run(RhythmPlay play, double from, double to) {
        for (double tick = from; tick <= to; tick += 1D) {
            play.tick(tick, anchor(START));
        }
    }

    /** A note nobody presses is a miss when its window closes, and it breaks the combo. */
    @Test
    void anUnplayedNoteIsMissedWhenItsWindowCloses() {
        RhythmPlay play = started();
        run(play, 1D, 100D);
        assertNotNull(play.press("col", 2, 100D), "the first note is playable on its tick");
        assertEquals(1, play.combo());

        run(play, 101D, 100D + play.chart().fairTicks());
        assertEquals(0, play.missed(), "a note is missed after its window, not on its last tick");

        run(play, 100D + play.chart().fairTicks() + 1D, 220D + play.chart().fairTicks() + 1D);
        assertEquals(1, play.missed(), "the note nobody played is a miss");
        assertEquals(0, play.combo(), "and the combo is gone");
        assertEquals("MISS", play.visibleVerdict(), "which the player is told about");
    }

    /**
     * A clock correction is not charged to the player.
     *
     * <p>The clock is interpolated between heartbeats, so it can jump by hundreds of ticks between
     * two frames - a server catching up after a stall, or one heartbeat arriving late. Every note a
     * jump skips went by without ever being drawn, so the sweep skips them in silence: the tally
     * must not move, because there was nothing to press.
     */
    @Test
    void aClockJumpIsNotCharged() {
        RhythmPlay play = started();
        play.tick(9000D, anchor(START));
        assertEquals(0, play.missed(), "the notes the jump skipped were never on screen");
        assertEquals(0, play.combo(), "and the combo is not broken by them either");
        assertTrue(play.inFlight().isEmpty(), "and there is nothing left in the air");
    }

    /** A frame that merely passed time still counts its misses. */
    @Test
    void anOrdinaryFrameStillCountsItsMisses() {
        RhythmPlay play = started();
        run(play, 1D, 100D + play.chart().fairTicks() + 1D);
        assertEquals(1, play.missed(), "the first note went by unplayed");
    }

    /** The three windows, and the verdict each one is worth. */
    @Test
    void theWindowsGradeThePress() {
        assertEquals("PERFECT", verdictAt(0D));
        assertEquals("GOOD", verdictAt(6D));
        assertEquals("FAIR", verdictAt(10D));
        RhythmChartData chart = chart();
        assertEquals(3, chart.volleys(RhythmChartData.Grade.PERFECT), "a perfect is three attacks");
        assertEquals(2, chart.volleys(RhythmChartData.Grade.GOOD));
        assertEquals(1, chart.volleys(RhythmChartData.Grade.FAIR));
        assertEquals(0, chart.volleys(RhythmChartData.Grade.MISS), "a miss is none");
    }

    /** Plays the first note this many ticks late, and answers the verdict it produced. */
    private static String verdictAt(double delta) {
        RhythmPlay play = started();
        run(play, 1D, 100D + delta);
        RhythmHitC2S hit = play.press("col", 2, 100D + delta);
        assertNotNull(hit, "a press " + delta + " ticks off the note counts");
        assertEquals((int) delta, hit.perceivedTicks(), "and it reports how far off it was");
        return play.visibleVerdict();
    }

    /**
     * A note flies <em>down</em>: it appears above the board and lands on the judgement line.
     *
     * <p>The direction is one subtraction and it is the one thing a still frame cannot check - a
     * capsule is a capsule whichever way it moves, and the first version of this had it inverted
     * (notes rose out of the keys). Every step of the flight is therefore compared with the one
     * before it, so "up" cannot pass as "down" again.
     */
    @Test
    void aNoteComesDownToTheJudgementLine() {
        int approach = 80;
        double topY = 5.25D;
        double judgeY = 0.68D;
        assertEquals(topY,
                new RhythmPlay.Flight("col", 2, 100, approach).worldY(approach, topY, judgeY),
                0.0001D, "a note that has just appeared is above the board");
        assertEquals(judgeY,
                new RhythmPlay.Flight("col", 2, 100, 0).worldY(approach, topY, judgeY),
                0.0001D, "and a note that is due is on the judgement line");

        double previous = topY;
        for (int ticksAhead = approach - 1; ticksAhead >= 0; ticksAhead--) {
            double y = new RhythmPlay.Flight("col", 2, 100, ticksAhead).worldY(approach, topY, judgeY);
            assertTrue(y < previous, "every tick of the flight is lower than the last: " + ticksAhead);
            previous = y;
        }
    }

    /** A press nowhere near a note is not a note, and costs nothing. */
    @Test
    void aStrayPressCostsNothing() {
        RhythmPlay play = started();
        run(play, 1D, 40D);
        assertNull(play.press("col", 2, 40D), "sixty ticks off the nearest note is not a press");
        assertEquals(0, play.missed(), "and not a miss either: the note keeps its own window");
        assertEquals(0, play.combo());
        assertFalse(play.justPlayed("col", 2));
    }

    /** Before the server says where the chart starts, there is no chart: nothing to play or miss. */
    @Test
    void nothingHappensUntilTheServerAnchorsTheChart() {
        RhythmPlay play = RhythmPlay.of(chart());
        play.tick(200D, null);
        assertFalse(play.started(), "the chart has not begun");
        assertTrue(play.inFlight().isEmpty(), "so nothing is in the air");
        assertNull(play.press("col", 2, 100D), "and a key is not a note");
        assertEquals(0, play.missed(), "and nothing has gone by");

        play.tick(200D, anchor(150D));
        assertTrue(play.started(), "the server's anchor starts it");
        assertTrue(play.visibleVerdict().isEmpty());
    }

    /** A note is in the air for its whole flight, and gone once it has been played. */
    @Test
    void aNoteIsInTheAirForItsFlight() {
        RhythmPlay play = started();
        int approach = play.chart().approachTicks();
        run(play, 1D, 100D - approach - 1D);
        assertTrue(play.inFlight().isEmpty(), "a note is not drawn before its flight begins");

        run(play, 100D - approach, 100D - approach);
        assertEquals(1, play.inFlight().size(), "and it is drawn from the moment the flight does");
        RhythmPlay.Flight flight = play.inFlight().get(0);
        assertEquals("col", flight.laneKind());
        assertEquals(2, flight.laneIndex());
        assertEquals(0D, flight.progress(approach), 0.0001D, "at the top of its flight");
        assertEquals(approach, flight.ticksAhead(), 0.0001D, "with the whole flight left to it");

        run(play, 100D - approach + 1D, 100D);
        assertEquals(1D, play.inFlight().get(0).progress(approach), 0.0001D,
                "and on the line on the tick it is due");
        RhythmHitC2S hit = play.press("col", 2, 100D);
        assertNotNull(hit);
        assertEquals(100, hit.noteTick());
        assertEquals(0, hit.perceivedTicks(), "dead on the beat");
        assertTrue(play.inFlight().isEmpty(), "a played note leaves the air");
    }

    // ------------------------------------------------------------------
    // The keyboard's side: the flash, and the verdict's own timing
    // ------------------------------------------------------------------

    /**
     * A key that answered nothing still lights its lane.
     *
     * <p>The whole point of the flash: a player who mistimed a note and a player whose key never
     * registered see the same nothing, and those two send them to two different places. So the
     * press is acknowledged before the judgement, and a stray one carries no verdict to be drawn in.
     */
    @Test
    void aPressThatAnsweredNothingStillLightsItsLane() {
        RhythmPlay play = started();
        play.tick(1D, anchor(START));

        play.pressed("col", 2);
        assertEquals(1F, play.pressFlash("col", 2), 0.001F, "the lane is fully lit");
        assertNull(play.pressedGrade("col", 2), "and it has no verdict to be drawn in");
        assertEquals(0F, play.pressFlash("col", 3), 0.001F, "the lanes beside it are not");
        assertEquals(0, play.combo(), "and nothing was counted");
    }

    /** A press that did answer something takes the verdict's colour for its flash. */
    @Test
    void aPressThatAnsweredANoteTakesItsVerdictsColour() {
        RhythmPlay play = started();
        run(play, 1D, 100D);
        play.pressed("col", 2);
        assertNotNull(play.press("col", 2, 100D), "dead on the beat");
        assertEquals(RhythmChartData.Grade.PERFECT, play.pressedGrade("col", 2),
                "the flash is the verdict's, so one press is one light");
        assertEquals(1F, play.pressFlash("col", 2), 0.001F);
    }

    /** The verdict lands large and settles, drifts upward and fades at the end of its life. */
    @Test
    void theVerdictPopsSettlesRisesAndFades() {
        RhythmPlay play = started();
        run(play, 1D, 100D);
        assertNotNull(play.press("col", 2, 100D));

        assertEquals(0F, play.verdictProgress(), 0.2F, "just landed: the pop is only starting");
        assertTrue(play.verdictScale() > 1F,
                "it arrives larger than it settles: " + play.verdictScale());
        assertEquals(1F, play.verdictAlpha(), 0.001F, "and fully opaque");
        assertTrue(play.verdictRise() >= 0F, "and it is on or above its resting line");
    }

    /**
     * The tally the HUD draws is the server's, not this side's guess.
     *
     * <p>The two sides grade a press slightly differently by design - the server takes the better of
     * the two clocks - so a line drawn from one of them beside a streak drawn from the other can
     * read as a contradiction. Everything a player reads as "how the run is going" comes from the
     * record below; the counters this side keeps are for the verdict and the flash.
     */
    @Test
    void theTallyDrawnIsTheServers() {
        RhythmPlay play = started();
        run(play, 1D, 100D);
        assertNotNull(play.press("col", 2, 100D), "the client counted a perfect note");
        assertEquals(1, play.perfect(), "this side counted it, for the verdict and the flash");
        assertEquals(0, play.tally().perfect(),
                "but the drawn tally is the server's, and it has not been told yet");

        play.tick(101D, new com.pvzce.common.level.mechanic.RhythmMechanic.Status(
                0, 400, 0, 1, 0, 0, 3, 3, 0, 2, 0));
        assertEquals(0, play.tally().perfect(), "the server says no perfect notes");
        assertEquals(1, play.tally().good());
        assertEquals(3, play.tally().combo());
        assertEquals(400, play.energy(), "and the bar is the server's number too");
        assertEquals(2, play.multiplier());
    }
}
