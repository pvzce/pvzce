package com.pvzce.client;

import com.pvzce.api.content.RhythmChartData;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.network.packet.RhythmHitC2S;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The rhythm levels, played from this side: which note a key was for, how well it went, and which
 * notes are still on their way down.
 *
 * <p>The judgement is the client's because the timing is: the press arrives with the frame it
 * happened in, and the smooth level clock turns that into a distance from the beat. What the
 * client sends is <em>which note</em> and <em>how far off</em>; the server owns the chart and the
 * score, so a client that lies about the second still has to name a note that is really there
 * (see {@code RhythmMechanic.judge}).
 *
 * <p><b>Nothing here is authoritative.</b> The combo and the verdict shown on screen are the
 * client's own copy for feedback - the number the run is scored on is the server's - and they are
 * deliberately not reconciled tick by tick: a HUD that argued with the scoreboard would be worse
 * than one that is a frame behind. The misses are the same story read the other way: a note whose
 * window closed with no press is counted here so the player can be <em>told</em> (the server counts
 * its own, and the two agree because they are the same arithmetic on the same chart).
 */
public final class RhythmPlay {
    /** How long a verdict stays on screen, in nanoseconds. */
    private static final long VERDICT_NANOS = 400_000_000L;

    private final RhythmChartData chart;
    /**
     * The level tick the chart began on, or {@code -1} until the server has said.
     *
     * <p>Sent by {@code RhythmMechanic} rather than worked out here: see {@link #tick}. Before it
     * arrives the chart has not started - nothing is drawn, nothing is missed and no key is a note,
     * which is the honest picture of a mode whose clock the server owns.
     */
    private double startTicks = -1D;
    /** Lane key -> the note ticks already pressed, so one note is not sent twice. */
    private final Map<String, Set<Integer>> pressed = new HashMap<>();
    /** Lane key -> every note of that lane, in the order they are played. */
    private final Map<String, List<Integer>> ticksByLane = new HashMap<>();
    /**
     * Lane key -> how far into that lane's list the miss sweep has got.
     *
     * <p>A cursor rather than a scan: the notes are ascending, so everything before it has already
     * been played or missed, and the sweep only ever walks forward. It is what makes "did I miss
     * anything while I was looking elsewhere" cost nothing per frame.
     */
    private final Map<String, Integer> swept = new HashMap<>();

    private String lastVerdict = "";
    private RhythmChartData.Grade lastGrade = RhythmChartData.Grade.MISS;
    private long lastVerdictNanos;
    private int perfect;
    private int good;
    private int fair;
    private int missed;
    private int combo;
    private int bestCombo;
    /** The lane the last verdict landed in, for the flash: "col:2". */
    private String lastLane = "";

    private RhythmPlay(RhythmChartData chart) {
        this.chart = chart;
        for (RhythmChartData.Lane lane : chart.lanes()) {
            ticksByLane.put(key(lane.kind(), lane.index()), chart.ticksOf(lane));
        }
    }

    /** The chart this level plays, or {@code null} when it has none. */
    public static RhythmPlay forLevel(ClientLevel level) {
        RhythmChartData chart = level == null ? null
                : level.mechanicData(PvzceIds.MECHANIC_RHYTHM, RhythmChartData.class);
        return chart == null ? null : new RhythmPlay(chart);
    }

    /**
     * The playhead over one chart, with no level behind it.
     *
     * <p>For a test: everything this class does is arithmetic on a chart and a clock, and none of it
     * needs a connection, a renderer or a lawn. The shipped levels' charts are data, so a chart
     * written by hand beside the test is the whole input.
     */
    static RhythmPlay of(RhythmChartData chart) {
        return new RhythmPlay(chart);
    }

    private static String key(RhythmChartData.LaneKind kind, int index) {
        return kind.json() + ":" + index;
    }

    /**
     * One note still on its way down, as the highway draws it.
     *
     * @param laneKind   which way the lane runs; the shipped charts are all {@code "col"}
     * @param laneIndex  which column
     * @param noteTick   the tick it is due on, in chart time
     * @param ticksAhead how many ticks are left before it is due; negative once it is past
     */
    public record Flight(String laneKind, int laneIndex, int noteTick, double ticksAhead) {
        /** How far along its flight this note is, 0 at the top and 1 on the judgement line. */
        public double progress(int approachTicks) {
            return 1D - ticksAhead / Math.max(1, approachTicks);
        }

        /**
         * Where on the board this note is right now, in world cells.
         *
         * <p>The flight is a line from just above the board's top row <em>down</em> to the
         * judgement line. Down, because the lanes are columns and the keys are under them, so
         * "nearly there" has to mean "nearly at the bottom" - and world y grows <em>upward</em> on
         * this board (row 0 is the bottom row on screen), so the direction is this one subtraction
         * and nothing else. It lives beside {@link #progress} because the two have to agree:
         * written the other way round it flies the notes up out of the keys, and a still frame
         * cannot tell the two directions apart - which is how it was written first
         * (see {@code 踩坑清单} 142).
         *
         * @param approachTicks how long the whole flight lasts, in ticks
         * @param topY          where a note appears, in world cells (just above the board)
         * @param judgeY        where the judgement line is, in world cells (in the bottom row)
         */
        public double worldY(int approachTicks, double topY, double judgeY) {
            double along = Math.max(0D, Math.min(1D, progress(approachTicks)));
            return topY - along * (topY - judgeY);
        }
    }

    /**
     * The notes that are on the highway right now, in lane order.
     *
     * <p>Everything from "just spawned" to "just past the line", which is what the screen has to
     * draw and nothing else: a note that has not entered the flight yet is not on screen, and one
     * whose window has closed is already a miss. Empty while the build phase is up.
     */
    public List<Flight> inFlight() {
        if (startTicks < 0D) {
            return List.of();
        }
        double nowTicks = nowTicks();
        List<Flight> flights = new ArrayList<>();
        for (RhythmChartData.Lane lane : chart.lanes()) {
            String laneKey = key(lane.kind(), lane.index());
            Set<Integer> already = pressed.getOrDefault(laneKey, Set.of());
            for (int tick : ticksByLane.getOrDefault(laneKey, List.of())) {
                double ahead = tick - nowTicks;
                if (ahead > chart.approachTicks() || tick + chart.fairTicks() < nowTicks) {
                    continue;
                }
                if (already.contains(tick)) {
                    continue;
                }
                flights.add(new Flight(lane.kind().json(), lane.index(), tick, ahead));
            }
        }
        return List.copyOf(flights);
    }

    /**
     * Plays one lane, at the moment the key arrived.
     *
     * <p>The note is chosen as the nearest one in that lane that has not been pressed and is inside
     * the widest window - nearest rather than "the next one", because a player who is a beat late
     * should get the note they were late for, not the one after it. Returns the message to send, or
     * {@code null} when the press was not near anything (a stray key, which costs nothing).
     */
    public RhythmHitC2S press(String laneKind, int laneIndex, double levelTicks) {
        List<Integer> ticks = ticksByLane.get(laneKind + ":" + laneIndex);
        if (ticks == null || startTicks < 0D) {
            // Still building: the chart has not begun, so a key is not a miss and not a note.
            return null;
        }
        double nowTicks = levelTicks - startTicks;
        Set<Integer> already = pressed.computeIfAbsent(laneKind + ":" + laneIndex,
                ignored -> new HashSet<>());
        int best = -1;
        double bestDelta = Double.MAX_VALUE;
        for (int tick : ticks) {
            if (already.contains(tick)) {
                continue;
            }
            double delta = Math.abs(nowTicks - tick);
            if (delta < bestDelta) {
                bestDelta = delta;
                best = tick;
            }
        }
        if (best < 0 || bestDelta > chart.fairTicks()) {
            return null;
        }
        already.add(best);
        RhythmChartData.Grade grade = chart.gradeOf(bestDelta);
        switch (grade) {
            case PERFECT -> perfect++;
            case GOOD -> good++;
            default -> fair++;
        }
        combo++;
        bestCombo = Math.max(bestCombo, combo);
        show(grade, laneKind + ":" + laneIndex);
        return new RhythmHitC2S(laneKind, laneIndex, best, (int) Math.round(bestDelta));
    }

    /** Puts a verdict on screen for its moment. */
    private void show(RhythmChartData.Grade grade, String lane) {
        lastGrade = grade;
        lastVerdict = grade.text();
        lastVerdictNanos = System.nanoTime();
        lastLane = lane;
    }

    /** The verdict to draw right now, or an empty string. */
    public String visibleVerdict() {
        if (lastVerdict.isEmpty() || System.nanoTime() - lastVerdictNanos > VERDICT_NANOS) {
            return "";
        }
        return lastVerdict;
    }

    /** Which verdict {@link #visibleVerdict()} is, for its colour. */
    public RhythmChartData.Grade visibleGrade() {
        return lastGrade;
    }

    /** True while the lane named by {@code laneKind}/{@code laneIndex} is the one just played. */
    public boolean justPlayed(String laneKind, int laneIndex) {
        return !lastLane.isEmpty() && lastLane.equals(laneKind + ":" + laneIndex)
                && System.nanoTime() - lastVerdictNanos <= VERDICT_NANOS;
    }

    public int perfect() {
        return perfect;
    }

    public int good() {
        return good;
    }

    public int fair() {
        return fair;
    }

    /** Notes whose window closed with nobody playing them, as this side counted them. */
    public int missed() {
        return missed;
    }

    public int combo() {
        return combo;
    }

    public int bestCombo() {
        return bestCombo;
    }

    /** The chart being played; the HUD reads the lanes and the tempo off it. */
    public RhythmChartData chart() {
        return chart;
    }

    /**
     * Tells the chart where the level's clock has got to and where the server anchored it, then
     * sweeps for misses.
     *
     * <p>Called once a frame by the screen. The anchor is the server's number
     * ({@code RhythmMechanic.Start}) and not this side's guess: the chart starts when the player
     * says the waves may, and a client that read that moment off its own mirror of the preparation
     * phase was out by the whole build phase on the levels that have one - which is every rhythm
     * level. Until the number arrives the chart has not started, and a key does nothing.
     *
     * <p>Once taken it never moves: a level that paused and resumed must not restart its chart, and
     * the server's clock does not.
     *
     * @param chartStartTick the level tick the chart counts from, or a negative number while the
     *                       server has not said
     */
    public void tick(double levelTicks, double chartStartTick) {
        lastLevelTicks = levelTicks;
        if (startTicks < 0D && chartStartTick >= 0D) {
            startTicks = chartStartTick;
        }
        if (startTicks < 0D) {
            return;
        }
        sweepMisses(nowTicks());
    }

    /**
     * The level clock as of the last {@link #tick}.
     *
     * <p>Kept so that the two questions the screen asks every frame - "which notes are in the air"
     * and "did anything just go by" - are answered against one clock rather than against whichever
     * number the caller happened to pass, and so that neither of them has to be handed the level
     * again.
     */
    private double lastLevelTicks;

    /** How far into the chart the level's clock has got; negative while the build phase is up. */
    private double nowTicks() {
        return startTicks < 0D ? -1D : lastLevelTicks - startTicks;
    }

    /**
     * Counts the notes whose window has closed with nobody playing them.
     *
     * <p>The mode's other half: the chart does not wait, and a player who is looking at the wrong
     * column has to be told what it cost them. The server counts the same misses for the score; this
     * counter exists so the screen can say MISS at the moment it happens rather than at the end of
     * the level, which is the whole difference between a rhythm game and a quiz.
     *
     * <p><b>A clock correction is not a miss.</b> The clock this reads is
     * {@code ClientLevel.smoothLevelTicks}, which interpolates between heartbeats and is therefore
     * allowed to be corrected - a server that caught up after a stall, or one heartbeat that arrived
     * late, can move it by hundreds of ticks between two frames. Notes swept up by a jump like that
     * were never on screen for the player to press, and charging them would be the mode blaming the
     * player for the network. So a jump wider than any frame is skipped in silence: the cursor moves,
     * the tally does not. This is not hypothetical - the first film of this HUD showed
     * {@code MISS 127} one frame after the chart started, which is every note up to tick nine
     * thousand, and none of them had been drawn.
     */
    private void sweepMisses(double nowTicks) {
        boolean jumped = lastSweptTicks >= 0D && nowTicks - lastSweptTicks > MAX_SWEEP_JUMP_TICKS;
        for (RhythmChartData.Lane lane : chart.lanes()) {
            String laneKey = key(lane.kind(), lane.index());
            List<Integer> ticks = ticksByLane.getOrDefault(laneKey, List.of());
            Set<Integer> already = pressed.getOrDefault(laneKey, Set.of());
            int from = swept.getOrDefault(laneKey, 0);
            while (from < ticks.size() && ticks.get(from) + chart.fairTicks() < nowTicks) {
                int note = ticks.get(from);
                if (!already.contains(note) && !jumped) {
                    missed++;
                    combo = 0;
                    show(RhythmChartData.Grade.MISS, laneKey);
                }
                from++;
            }
            swept.put(laneKey, from);
        }
        lastSweptTicks = nowTicks;
    }

    /** The last clock the miss sweep ran at, so a jump can be told from a frame. */
    private double lastSweptTicks = -1D;
    /**
     * How far the clock may move between two frames before it is read as a correction.
     *
     * <p>Half a second, which is thirty ticks at the level's 60 a second: longer than any frame a
     * player can lose to a hitch and still be playing, and far shorter than the heartbeat's own
     * corrections. A player whose game froze for a second was not able to press, and is not charged
     * for it either.
     */
    private static final double MAX_SWEEP_JUMP_TICKS = 30D;

    /** True once the chart is running; before that the lane keys do nothing at all. */
    public boolean started() {
        return startTicks >= 0D;
    }
}
