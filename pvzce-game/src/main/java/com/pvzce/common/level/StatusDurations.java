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
        return scale(level, ticks, 1F);
    }

    /** Cold composes with the existing status-duration rule at the moment of application. */
    public static int cold(LevelAccess level, int ticks) {
        return scale(level, ticks, level == null ? 1F : level.weatherColdDurationMultiplier());
    }

    /** Ice-shroom freeze historically uses its own duration, independent of the slow-duration rule. */
    public static int coldFreeze(LevelAccess level, int ticks) {
        return ticks <= 0 ? 0 : Math.max(1, Math.round(ticks * (level == null ? 1F : level.weatherColdDurationMultiplier())));
    }

    private static int scale(LevelAccess level, int ticks, float extra) {
        if (ticks <= 0) return 0;
        float factor = level == null || level.rules() == null ? 1F
                : level.rules().getFloat(PvzceIds.RULE_SLOW_DURATION_MULTIPLIER);
        factor = Math.max(MIN_FACTOR, Math.min(MAX_FACTOR, factor));
        return Math.max(1, Math.round(ticks * factor * extra));
    }
}
