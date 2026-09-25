package com.pvzce.common.level.mutation;

import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.server.level.LevelServer;

/**
 * The numeric mutations: one game rule, multiplied by whatever the tier rolled.
 *
 * <p>Nine mutations in the catalogue are the same sentence with a different rule - sun arrives
 * faster, plants work faster, zombies walk faster, zombies arrive faster, cards cost more, cards
 * recharge slower, the horde is tougher, the plants are frailer - so they are one class
 * parameterised by the rule rather than nine classes that would each have to get the save-and-restore
 * half right. Two of them scale a rule this class does not otherwise care about: the health
 * multipliers are read where an entity is <em>created</em>, which is why they need no refresh pass.
 *
 * <p>The roll multiplies the rule's <em>written</em> value, not the current one: the mutation
 * snapshots what the rule said when it arrived and restores exactly that when it leaves. A second
 * copy of the same mutation therefore compounds (two rolls of 1.4 are 1.96, not a re-roll), which
 * is the "数值类的就叠加" rule - and undoing them in any order still lands back on the original,
 * because each one restores what it found only when it is the last one holding the rule.
 */
final class RateMutation implements Mutation, com.pvzce.common.level.mutation.MutationManager.RuleMutation {
    private final Identifier id;
    private final Identifier rule;
    private final float lowest;
    private final float highest;
    private final boolean invert;

    /**
     * @param rule    the game rule this mutation scales
     * @param lowest  the smallest value the rule may be set to; the mutation clamps to it
     * @param highest the largest value the rule may be set to
     * @param invert  true when the rule counts <em>down</em> (an interval): then a bigger roll
     *                means more of the thing, so the rule is divided instead of multiplied
     */
    RateMutation(Identifier id, Identifier rule, float lowest, float highest, boolean invert) {
        this.id = id;
        this.rule = rule;
        this.lowest = lowest;
        this.highest = highest;
        this.invert = invert;
    }

    @Override
    public Identifier id() {
        return id;
    }

    /** The rule this mutation scales; see {@code MutationManager.rulesForSave}. */
    @Override
    public Identifier rule() {
        return rule;
    }

    @Override
    public Mutation.Roll roll(LevelServer level) {
        return Mutation.Roll.of(level.mutations().difficulty().rollNumber(level.random()));
    }

    @Override
    public Object apply(LevelServer level, Mutation.Roll roll) {
        float before = level.rules().getFloat(rule);
        level.setRule(rule, scale(before, roll.multiplier()));
        return new Applied(roll.multiplier());
    }

    /**
     * On a restore the rule is written again rather than read back.
     *
     * <p>This is the one shape where "the world already has it" is false: the rules in a save are
     * the <em>level's own</em>, and this mutation's share of them was never part of the file - it
     * is this object's factor, applied to whatever the rule said when it arrived. Multiplying
     * again by the same factor reproduces exactly the value that was saved, because the factor is
     * what the roll already carries.
     */
    @Override
    public Object applyFromSave(LevelServer level, Mutation.Roll roll) {
        level.setRule(rule, scale(level.rules().getFloat(rule), roll.multiplier()));
        return new Applied(roll.multiplier());
    }

    @Override
    public void revert(LevelServer level, Mutation.Roll roll, Object state) {
        float factor = state instanceof Applied applied ? applied.factor() : roll.multiplier();
        // What the rule says now, divided back out: another copy of this mutation (or a mutation
        // that shares the rule) may have scaled it since, and restoring the remembered "before"
        // would silently drop their half.
        float current = level.rules().getFloat(rule);
        level.setRule(rule, scale(current, 1F / factor));
    }

    /** The scaled value, clamped into the rule's own domain. */
    private float scale(float value, float factor) {
        float scaled = invert ? value / factor : value * factor;
        return Math.max(lowest, Math.min(highest, scaled));
    }

    /** What this activation remembers: the factor it applied, for the inverse on the way out. */
    private record Applied(float factor) {
    }

    /** Sun arrives faster (or slower): both the sky and the producers. */
    static Mutation sunRate() {
        return new RateMutation(PvzceIds.MUTATION_SUN_RATE, PvzceIds.RULE_SUN_RATE_MULTIPLIER,
                0.1F, 10F, false);
    }

    /** Plants work faster (or slower): shooters fire, producers produce, throwers lob. */
    static Mutation plantAttackRate() {
        return new RateMutation(PvzceIds.MUTATION_PLANT_ATTACK_RATE,
                PvzceIds.RULE_PLANT_ACTION_SPEED_MULTIPLIER, 0.1F, 20F, false);
    }

    /** Zombies walk faster (or slower). */
    static Mutation zombieSpeed() {
        return new RateMutation(PvzceIds.MUTATION_ZOMBIE_SPEED, PvzceIds.RULE_ZOMBIE_SPEED_MULTIPLIER,
                0.1F, 20F, false);
    }

    /** Zombies arrive faster (or slower): the gap between waves and inside one. */
    static Mutation zombieSpawnRate() {
        return new RateMutation(PvzceIds.MUTATION_ZOMBIE_SPAWN_RATE,
                PvzceIds.RULE_ZOMBIE_SPAWN_SPEED_MULTIPLIER, 0.1F, 20F, false);
    }

    /** Zombies arrive with more (or less) health than their definition says. */
    static Mutation zombieHealth() {
        return new RateMutation(PvzceIds.MUTATION_ZOMBIE_HEALTH,
                PvzceIds.RULE_ZOMBIE_HEALTH_MULTIPLIER, 0.1F, 20F, false);
    }

    /**
     * Plants are planted with a fraction of their own health: "植物脆化".
     *
     * <p><b>Inverted, and capped at 1.</b> The rule is a health <em>multiplier</em>, so the roll has
     * to be read upside down for a bigger roll to mean more fragile - and the ceiling is the whole
     * point of the mutation: a mutation called "fragile" that rolled the other way and handed the
     * player tougher plants would be a different mutation wearing this one's name. Dividing also
     * makes the tiers work: EASY's rolls give 0.67x to 1x, HELL's give 0.22x to 0.67x, and the floor
     * stops it at a fifth.
     */
    static Mutation plantFragile() {
        return new RateMutation(PvzceIds.MUTATION_PLANT_FRAGILE,
                PvzceIds.RULE_PLANT_HEALTH_MULTIPLIER, 0.2F, 1F, true);
    }

    /** Cards take longer (or less long) to recharge, on top of whatever the level asked for. */
    static Mutation cardCooldown() {
        return new RateMutation(PvzceIds.MUTATION_CARD_COOLDOWN,
                PvzceIds.RULE_SEED_COOLDOWN_MULTIPLIER, 0.1F, 5F, false);
    }

    /** Cards cost more (or less). */
    static Mutation plantSunCost() {
        return new RateMutation(PvzceIds.MUTATION_PLANT_SUN_COST,
                PvzceIds.RULE_PLANT_SUN_COST_MULTIPLIER, 0F, 10F, false);
    }
}
