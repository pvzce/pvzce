package com.pvzce.server.gamerule;

import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.level.DayNightCycle;

/**
 * Day/night cycle driven by the {@code pvzce:day_length} / {@code pvzce:night_length}
 * rules. All the maths lives in {@link DayNightCycle} so the client's smooth
 * lighting factor and this hard day/night answer can never disagree.
 */
public final class PvzceClock {
    public static final Identifier DAY_LENGTH = PvzceIds.RULE_DAY_LENGTH;
    public static final Identifier NIGHT_LENGTH = PvzceIds.RULE_NIGHT_LENGTH;

    private long dayTicks;

    public void tick() {
        dayTicks++;
    }

    public int dayLength(GameRules rules) {
        return Math.max(0, rules.getInt(DAY_LENGTH));
    }

    public int nightLength(GameRules rules) {
        return rules.getInt(NIGHT_LENGTH);
    }

    public boolean isNight(GameRules rules) {
        return DayNightCycle.isNight(dayTicks, dayLength(rules), nightLength(rules));
    }

    public float nightBlend(GameRules rules) {
        return DayNightCycle.nightBlend(dayTicks, dayLength(rules), nightLength(rules));
    }

    public long dayTicks() {
        return dayTicks;
    }

    public void setDayTicks(long dayTicks) {
        this.dayTicks = Math.max(0L, dayTicks);
    }

    public void addDayTicks(long amount) {
        setDayTicks(this.dayTicks + amount);
    }
}
