package com.pvzce.client;

import com.pvzce.api.content.RhythmChartData;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.network.packet.RhythmHitC2S;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The rhythm levels, played from this side: which note a key was for, and how well it went.
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
 * than one that is a frame behind.
 */
public final class RhythmPlay {
    /** How long a verdict stays on screen, in nanoseconds. */
    private static final long VERDICT_NANOS = 400_000_000L;

    private final RhythmChartData chart;
    /**
     * The level tick the chart began on, or {@code -1} while the build phase is still up.
     *
     * <p>The client's own answer to the same question the server answers in
     * {@code RhythmMechanic}: the chart starts when the waves do. It is anchored the moment the
     * level stops preparing, which is one packet - one tick - after the server did the same, so the
     * two clocks can differ by a tick. That is well inside the hit window, and it is the price of
     * not putting a number on the wire that the client can already work out.
     */
    private double startTicks = -1D;
    /** Lane key -> the note ticks already pressed, so one note is not sent twice. */
    private final Map<String, Set<Integer>> pressed = new HashMap<>();
    private final Map<String, List<Integer>> ticksByLane = new HashMap<>();

    private String lastVerdict = "";
    private long lastVerdictNanos;
    private int perfect;
    private int good;
    private int combo;
    private int bestCombo;
    /** The lane the last note landed in, for the flash: "row:2" or "col:5". */
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

    private static String key(RhythmChartData.LaneKind kind, int index) {
        return kind.json() + ":" + index;
    }

    /**
     * Plays one lane, at the moment the key arrived.
     *
     * <p>The note is chosen as the nearest one in that lane that has not been pressed and is inside
     * the window - nearest rather than "the next one", because a player who is a beat late should
     * get the note they were late for, not the one after it. Returns the message to send, or
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
        if (best < 0 || bestDelta > chart.goodTicks()) {
            return null;
        }
        already.add(best);
        boolean isPerfect = bestDelta <= chart.perfectTicks();
        if (isPerfect) {
            perfect++;
        } else {
            good++;
        }
        combo++;
        bestCombo = Math.max(bestCombo, combo);
        lastVerdict = isPerfect ? "PERFECT" : "GOOD";
        lastVerdictNanos = System.nanoTime();
        lastLane = laneKind + ":" + laneIndex;
        return new RhythmHitC2S(laneKind, laneIndex, best, (int) Math.round(bestDelta));
    }

    /** The verdict to draw right now, or an empty string. */
    public String visibleVerdict() {
        if (lastVerdict.isEmpty() || System.nanoTime() - lastVerdictNanos > VERDICT_NANOS) {
            return "";
        }
        return lastVerdict;
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
     * Tells the chart where the level's clock has got to, and anchors it when the build phase ends.
     *
     * <p>Called once a frame by the screen. The anchor is taken once and never moved: a level that
     * paused and resumed must not restart its chart, and the server's clock does not.
     */
    public void tick(double levelTicks, boolean preparing) {
        if (startTicks < 0D && !preparing) {
            startTicks = levelTicks;
        }
    }

    /** True once the chart is running; before that the lane keys do nothing at all. */
    public boolean started() {
        return startTicks >= 0D;
    }
}
