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
