package com.pvzce.api.content;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.util.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * How an endless level's waves grow, round after round.
 *
 * <p>Loaded from {@code data/<ns>/endless_schedules/<name>.json}, and named by a level's
 * {@code pvzce:endless} mechanic block. The schedule is the whole difficulty curve: which
 * zombies may appear and from which round, how many of them a wave holds, how tightly they
 * are packed, and how much a zombie's body grows as the rounds go on. A level that wants a
 * different endless does not write a wave table - it points at a different schedule.
 *
 * <p><b>Why this is data and not code.</b> The table is the balance, and balance is what a
 * pack author and a mod both need to reach without touching the generator. A schedule is
 * pure numbers with no behaviour in it, which is exactly the line this project draws between
 * a content definition and a mechanic: the mechanic says "waves are generated", the
 * definition says "these ones".
 *
 * <p><b>Round length.</b> {@code round_waves_base} plus {@code round_waves_per_round} for
 * every round after the first, capped at {@code round_waves_max}. The defaults are the
 * shipped shape: 11 waves in round one, one more each round, thirty from round twenty on.
 *
 * @param roundWavesBase    waves in round one
 * @param roundWavesPerRound how many more waves each following round adds
 * @param roundWavesMax     the cap on a round's length
 * @param hugeWavesPerRound how many of a round's waves get the big-wave banner; the last
 *                          wave of a round is always one of them
 * @param spawnIntervalStart ticks between two zombies of a wave in round one
 * @param spawnIntervalEnd   ticks between two zombies once the ramp is complete
 * @param spawnIntervalRampRounds how many rounds the spawn interval takes to reach its end
 * @param gapStart           ticks of quiet between two waves in round one, on top of the
 *                           previous wave's own release time
 * @param gapEnd             that quiet once the ramp is complete
 * @param gapRampRounds      how many rounds the quiet takes to shrink from start to end
 * @param countRampRounds    how many rounds {@code countEnd} takes to be reached
 * @param countStart         zombies in a round-one wave
 * @param countEnd           zombies in a wave once the count ramp is complete
 * @param statGrowth         how much a zombie's own body grows per round
 * @param pool               the zombie pool, with the round each entry unlocks at
 */
public record EndlessScheduleDef(
        int roundWavesBase,
        int roundWavesPerRound,
        int roundWavesMax,
        int hugeWavesPerRound,
        int spawnIntervalStart,
        int spawnIntervalEnd,
        int spawnIntervalRampRounds,
        int gapStart,
        int gapEnd,
        int gapRampRounds,
        int countRampRounds,
        int countStart,
        int countEnd,
        StatGrowth statGrowth,
        List<ZombieEntry> pool
) {
    /** The shipped shape: eleven waves in round one, thirty at the top. */
    public static final int DEFAULT_ROUND_WAVES_BASE = 10;
    public static final int DEFAULT_ROUND_WAVES_PER_ROUND = 1;
    public static final int DEFAULT_ROUND_WAVES_MAX = 30;
    public static final int DEFAULT_HUGE_WAVES_PER_ROUND = 3;
    /** The pool levels' own pacing, as the starting point of the ramp. */
    public static final int DEFAULT_SPAWN_INTERVAL_START = 420;
    public static final int DEFAULT_SPAWN_INTERVAL_END = 150;
    public static final int DEFAULT_SPAWN_INTERVAL_RAMP_ROUNDS = 20;
    /** The quiet between two waves, which shrinks as the rounds go on. */
    public static final int DEFAULT_GAP_START = 900;
    public static final int DEFAULT_GAP_END = 300;
    public static final int DEFAULT_GAP_RAMP_ROUNDS = 20;
    public static final int DEFAULT_COUNT_RAMP_ROUNDS = 30;
    public static final int DEFAULT_COUNT_START = 3;
    public static final int DEFAULT_COUNT_END = 9;
    /** A guard so a hand-written schedule cannot ask for a wave of two hundred zombies. */
    public static final int MAX_COUNT = 30;

    /**
     * How much a zombie's body grows per round.
     *
     * <p>Health only, and deliberately mild. The type unlock in {@link ZombieEntry#fromRound()}
     * is where the difficulty comes from; this is the slow background pressure on top of it,
     * and a schedule that sets it to zero is a legitimate choice.
     *
     * @param healthPerRound extra health per round, as a fraction of the definition's own
     * @param healthCap      the most that extra may ever amount to, as a multiplier
     */
    public record StatGrowth(float healthPerRound, float healthCap) {
        /** The shipped growth: five percent a round, never more than double. */
        public static final StatGrowth DEFAULT = new StatGrowth(0.05F, 2F);

        public static final Codec<StatGrowth> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.FLOAT.optionalFieldOf("health_per_round", DEFAULT.healthPerRound())
                        .forGetter(StatGrowth::healthPerRound),
                Codec.FLOAT.optionalFieldOf("health_cap", DEFAULT.healthCap())
                        .forGetter(StatGrowth::healthCap)
        ).apply(i, StatGrowth::new));

        /** The health multiplier a zombie of this round is spawned with, never below one. */
        public float healthMultiplier(int round) {
            float growth = Math.max(0F, healthPerRound) * Math.max(0, round - 1);
            float cap = Math.max(1F, healthCap);
            return Math.min(cap, 1F + growth);
        }
    }

    /**
     * One zombie the schedule may send, and the round it starts appearing in.
     *
     * <p>{@code water} is a statement about the zombie, not about the wave: a ducky-tube
     * zombie may only walk the pool's water rows, and an ordinary walker sent into those rows
     * drowns. A schedule for a board with no water leaves every entry dry, and a schedule for
     * a pool has to provide at least one floater or the water rows stay empty.
     *
     * @param zombie      the zombie's content id
     * @param fromRound   the first round it may appear in, one-based
     * @param weight      its share of the draw; zero excludes it without deleting the line
     * @param water       whether it may only use the board's water rows
     * @param maxPerWave  how many of it one wave may hold, or zero for no limit
     */
    public record ZombieEntry(Identifier zombie, int fromRound, int weight, boolean water,
                              int maxPerWave) {
        public static final Codec<ZombieEntry> CODEC = RecordCodecBuilder.create(i -> i.group(
                Identifier.CODEC.fieldOf("zombie").forGetter(ZombieEntry::zombie),
                Codec.INT.optionalFieldOf("from_round", 1).forGetter(ZombieEntry::fromRound),
                Codec.INT.optionalFieldOf("weight", 10).forGetter(ZombieEntry::weight),
                Codec.BOOL.optionalFieldOf("water", false).forGetter(ZombieEntry::water),
                Codec.INT.optionalFieldOf("max_per_wave", 0).forGetter(ZombieEntry::maxPerWave)
        ).apply(i, ZombieEntry::new));

        /** An ordinary land zombie, unlocked at round one. */
        public ZombieEntry(Identifier zombie, int fromRound, int weight) {
            this(zombie, fromRound, weight, false, 0);
        }
    }

    public static final Codec<EndlessScheduleDef> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.optionalFieldOf("round_waves_base", DEFAULT_ROUND_WAVES_BASE)
                    .forGetter(EndlessScheduleDef::roundWavesBase),
            Codec.INT.optionalFieldOf("round_waves_per_round", DEFAULT_ROUND_WAVES_PER_ROUND)
                    .forGetter(EndlessScheduleDef::roundWavesPerRound),
            Codec.INT.optionalFieldOf("round_waves_max", DEFAULT_ROUND_WAVES_MAX)
                    .forGetter(EndlessScheduleDef::roundWavesMax),
            Codec.INT.optionalFieldOf("huge_waves_per_round", DEFAULT_HUGE_WAVES_PER_ROUND)
                    .forGetter(EndlessScheduleDef::hugeWavesPerRound),
            Codec.INT.optionalFieldOf("spawn_interval_start", DEFAULT_SPAWN_INTERVAL_START)
                    .forGetter(EndlessScheduleDef::spawnIntervalStart),
            Codec.INT.optionalFieldOf("spawn_interval_end", DEFAULT_SPAWN_INTERVAL_END)
                    .forGetter(EndlessScheduleDef::spawnIntervalEnd),
            Codec.INT.optionalFieldOf("spawn_interval_ramp_rounds", DEFAULT_SPAWN_INTERVAL_RAMP_ROUNDS)
                    .forGetter(EndlessScheduleDef::spawnIntervalRampRounds),
            Codec.INT.optionalFieldOf("gap_start", DEFAULT_GAP_START)
                    .forGetter(EndlessScheduleDef::gapStart),
            Codec.INT.optionalFieldOf("gap_end", DEFAULT_GAP_END)
                    .forGetter(EndlessScheduleDef::gapEnd),
            Codec.INT.optionalFieldOf("gap_ramp_rounds", DEFAULT_GAP_RAMP_ROUNDS)
                    .forGetter(EndlessScheduleDef::gapRampRounds),
            Codec.INT.optionalFieldOf("count_ramp_rounds", DEFAULT_COUNT_RAMP_ROUNDS)
                    .forGetter(EndlessScheduleDef::countRampRounds),
            Codec.INT.optionalFieldOf("count_start", DEFAULT_COUNT_START)
                    .forGetter(EndlessScheduleDef::countStart),
            Codec.INT.optionalFieldOf("count_end", DEFAULT_COUNT_END)
                    .forGetter(EndlessScheduleDef::countEnd),
            StatGrowth.CODEC.optionalFieldOf("stat_growth", StatGrowth.DEFAULT)
                    .forGetter(EndlessScheduleDef::statGrowth),
            ZombieEntry.CODEC.listOf().optionalFieldOf("pool", List.of())
                    .forGetter(EndlessScheduleDef::pool)
    ).apply(i, EndlessScheduleDef::new));

    public EndlessScheduleDef {
        pool = pool == null ? List.of() : List.copyOf(pool);
        statGrowth = statGrowth == null ? StatGrowth.DEFAULT : statGrowth;
    }

    /** The pool entries that may appear in this round and carry a non-zero weight. */
    public List<ZombieEntry> poolForRound(int round) {
        List<ZombieEntry> available = new ArrayList<>();
        for (ZombieEntry entry : pool) {
            if (entry.weight() > 0 && round >= Math.max(1, entry.fromRound())) {
                available.add(entry);
            }
        }
        return List.copyOf(available);
    }

    /** The entries that may use the water rows this round. */
    public List<ZombieEntry> waterPoolForRound(int round) {
        return poolForRound(round).stream().filter(ZombieEntry::water).toList();
    }

    /** The entries that may use the land rows this round. */
    public List<ZombieEntry> landPoolForRound(int round) {
        return poolForRound(round).stream().filter(entry -> !entry.water()).toList();
    }
}
