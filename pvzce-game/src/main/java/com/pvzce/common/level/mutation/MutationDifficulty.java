package com.pvzce.common.level.mutation;

import com.mojang.serialization.Codec;
import com.pvzce.common.PvzceConstants;

import java.util.Locale;
import java.util.Random;

/**
 * How hard a mutation level is: how many mutations may stand at once, how often a new one
 * arrives, and how strong their numbers roll.
 *
 * <p>The four tiers are shipped content rather than a rule a level file writes by hand, because
 * the three numbers move together and a level that set only one of them would not be a tier at
 * all - the top tier means all three of "many", "often" and "strong", which is why this is an enum with
 * a codec instead of three game rules.
 *
 * <p>The strength half is a multiplier on the <em>roll</em>, not on the effect: a mutation that
 * rolls its number out of {@code [0.5, 1.5]} at EASY rolls out of {@code [0.5k, 1.5k]} at HELL,
 * so {@code k} scales what a mutation can do in either direction rather than only making it
 * worse. The bound that saves this from nonsense is the game rule each number is finally written
 * into - a rule with a 0.1 floor clamps there, and the tier does not have to know every rule's
 * domain.
 */
public enum MutationDifficulty {
    /** 10 at once, one every two minutes, rolls as written. */
    EASY("easy", 10, 120 * PvzceConstants.TICKS_PER_SECOND, 1F),
    /** 20 at once, one every ninety seconds, rolls half again as wide. */
    NORMAL("normal", 20, 90 * PvzceConstants.TICKS_PER_SECOND, 1.5F),
    /** 30 at once, one a minute, rolls twice as wide. */
    HARD("hard", 30, 60 * PvzceConstants.TICKS_PER_SECOND, 2F),
    /** 50 at once, one every thirty seconds, rolls three times as wide. */
    HELL("hell", 50, 30 * PvzceConstants.TICKS_PER_SECOND, 3F);

    /** The tier a level that never says: the middle one, which is what the mutation levels ship. */
    public static final MutationDifficulty DEFAULT = NORMAL;

    /** The lowest a mutation's own roll may start from, before the tier's multiplier. */
    public static final float ROLL_MIN = 0.5F;
    /** The highest a mutation's own roll may reach, before the tier's multiplier. */
    public static final float ROLL_MAX = 1.5F;

    /** What a level's {@code mutation difficulty} rule holds, and what a save stores. */
    public static final Codec<MutationDifficulty> CODEC = Codec.STRING.xmap(
            MutationDifficulty::parse, MutationDifficulty::name);

    private final String name;
    private final int maxConcurrent;
    private final int intervalTicks;
    private final float rollMultiplier;

    MutationDifficulty(String name, int maxConcurrent, int intervalTicks, float rollMultiplier) {
        this.name = name;
        this.maxConcurrent = maxConcurrent;
        this.intervalTicks = intervalTicks;
        this.rollMultiplier = rollMultiplier;
    }

    /** The lowercase name this tier is written and looked up by. */
    public String tierName() {
        return name;
    }

    /** How many mutations may be on the field at once; the oldest is evicted past this. */
    public int maxConcurrent() {
        return maxConcurrent;
    }

    /** How long the level waits between two mutations. */
    public int intervalTicks() {
        return intervalTicks;
    }

    /**
     * The tier's multiplier on a mutation's roll, applied to both ends of it.
     *
     * <p>Applied to the interval too: a mutation that runs on its own clock (a grave every twenty
     * seconds, a crisis zombie every second or so) divides its interval by this, so HELL is
     * genuinely three times as busy rather than merely three times as crowded.
     */
    public float rollMultiplier() {
        return rollMultiplier;
    }

    /** An interval a mutation's own clock runs on at this tier, never below one tick. */
    public int scaledInterval(int baseTicks) {
        return Math.max(1, Math.round(baseTicks / rollMultiplier));
    }

    /**
     * Rolls one number out of {@code [0.5, 1.5]} scaled by this tier.
     *
     * <p>Both ends move, which is what the tiers are: 简单 can produce anything from a helpful
     * mutation to a harmful one, and HELL produces the same shapes, three times as far from 1.
     */
    public float rollNumber(Random random) {
        float span = ROLL_MAX - ROLL_MIN;
        return (ROLL_MIN + random.nextFloat() * span) * rollMultiplier;
    }

    /**
     * The tier a name like {@code "hell"} means, or the default for anything unrecognised.
     *
     * <p>Both spellings are accepted - the lowercase one a level file writes, and the constant name
     * the enum's own {@code toString} produces - because the value reaches this method down two
     * paths: the codec, which parses the file's string, and {@code /gamerule}, which hands over the
     * value the command parser produced. A parser that only knew one of them would silently leave a
     * hard level set to the middle tier.
     */
    public static MutationDifficulty parse(String value) {
        if (value != null) {
            String trimmed = value.trim().toLowerCase(Locale.ROOT);
            for (MutationDifficulty tier : values()) {
                if (tier.name.equals(trimmed) || tier.name().toLowerCase(Locale.ROOT).equals(trimmed)) {
                    return tier;
                }
            }
        }
        return DEFAULT;
    }

    /** True when the name is one of the four tiers, for the validator's message. */
    public static boolean isKnown(String value) {
        if (value == null) {
            return false;
        }
        String trimmed = value.trim().toLowerCase(Locale.ROOT);
        for (MutationDifficulty tier : values()) {
            if (tier.name.equals(trimmed)) {
                return true;
            }
        }
        return false;
    }
}
