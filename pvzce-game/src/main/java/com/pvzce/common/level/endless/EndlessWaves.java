package com.pvzce.common.level.endless;

import com.pvzce.api.content.EndlessScheduleDef;
import com.pvzce.api.content.WaveDef;
import com.pvzce.api.util.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * One endless level's wave, generated from its schedule rather than read from a table.
 *
 * <p>The original's Survival Endless is a loop: a handful of wave shapes that repeat and get
 * heavier. This is the same idea with the loop made explicit - a round is a fixed number of
 * waves, and the round number is the only thing the shapes are a function of. Nothing is
 * generated ahead of time: the director asks for "round 7, wave 12" and gets a wave back, so
 * a run that reaches round two hundred costs exactly as much memory as one that reaches round
 * two, and the save file holds a round number instead of a wave table.
 *
 * <p><b>Determinism.</b> The dice are the level's own {@link Random}, so a run reproduced from
 * its seed generates the same waves, and a save taken mid-round resumes into the same ones.
 * That is the property that makes "the table is not saved" safe: the table is derivable, and
 * only the round and the wave inside it have to be written down.
 *
 * <p><b>Row safety.</b> A schedule marks its water-capable zombies, and a generated wave keeps
 * the land zombies on the land rows and the floaters on the water rows. A board with no water
 * rows gets one land entry per zombie and nothing else.
 */
public final class EndlessWaves {
    /** How much heavier a huge wave is than an ordinary one of the same round. */
    public static final float HUGE_COUNT_FACTOR = 1.5F;
    /** How much tighter a huge wave pours, as a fraction of the round's own interval. */
    public static final float HUGE_INTERVAL_FACTOR = 0.6F;
    /** The banner a huge wave shows before it arrives, in ticks. */
    public static final int HUGE_WARNING_TICKS = 600;

    private EndlessWaves() {
    }

    /**
     * The rows of a board split into the two kinds a schedule's zombies care about.
     *
     * <p>Read from the level's own scene rather than assumed: an endless level on the day pool
     * has water in the middle two rows, one on the front lawn has none, and both run the same
     * generator.
     *
     * @param land  the rows an ordinary walker may use
     * @param water the rows a floater may use; empty on a board with no water
     */
    public record Rows(List<Integer> land, List<Integer> water) {
        public Rows {
            land = land == null ? List.of() : List.copyOf(land);
            water = water == null ? List.of() : List.copyOf(water);
        }

        /** Every row is land, which is what a level whose scene has no water answers. */
        public static Rows allLand(int rows) {
            List<Integer> land = new ArrayList<>();
            for (int y = 0; y < Math.max(0, rows); y++) {
                land.add(y);
            }
            return new Rows(land, List.of());
        }
    }

    /**
     * One round of waves, generated as a list.
     *
     * <p>For the callers that want to look at a whole round rather than run it: the level list's
     * preview, the validator, and the tests. The simulation never asks for a round - it asks for
     * one wave at a time - so this is not on any hot path, and a round is at most thirty waves.
     *
     * @param schedule the level's schedule
     * @param round    the round, one-based
     * @param rows     the board's land and water rows
     * @param random   the dice; pass a seeded one for a reproducible round
     */
    public static List<WaveDef> round(EndlessScheduleDef schedule, int round, Rows rows,
                                      Random random) {
        int waves = EndlessRamp.wavesInRound(schedule, Math.max(1, round));
        List<WaveDef> generated = new ArrayList<>(waves);
        for (int index = 0; index < waves; index++) {
            WaveDef wave = wave(schedule, round, index, rows.land(), rows.water(), random);
            if (wave == null) {
                break;
            }
            generated.add(wave);
        }
        return List.copyOf(generated);
    }

    /**
     * The first round a level list entry can show, for a board of this height.
     *
     * <p>Shapes only: the dice are seeded from the level id and the round, so the preview a
     * player browses does not change every time the list is redrawn.
     */
    public static List<WaveDef> preview(EndlessScheduleDef schedule, int height) {
        return round(schedule, 1, Rows.allLand(height), new Random(0L));
    }

    /**
     * The wave an endless level sends at this point in its run.
     *
     * @param schedule the level's schedule
     * @param round    the round, one-based
     * @param index    the wave inside that round, zero-based
     * @param landRows the rows an ordinary walker may use
     * @param waterRows the rows a floater may use; empty on a board with no water
     * @param random   the level's dice
     * @return the wave, or {@code null} when the schedule has nothing it can send (an empty
     *         pool, or a pool whose entries are all still locked)
     */
    public static WaveDef wave(EndlessScheduleDef schedule, int round, int index,
                               List<Integer> landRows, List<Integer> waterRows, Random random) {
        EndlessRamp ramp = EndlessRamp.of(schedule, round);
        boolean huge = ramp.isHugeWave(index);
        int interval = huge
                ? Math.max(EndlessRamp.MIN_SPAWN_INTERVAL,
                        Math.round(ramp.spawnInterval() * HUGE_INTERVAL_FACTOR))
                : ramp.spawnInterval();
        List<WaveDef.Entry> entries = composition(schedule, ramp, huge, landRows, waterRows, random);
        if (entries.isEmpty()) {
            return null;
        }
        int zombies = entries.stream().mapToInt(WaveDef.Entry::count).sum();
        int delay = ramp.delayFor(zombies, interval);
        return huge
                ? WaveDef.declaringSpawnInterval(WaveDef.WaveType.HUGE, delay, HUGE_WARNING_TICKS, entries, interval)
                : WaveDef.declaringSpawnInterval(WaveDef.WaveType.SMALL, delay, 0, entries, interval);
    }

    /**
     * The zombies of one wave, one entry per zombie so each can carry its own stats.
     *
     * <p>Per-zombie entries rather than one entry with a count: the round's health growth has
     * to be readable off the wave (see {@link WaveDef.Entry#healthScale()}), and a water zombie
     * and a land zombie of the same wave are restricted to different rows anyway.
     */
    private static List<WaveDef.Entry> composition(EndlessScheduleDef schedule, EndlessRamp ramp,
                                                   boolean huge, List<Integer> landRows,
                                                   List<Integer> waterRows, Random random) {
        List<EndlessScheduleDef.ZombieEntry> land = schedule.landPoolForRound(ramp.round());
        List<EndlessScheduleDef.ZombieEntry> water =
                waterRows.isEmpty() ? List.of() : schedule.waterPoolForRound(ramp.round());
        int total = ramp.count();
        if (huge) {
            total = Math.max(total + 1, Math.round(total * HUGE_COUNT_FACTOR));
        }
        total = Math.min(EndlessScheduleDef.MAX_COUNT, total);

        // The water share is a fifth of the wave, rounded up, and never more than the count:
        // a pool level's two water rows hold a couple of zombies each, not half the horde.
        int waterCount = water.isEmpty() ? 0 : Math.min(total, Math.max(1, total / 5));
        int landCount = total - waterCount;
        if (land.isEmpty()) {
            // No land zombie is unlocked yet: everything goes to the water rather than nothing
            // arriving at all. Only reachable on a schedule whose first unlocks are floaters.
            landCount = 0;
            waterCount = water.isEmpty() ? 0 : total;
        }

        List<WaveDef.Entry> entries = new ArrayList<>();
        float healthScale = ramp.healthMultiplier();
        addZombies(entries, land, landCount, landRows, healthScale, random);
        addZombies(entries, water, waterCount, waterRows, healthScale, random);
        return entries;
    }

    /** Draws {@code count} zombies out of one pool and appends them as entries. */
    private static void addZombies(List<WaveDef.Entry> entries,
                                   List<EndlessScheduleDef.ZombieEntry> pool, int count,
                                   List<Integer> rows, float healthScale, Random random) {
        if (count <= 0 || pool.isEmpty() || rows.isEmpty()) {
            return;
        }
        // The per-wave caps are consumed as the wave fills, so "at most one gargantuar" holds
        // for the wave rather than for each draw.
        int[] remaining = new int[pool.size()];
        for (int i = 0; i < pool.size(); i++) {
            int cap = pool.get(i).maxPerWave();
            remaining[i] = cap <= 0 ? Integer.MAX_VALUE : cap;
        }
        for (int i = 0; i < count; i++) {
            int picked = pick(pool, remaining, random);
            if (picked < 0) {
                return;
            }
            remaining[picked]--;
            Identifier id = pool.get(picked).zombie();
            entries.add(new WaveDef.Entry(id, 1, rows, healthScale));
        }
    }

    /**
     * One weighted draw, skipping whatever has used up its per-wave allowance.
     *
     * @return the index into {@code pool}, or {@code -1} when every entry is exhausted
     */
    private static int pick(List<EndlessScheduleDef.ZombieEntry> pool, int[] remaining,
                            Random random) {
        int total = 0;
        for (int i = 0; i < pool.size(); i++) {
            if (remaining[i] > 0) {
                total += Math.max(0, pool.get(i).weight());
            }
        }
        if (total <= 0) {
            return -1;
        }
        int roll = random.nextInt(total);
        for (int i = 0; i < pool.size(); i++) {
            if (remaining[i] <= 0) {
                continue;
            }
            roll -= Math.max(0, pool.get(i).weight());
            if (roll < 0) {
                return i;
            }
        }
        return pool.size() - 1;
    }
}
