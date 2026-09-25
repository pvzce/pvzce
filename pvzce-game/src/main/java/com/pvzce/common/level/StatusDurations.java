package com.pvzce.common.level;

import com.pvzce.api.entity.LevelAccess;
import com.pvzce.common.PvzceIds;

/**
 * How long a status lasts on this lawn.
 *
 * <p>One helper rather than a rule read at each call site, because "chilled for twice as long" has to
 * mean the same thing for a snow pea's hit and for an ice-shroom's freeze - and those are two
 * different capabilities. The rule is a multiplier on the effect's own written duration, so a pack
 * that ships "slow 50% for 2s" keeps its own number and the level scales it.
 *
 * <p>Read where the status is <em>applied</em>, not while it counts down: a chill that is already on a
 * zombie was applied by the rules in force at that moment, which is also what makes it survivable to
 * save and resume (the remaining ticks are in the entity's own state).
 */
public final class StatusDurations {
    /** Clamp for the multiplier, matching the rule's own domain. */
    private static final float MIN_FACTOR = 0.1F;
    private static final float MAX_FACTOR = 10F;

    private StatusDurations() {
    }

    /** The ticks this status lasts on this level, or the written number when there is no level. */
    public static int scale(LevelAccess level, int ticks) {
        if (level == null || level.rules() == null || ticks <= 0) {
            return Math.max(0, ticks);
        }
        float factor = level.rules().getFloat(PvzceIds.RULE_SLOW_DURATION_MULTIPLIER);
        factor = Math.max(MIN_FACTOR, Math.min(MAX_FACTOR, factor));
        return Math.max(1, Math.round(ticks * factor));
    }
}
