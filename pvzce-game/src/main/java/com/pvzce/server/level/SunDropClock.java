package com.pvzce.server.level;

import com.pvzce.common.PvzceIds;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.server.gamerule.GameRules;

import java.util.Random;

/**
 * When the sky drops its next sun.
 *
 * <p>Replaces the per-tick roll ({@code random.nextFloat() < sun_spawn_chance}) that used to
 * live in {@code LevelServer.maybeSpawnSun}. The roll was independent every tick, which meant
 * the level had no idea when the last sun fell: the average was the number the level wrote, but
 * the actual gaps were geometric - a minute of nothing followed by three suns in two seconds
 * was as likely as the ten seconds the author had in mind.
 *
 * <p>This class keeps the one thing the roll could not: a countdown. Each drop picks the gap
 * until the next one, uniformly inside the level's {@code sun_spawn_interval_min} /
 * {@code _max}, and the first drop of a level uses {@code sun_spawn_initial_ticks} instead -
 * the opening is the one moment the sky is allowed to be prompt.
 *
 * <p>The countdown is part of the level's save file, so a restored run resumes the gap it was
 * in instead of handing the player an immediate sun. That is also why the roll lives here and
 * not in {@code LevelServer}: the value that has to survive a save is this object's whole
 * state.
 *
 * <p>{@code min} of {@code 0} is the off switch, and it is the old {@code sun_spawn_chance: 0}
 * written the new way: the sky drops nothing. A range whose maximum is below its minimum is
 * read as "both ends are the minimum", because a level with a typo should still be playable and
 * the validator is what reports the typo.
 */
public final class SunDropClock {
    private static final String KEY_COUNTDOWN = "SunDropCountdown";
    private static final String KEY_FIRST_DONE = "SunDropFirstDone";
    /** The longest gap a level may ask for, matching the rule's own bound. */
    private static final int MAX_INTERVAL_TICKS = 36000;

    private int countdown;
    private boolean firstDropDone;

    /** Rewinds the clock to the level's opening: the first sun uses the initial delay. */
    public void reset(GameRules rules) {
        firstDropDone = false;
        countdown = initialDelay(rules);
    }

    /**
     * Advances one tick and answers whether a sun is due now.
     *
     * <p>Ticked only while the level runs, so a paused or ended level does not bank suns.
     * The countdown is re-armed from the rules on every drop rather than cached, so a
     * {@code /gamerule} change lands on the next sun instead of the next level.
     *
     * @return true on the tick the sky drops one
     */
    public boolean tick(GameRules rules) {
        if (disabled(rules)) {
            return false;
        }
        if (countdown > 0) {
            countdown--;
        }
        if (countdown > 0) {
            return false;
        }
        countdown = firstDropDone ? rollInterval(rules) : initialDelay(rules);
        firstDropDone = true;
        return true;
    }

    private static boolean disabled(GameRules rules) {
        return Math.max(0, rules.getInt(PvzceIds.RULE_SUN_SPAWN_INTERVAL_MIN))
                + Math.max(0, rules.getInt(PvzceIds.RULE_SUN_SPAWN_INTERVAL_MAX)) <= 0;
    }

    private static int initialDelay(GameRules rules) {
        int configured = rules.getInt(PvzceIds.RULE_SUN_SPAWN_INITIAL_TICKS);
        // A level that wants no head start leaves the rule alone and sets its interval; the
        // boot delay then has to stay inside what that level asked for, or the "prompt first
        // sun" default would be the only sun this clock ever drops early.
        int base = configured > 0
                ? clamp(configured)
                : Math.max(1, Math.min(clamp(rules.getInt(PvzceIds.RULE_SUN_SPAWN_INTERVAL_MIN)),
                        clamp(rules.getInt(PvzceIds.RULE_SUN_SPAWN_INTERVAL_MAX))));
        float rate = Math.max(0.1F, rules.getFloat(PvzceIds.RULE_SUN_RATE_MULTIPLIER));
        return Math.max(1, Math.round(base / rate));
    }

    /**
     * The gap until the sun after this one.
     *
     * <p>Uniform inside {@code [min, max]}, and inclusive of both ends: the author wrote a
     * range, and a roll that could never produce either end would quietly narrow it.
     *
     * <p>The {@code sun_rate_multiplier} rule divides the whole gap, so a level whose sun comes
     * twice as fast gets two suns where the author wrote one rather than one short wait followed
     * by the original interval. Read here rather than cached, for the same reason the range is: a
     * mutation rewrites the rule mid-level, and the next gap has to hear about it.
     */
    public static int rollInterval(GameRules rules, Random random) {
        int min = clamp(rules.getInt(PvzceIds.RULE_SUN_SPAWN_INTERVAL_MIN));
        int max = clamp(rules.getInt(PvzceIds.RULE_SUN_SPAWN_INTERVAL_MAX));
        if (max < min) {
            max = min;
        }
        float rate = Math.max(0.1F, rules.getFloat(PvzceIds.RULE_SUN_RATE_MULTIPLIER));
        int rolled = min >= max ? min : min + random.nextInt(max - min + 1);
        // Zero stays zero: it is the switch that says the sky drops nothing at all, and dividing
        // "off" by a rate must not turn it into one sun every tick.
        return rolled <= 0 ? 0 : Math.max(1, Math.round(rolled / rate));
    }

    private int rollInterval(GameRules rules) {
        return rollInterval(rules, randomForRoll);
    }

    private static int clamp(int ticks) {
        return Math.max(0, Math.min(MAX_INTERVAL_TICKS, ticks));
    }

    /** The level's own random source, supplied so a save/restore does not replace the dice. */
    private final Random randomForRoll;

    public SunDropClock(Random random) {
        this.randomForRoll = random;
        this.countdown = 0;
    }

    /** Ticks still to wait, for tests and for the save block. */
    public int countdown() {
        return countdown;
    }

    /** Overwrites the countdown; the restore path of {@link #save}. */
    public void setCountdown(int ticks, boolean firstDone) {
        this.countdown = Math.max(0, Math.min(MAX_INTERVAL_TICKS, ticks));
        this.firstDropDone = firstDone;
    }

    /** Writes the clock into the level's save tag. */
    public void save(CompoundTag root) {
        root.putInt(KEY_COUNTDOWN, countdown);
        if (firstDropDone) {
            root.putByte(KEY_FIRST_DONE, (byte) 1);
        }
    }

    /**
     * Reads back what {@link #save} wrote.
     *
     * <p>A save without this block predates the clock; the caller resets it instead, which is
     * what {@link #restore} reports through its return value.
     *
     * @return true when a countdown was found and applied
     */
    public boolean restore(CompoundTag root) {
        if (!root.contains(KEY_COUNTDOWN)) {
            return false;
        }
        setCountdown(root.getInt(KEY_COUNTDOWN), root.getInt(KEY_FIRST_DONE) != 0);
        return true;
    }
}
