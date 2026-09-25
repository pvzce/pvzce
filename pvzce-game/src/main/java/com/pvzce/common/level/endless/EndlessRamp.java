package com.pvzce.common.level.endless;

import com.pvzce.api.content.EndlessScheduleDef;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * The numbers of one endless round, derived from the schedule and the round number.
 *
 * <p>Pure: no registry, no level, no dice. Everything a round's shape depends on is a function
 * of {@code (schedule, round)}, which is what makes "round twenty is thirty waves" a statement
 * a test can pin instead of a property of a four-thousand-entry table nobody can read.
 *
 * <p>The round number is one-based, which is the number the player is shown. Round one is
 * therefore the round a fresh run starts in, and the {@code (round - 1)} in every ramp below is
 * what makes the first round exactly the schedule's starting values.
 *
 * @param round            which round this is, one-based
 * @param waves            how many waves the round holds
 * @param hugeWaves        how many of those waves carry the big-wave banner
 * @param spawnInterval    ticks between two zombies of a wave
 * @param gap              ticks of quiet between two waves, on top of the release time
 * @param count            zombies in one of this round's ordinary waves
 * @param healthMultiplier what this round multiplies a zombie's own health by
 */
public record EndlessRamp(int round, int waves, int hugeWaves, int spawnInterval, int gap,
                          int count, float healthMultiplier) {
    /** The lowest a wave delay may ever fall; below this the meter stops being readable. */
    public static final int MIN_DELAY = 240;
    /** The highest, so a late round does not turn into a staring contest. */
    public static final int MAX_DELAY = 3600;
    /** The fastest a wave may pour: a zombie a second is already a wall. */
    public static final int MIN_SPAWN_INTERVAL = 60;

    /** The ramp for one round of a schedule. */
    public static EndlessRamp of(EndlessScheduleDef schedule, int round) {
        int capped = Math.max(1, round);
        int steps = capped - 1;
        return new EndlessRamp(
                capped,
                wavesInRound(schedule, capped),
                Math.max(1, schedule.hugeWavesPerRound()),
                spawnInterval(schedule, steps),
                gap(schedule, steps),
                count(schedule, steps),
                schedule.statGrowth().healthMultiplier(capped));
    }

    /**
     * Ticks between this wave and the next.
     *
     * <p>The gap alone, deliberately. The director counts a wave's delay from the moment the
     * previous wave <em>finished releasing</em>, so a delay that also carried the release time
     * would be charged twice: a late round, whose waves are both bigger and faster, would then
     * ramp up on two axes at once and the round would grow super-linearly in length. The original
     * ramps by making the gap shorter, which is what {@link #gap} already is.
     *
     * <p>The parameters are kept for the callers and for the clamp: a wave's own pacing is the
     * interval it pours at, and this method is where the two meet.
     */
    public int delayFor(int zombies, int interval) {
        return Math.max(MIN_DELAY, Math.min(MAX_DELAY, gap));
    }

    /**
     * How long a round is: one more than the schedule's base, plus one per round, capped.
     *
     * <p>The base is ten and the first round is eleven because a round's opening waves have to be
     * the ones an ordinary level opens with - the level's first two waves are the ones that pace
     * themselves by the player's kills (see {@code WaveDef.holdUntilDead}) - and the round then
     * needs a body after them before its huge one.
     */
    public static int wavesInRound(EndlessScheduleDef schedule, int round) {
        int base = Math.max(1, schedule.roundWavesBase()) + 1;
        int grown = base + Math.max(0, schedule.roundWavesPerRound()) * Math.max(0, round - 1);
        int max = Math.max(base, schedule.roundWavesMax());
        return Math.min(max, grown);
    }

    /** Whether the wave at this index of the round carries the big-wave banner. */
    public boolean isHugeWave(int index) {
        return hugeWaveIndexes(waves, hugeWaves).contains(index);
    }

    /**
     * Which of a round's waves are huge, ascending.
     *
     * <p>Two properties matter and both are pinned by tests: the last wave of the round is
     * always one of them (a round has to end on a beat), and there are never more of them than
     * the round has waves. The rest are spread evenly, so a thirty-wave round has its banners
     * spaced rather than piled up at the end.
     */
    public static List<Integer> hugeWaveIndexes(int waves, int perRound) {
        int length = Math.max(1, waves);
        int total = Math.max(1, Math.min(length, Math.max(1, perRound)));
        LinkedHashSet<Integer> chosen = new LinkedHashSet<>();
        // Last first, so the "always" half holds even when the even split would have skipped it.
        chosen.add(length - 1);
        for (int i = 1; i < total; i++) {
            // An even split of the round, folded into range and nudged off the last wave, which
            // is already taken: an index at or past it would collide instead of adding a banner.
            int index = (int) Math.round(length * (double) i / total) - 1;
            chosen.add(Math.max(0, index >= length - 1 ? index - 1 : index));
        }
        List<Integer> sorted = new ArrayList<>(chosen);
        Collections.sort(sorted);
        return List.copyOf(sorted);
    }

    // The three ramps, each clamped at both ends so a hand-written schedule cannot produce a
    // wave that arrives instantly or a round nobody could survive.

    private static int spawnInterval(EndlessScheduleDef schedule, int steps) {
        int span = Math.max(0, schedule.spawnIntervalRampRounds());
        int start = Math.max(MIN_SPAWN_INTERVAL, schedule.spawnIntervalStart());
        int end = Math.max(MIN_SPAWN_INTERVAL, schedule.spawnIntervalEnd());
        return lerp(start, end, span, steps);
    }

    private static int gap(EndlessScheduleDef schedule, int steps) {
        int span = Math.max(0, schedule.gapRampRounds());
        int start = Math.max(0, schedule.gapStart());
        int end = Math.max(0, schedule.gapEnd());
        return lerp(start, end, span, steps);
    }

    private static int count(EndlessScheduleDef schedule, int steps) {
        int span = Math.max(1, schedule.countRampRounds());
        int start = Math.max(1, schedule.countStart());
        int end = Math.max(start, schedule.countEnd());
        return Math.min(EndlessScheduleDef.MAX_COUNT, lerp(start, end, span, steps));
    }

    /** Linear interpolation over {@code span} steps, saturating past the end. */
    private static int lerp(int start, int end, int span, int steps) {
        if (steps <= 0) {
            return start;
        }
        if (steps >= span) {
            return end;
        }
        return start + (int) Math.round((end - start) * (steps / (double) span));
    }
}
