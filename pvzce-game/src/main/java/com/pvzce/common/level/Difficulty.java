package com.pvzce.common.level;

import com.mojang.serialization.Codec;
import com.pvzce.common.PvzceIds;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * How hard the whole game is: one tier per world, switchable at any time.
 *
 * <p>Four tiers that move four numbers together, because a tier that set only one of them would not
 * be a tier at all - "hell" means all four of "tougher, faster, more of them, less sun". They are
 * shipped content rather than a rule a level file writes by hand, which is the same argument
 * {@code MutationDifficulty} makes for its three: an enum with a codec, not four game rules.
 *
 * <p><b>{@link #NORMAL} is the original game.</b> Every factor is exactly 1, so a world on the
 * default tier plays the levels as they were authored - and every test, the plant AI and the level
 * editor's preview (none of which has a profile) get the original behaviour by construction.
 *
 * <p><b>How it reaches the simulation.</b> The factors are written into the level's own game rules
 * as an extra multiplier, at level creation and again whenever the tier changes. Nothing reads this
 * enum to find out how fast a zombie walks: it reads {@code zombie_speed_multiplier}, which already
 * has one answer. The two rules that are read once per entity (health at spawn, speed per tick) are
 * therefore "the tier in force when it spawned", which is the honest behaviour for a setting that
 * can be switched mid-run.
 */
public enum Difficulty {
    /** Tougher, faster, sooner, and a little less sun than the original. */
    EASY("easy", 0.8F, 0.85F, 1.15F, 1.2F),
    /** The original. Every factor is one. */
    NORMAL("normal", 1F, 1F, 1F, 1F),
    /** The original with a thinner margin. */
    HARD("hard", 1.3F, 1.12F, 0.9F, 0.85F),
    /** For a player who has already beaten everything: the same levels, twice as unforgiving. */
    HELL("hell", 1.7F, 1.25F, 0.8F, 0.7F);

    /** The tier a world that never chose: the original's own difficulty. */
    public static final Difficulty DEFAULT = NORMAL;

    /** What a profile stores and what the packet carries. */
    public static final Codec<Difficulty> CODEC = Codec.STRING.xmap(
            Difficulty::parse, Difficulty::key);

    private final String key;
    private final float zombieHealth;
    private final float zombieSpeed;
    private final float spawnInterval;
    private final float sunRate;

    Difficulty(String key, float zombieHealth, float zombieSpeed, float spawnInterval,
               float sunRate) {
        this.key = key;
        this.zombieHealth = zombieHealth;
        this.zombieSpeed = zombieSpeed;
        this.spawnInterval = spawnInterval;
        this.sunRate = sunRate;
    }

    /** The lowercase name this tier is written and looked up by. */
    public String key() {
        return key;
    }

    /** What every zombie's health is multiplied by, read once when it spawns. */
    public float zombieHealth() {
        return zombieHealth;
    }

    /** What every zombie's walking speed is multiplied by. */
    public float zombieSpeed() {
        return zombieSpeed;
    }

    /**
     * What the gap between two zombies is multiplied by; above one means a slower trickle.
     *
     * <p>Named for what a level author writes, not for the rule it lands in: the rule is
     * {@code zombie_spawn_speed_multiplier}, which <em>divides</em> the gap, so a tier that wants
     * gaps 20% longer contributes {@code 1/1.2} to it. Folding that inversion in here is what lets
     * the table above read as a table.
     */
    public float spawnInterval() {
        return spawnInterval;
    }

    /** What sky sun production is multiplied by. */
    public float sunRate() {
        return sunRate;
    }

    /** True for the tier that changes nothing; the one a level cannot tell from "no difficulty". */
    public boolean isOriginal() {
        return this == NORMAL;
    }

    /**
     * The factors as game-rule multipliers, keyed by the rule each one lands in.
     *
     * <p>An empty map at {@link #NORMAL}, so applying it is a no-op rather than four multiplications
     * by one - and so a caller can skip the whole thing for the tier that is the original.
     */
    public Map<com.pvzce.api.util.Identifier, Float> ruleFactors() {
        if (isOriginal()) {
            return Map.of();
        }
        Map<com.pvzce.api.util.Identifier, Float> factors = new LinkedHashMap<>(4);
        factors.put(PvzceIds.RULE_ZOMBIE_HEALTH_MULTIPLIER, zombieHealth);
        factors.put(PvzceIds.RULE_ZOMBIE_SPEED_MULTIPLIER, zombieSpeed);
        factors.put(PvzceIds.RULE_ZOMBIE_SPAWN_SPEED_MULTIPLIER, 1F / spawnInterval);
        factors.put(PvzceIds.RULE_SUN_RATE_MULTIPLIER, sunRate);
        return Map.copyOf(factors);
    }

    /** The tier with this name, or {@link #DEFAULT} for anything else. */
    public static Difficulty parse(String name) {
        if (name == null || name.isBlank()) {
            return DEFAULT;
        }
        String wanted = name.trim().toLowerCase(Locale.ROOT);
        for (Difficulty tier : values()) {
            if (tier.key.equals(wanted) || tier.name().toLowerCase(Locale.ROOT).equals(wanted)) {
                return tier;
            }
        }
        return DEFAULT;
    }

    /** True when this name is one of the four; for the packet handler and the command. */
    public static boolean isKnown(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        String wanted = name.trim().toLowerCase(Locale.ROOT);
        for (Difficulty tier : values()) {
            if (tier.key.equals(wanted) || tier.name().toLowerCase(Locale.ROOT).equals(wanted)) {
                return true;
            }
        }
        return false;
    }

    /** The next tier in the list, wrapping; what a one-button selector walks through. */
    public Difficulty next() {
        Difficulty[] all = values();
        return all[(ordinal() + 1) % all.length];
    }
}
