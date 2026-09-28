package com.pvzce.common.level.endless;

import com.pvzce.api.content.EndlessScheduleDef;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.core.BuiltInRegistries;

import java.util.List;

/**
 * The two endless schedules the shipped levels run on.
 *
 * <p>Code-defined, exactly like the levels that name them. The schedules <em>can</em> be written
 * as data ({@code data/<ns>/endless_schedules/<name>.json}) and a pack that ships one overrides
 * these by id - but the shipped levels are code too, so their schedule has to exist before any
 * pack is read or those levels would name something that is not there.
 *
 * <p>What separates the two is how hard they are meant to be, not what they can express:
 *
 * <ul>
 *   <li>{@code pool_endless} is the original's Survival Endless - the day pool, unlocking one
 *       tier of zombie at a time, and the ramp is the whole difficulty;</li>
 *   <li>{@code mutation_endless} is the same board with the mutation system switched on, so it
 *       starts harder (footballs and doors arrive sooner) to keep up with four levels that
 *       differ only by how often they mutate.</li>
 * </ul>
 */
public final class EndlessSchedules {
    private EndlessSchedules() {
    }

    /** Registers both built-ins; called from {@code BuiltInRegistries.bootstrap()}. */
    public static void bootstrap() {
        BuiltInRegistries.registerStatic(BuiltInRegistries.ENDLESS_SCHEDULES,
                PvzceIds.ENDLESS_SCHEDULE_POOL.toString(), poolEndless());
        BuiltInRegistries.registerStatic(BuiltInRegistries.ENDLESS_SCHEDULES,
                PvzceIds.ENDLESS_SCHEDULE_MUTATION.toString(), mutationEndless());
        BuiltInRegistries.registerStatic(BuiltInRegistries.ENDLESS_SCHEDULES,
                PvzceIds.ENDLESS_SCHEDULE_RHYTHM.toString(), rhythmLawn());
    }

    /**
     * The original's Survival Endless on the day pool.
     *
     * <p>The unlocks are the difficulty curve and they are ordered the way the adventure levels
     * introduce the same zombies, minus the tiers that need a different board: a buckethead at
     * round three, the first armoured-but-fast zombie at five, the first one that ignores the
     * front of the lawn at seven, and the Gargantuar at fifteen - one of those per wave at most,
     * because two of them in one wave is not a harder wave, it is a different level.
     */
    public static EndlessScheduleDef poolEndless() {
        List<EndlessScheduleDef.ZombieEntry> pool = List.of(
                entry("basic_zombie", 1, 10),
                entry("conehead_zombie", 1, 8),
                entry("flag_zombie", 1, 2),
                entry("buckethead_zombie", 3, 7),
                entry("newspaper_zombie", 5, 5),
                entry("pole_vaulter_zombie", 7, 5),
                entry("football_zombie", 9, 5),
                entry("door_zombie", 11, 5),
                entry("gargantuar", 15, 3, 1),
                water("ducky_tube_zombie", 1, 8),
                water("ducky_tube_conehead_zombie", 3, 6),
                water("ducky_tube_buckethead_zombie", 6, 5));
        return new EndlessScheduleDef(
                EndlessScheduleDef.DEFAULT_ROUND_WAVES_BASE,
                EndlessScheduleDef.DEFAULT_ROUND_WAVES_PER_ROUND,
                EndlessScheduleDef.DEFAULT_ROUND_WAVES_MAX,
                EndlessScheduleDef.DEFAULT_HUGE_WAVES_PER_ROUND,
                EndlessScheduleDef.DEFAULT_SPAWN_INTERVAL_START,
                EndlessScheduleDef.DEFAULT_SPAWN_INTERVAL_END,
                EndlessScheduleDef.DEFAULT_SPAWN_INTERVAL_RAMP_ROUNDS,
                EndlessScheduleDef.DEFAULT_GAP_START,
                EndlessScheduleDef.DEFAULT_GAP_END,
                EndlessScheduleDef.DEFAULT_GAP_RAMP_ROUNDS,
                EndlessScheduleDef.DEFAULT_COUNT_RAMP_ROUNDS,
                EndlessScheduleDef.DEFAULT_COUNT_START,
                EndlessScheduleDef.DEFAULT_COUNT_END,
                EndlessScheduleDef.StatGrowth.DEFAULT,
                pool);
    }

    /**
     * The same, for the four mutation levels.
     *
     * <p>Faster and heavier from the start: the mutations are already rewriting the rules by the
     * second minute, and a wave table that opened as gently as the plain endless would spend the
     * first ten rounds being irrelevant. The pool is also shallower - the mutation catalogue is
     * where this mode's variety is supposed to come from.
     */
    public static EndlessScheduleDef mutationEndless() {
        List<EndlessScheduleDef.ZombieEntry> pool = List.of(
                entry("basic_zombie", 1, 10),
                entry("conehead_zombie", 1, 8),
                entry("flag_zombie", 1, 3),
                entry("buckethead_zombie", 2, 8),
                entry("pole_vaulter_zombie", 4, 5),
                entry("football_zombie", 6, 5),
                entry("door_zombie", 8, 5),
                entry("newspaper_zombie", 10, 5),
                entry("gargantuar", 12, 4, 1),
                water("ducky_tube_zombie", 1, 8),
                water("ducky_tube_conehead_zombie", 2, 6),
                water("ducky_tube_buckethead_zombie", 4, 5));
        return new EndlessScheduleDef(
                EndlessScheduleDef.DEFAULT_ROUND_WAVES_BASE,
                EndlessScheduleDef.DEFAULT_ROUND_WAVES_PER_ROUND,
                EndlessScheduleDef.DEFAULT_ROUND_WAVES_MAX,
                EndlessScheduleDef.DEFAULT_HUGE_WAVES_PER_ROUND,
                EndlessScheduleDef.DEFAULT_SPAWN_INTERVAL_START,
                EndlessScheduleDef.DEFAULT_SPAWN_INTERVAL_END,
                EndlessScheduleDef.DEFAULT_SPAWN_INTERVAL_RAMP_ROUNDS,
                EndlessScheduleDef.DEFAULT_GAP_START / 2,
                EndlessScheduleDef.DEFAULT_GAP_END,
                EndlessScheduleDef.DEFAULT_GAP_RAMP_ROUNDS,
                EndlessScheduleDef.DEFAULT_COUNT_RAMP_ROUNDS,
                EndlessScheduleDef.DEFAULT_COUNT_START + 1,
                EndlessScheduleDef.DEFAULT_COUNT_END,
                EndlessScheduleDef.StatGrowth.DEFAULT,
                pool);
    }

    /**
     * The rhythm levels' zombie clock: one song's worth of ramp, walked by the coefficient.
     *
     * <p>Not a round-based endless at all, although it is the same record and the same generator -
     * the difference is what the "round" means. On the four endless levels a round is a stretch of
     * a run that never ends; here the round is <em>where in the song</em> the player is, computed
     * from the chart's own clock times the level's difficulty coefficient (see
     * {@code RhythmWaves.roundFor}). So the ramp below is a shape over one track rather than a
     * curve that keeps climbing: it opens on plain walkers and ends a three-minute song with the
     * armoured half of the roster, and the coefficient only decides how fast it gets there.
     *
     * <p>The unlocks are deliberately the adventure campaign's order minus everything that needs a
     * different board or a different game: no Gargantuar (a wall the player cannot answer with the
     * keyboard), no digger or balloon (a lane the judgement line does not cover), no floaters (the
     * rhythm board is a lawn). What is left is a curve a player can read off the music.
     */
    public static EndlessScheduleDef rhythmLawn() {
        List<EndlessScheduleDef.ZombieEntry> pool = List.of(
                entry("basic_zombie", 1, 10),
                entry("conehead_zombie", 2, 8),
                entry("flag_zombie", 3, 2),
                entry("pole_vaulter_zombie", 5, 5),
                entry("buckethead_zombie", 7, 8),
                entry("newspaper_zombie", 10, 5),
                entry("football_zombie", 13, 5),
                entry("door_zombie", 16, 5));
        return new EndlessScheduleDef(
                EndlessScheduleDef.DEFAULT_ROUND_WAVES_BASE,
                EndlessScheduleDef.DEFAULT_ROUND_WAVES_PER_ROUND,
                EndlessScheduleDef.DEFAULT_ROUND_WAVES_MAX,
                EndlessScheduleDef.DEFAULT_HUGE_WAVES_PER_ROUND,
                // Tighter than the endless levels' 420/150: a song is three minutes, not an
                // evening, so the waves have to pour rather than trickle - and the coefficient
                // moves the whole band (a harder tier reaches the tight end sooner).
                360,
                120,
                EndlessScheduleDef.DEFAULT_SPAWN_INTERVAL_RAMP_ROUNDS,
                // And the quiet between waves is short for the same reason: the player is busy
                // with the chart, not watching an empty lawn.
                420,
                180,
                EndlessScheduleDef.DEFAULT_GAP_RAMP_ROUNDS,
                EndlessScheduleDef.DEFAULT_COUNT_RAMP_ROUNDS,
                2,
                7,
                // Half the endless growth per round, because "a round" here is a slice of one
                // song rather than a stretch of a run: at the shipped coefficients the hardest
                // tier ends the track around round seventy and a health multiplier of two.
                new EndlessScheduleDef.StatGrowth(0.02F, 2F),
                pool);
    }

    private static EndlessScheduleDef.ZombieEntry entry(String path, int fromRound, int weight) {
        return new EndlessScheduleDef.ZombieEntry(id(path), fromRound, weight, false, 0);
    }

    private static EndlessScheduleDef.ZombieEntry entry(String path, int fromRound, int weight,
                                                        int maxPerWave) {
        return new EndlessScheduleDef.ZombieEntry(id(path), fromRound, weight, false, maxPerWave);
    }

    private static EndlessScheduleDef.ZombieEntry water(String path, int fromRound, int weight) {
        return new EndlessScheduleDef.ZombieEntry(id(path), fromRound, weight, true, 0);
    }

    private static Identifier id(String path) {
        return PvzceIds.id(path);
    }
}
