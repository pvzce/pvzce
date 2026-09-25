package com.pvzce.common.level.mutation;

import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.server.level.LevelServer;

/**
 * It is night now: the backdrop, the mushrooms and the sky all change, and the board goes dark.
 *
 * <p>Three rules move together, because "night" is not one flag in this engine - it is derived
 * from the clock ({@code DayNightCycle.isNight}), which is what makes a mushroom sleep, what the
 * client lights the board by, and what a night level's graves open on. So this writes the clock's
 * two numbers, stops the sky from dropping suns (the original's night levels have no sky sun), and
 * leaves the producers alone: a sunflower in the dark still pays.
 *
 * <p>The backdrop is the client's, so the mutation also asks for
 * {@link MutationEffects#POOL_NIGHT}: that effect carries the after-dark picture *and* the wash
 * over it, which together are what "it is night now" looks like. Without it a day level turned
 * night by this mutation was a black haze on a bright lawn.
 */
final class NightfallMutation implements Mutation {
    /**
     * What {@code night_length} becomes.
     *
     * <p>The clock is written as {@code day_length: 0} plus a positive night, which is the
     * engine's own spelling of "this level is at night for its whole run" - the number itself only
     * has to be large enough that the cycle never comes round again.
     */
    private static final int NIGHT_LENGTH_TICKS = 360_000;

    @Override
    public Identifier id() {
        return PvzceIds.MUTATION_NIGHTFALL;
    }

    @Override
    public MutationEffects clientEffects() {
        // Both halves: the clock and the rules are the server's, the haze is a drawing, and the
        // backdrop is a texture the client has to swap - its id came in with the level payload and
        // no packet carries a new one.
        return MutationEffects.POOL_NIGHT;
    }

    @Override
    public Object apply(LevelServer level, Mutation.Roll roll) {
        Object beforeDay = level.ruleValue(PvzceIds.RULE_DAY_LENGTH);
        Object beforeNight = level.ruleValue(PvzceIds.RULE_NIGHT_LENGTH);
        Object beforeMin = level.ruleValue(PvzceIds.RULE_SUN_SPAWN_INTERVAL_MIN);
        Object beforeMax = level.ruleValue(PvzceIds.RULE_SUN_SPAWN_INTERVAL_MAX);
        level.setRule(PvzceIds.RULE_DAY_LENGTH, 0);
        level.setRule(PvzceIds.RULE_NIGHT_LENGTH, NIGHT_LENGTH_TICKS);
        level.setRule(PvzceIds.RULE_SUN_SPAWN_INTERVAL_MIN, 0);
        level.setRule(PvzceIds.RULE_SUN_SPAWN_INTERVAL_MAX, 0);
        return new Applied(beforeDay, beforeNight, beforeMin, beforeMax);
    }

    /**
     * On a restore the rules are left alone: they are part of the save.
     *
     * <p>The snapshot of what stood before the night fell is only needed to undo a night that is
     * still happening, and a restored level's rules <em>are</em> the night already - writing them
     * again would be harmless, but replacing the remembered "before" with today's night would
     * leave the mutation unable to undo itself.
     */
    @Override
    public Object applyFromSave(LevelServer level, Mutation.Roll roll) {
        return null;
    }

    @Override
    public void revert(LevelServer level, Mutation.Roll roll, Object state) {
        if (!(state instanceof Applied applied)) {
            return;
        }
        // Restored from what was there when it arrived, not from "what night is not": the level
        // may have started as a night level, and turning the lights back on would be a different
        // mutation from the one that was evicted.
        restore(level, PvzceIds.RULE_DAY_LENGTH, applied.beforeDay());
        restore(level, PvzceIds.RULE_NIGHT_LENGTH, applied.beforeNight());
        restore(level, PvzceIds.RULE_SUN_SPAWN_INTERVAL_MIN, applied.beforeSunMin());
        restore(level, PvzceIds.RULE_SUN_SPAWN_INTERVAL_MAX, applied.beforeSunMax());
    }

    private static void restore(LevelServer level, Identifier rule, Object value) {
        if (value != null) {
            level.setRule(rule, value);
        }
    }

    /** The four rules as they were. */
    private record Applied(Object beforeDay, Object beforeNight, Object beforeSunMin,
                           Object beforeSunMax) {
    }
}
