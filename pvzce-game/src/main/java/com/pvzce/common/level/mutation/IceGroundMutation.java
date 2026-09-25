package com.pvzce.common.level.mutation;

import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.server.level.LevelServer;

import java.util.List;

/**
 * The ground is sheet ice: "结冰地面".
 *
 * <p>Two effects that are one idea: everything walks half again as fast, and everything that is
 * chilled stays chilled twice as long. The first is a rule the engine already has, the second is a
 * rule this mutation brought with it ({@code slow_duration_multiplier}, read where a status is
 * applied) - and both are needed for the mutation to read as <em>ice</em> rather than as "a speed
 * buff": on ice you skid forward, and the cold that would have stopped you does not let go.
 *
 * <p>Both factors are fixed rather than rolled. A rolled speed on top of the walk that is already
 * this level's is a different mutation ("僵尸移动速率" exists), and the point of this one is the
 * combination: the player sees the board turn white and knows exactly what changed.
 *
 * <p>Implements {@link MutationManager.RuleMutation} for <em>two</em> rules, which is why that
 * interface can name a list: a save unwinds what a mutation multiplied, and a mutation that
 * multiplied two things has to unwind both or a resumed run compounds one of them.
 */
final class IceGroundMutation implements Mutation, MutationManager.RuleMutation {
    /** How much faster everything walks. */
    private static final float SPEED_FACTOR = 1.5F;
    /** How much longer a chill lasts. */
    private static final float CHILL_FACTOR = 2F;

    @Override
    public Identifier id() {
        return PvzceIds.MUTATION_ICE_GROUND;
    }

    @Override
    public Identifier rule() {
        return PvzceIds.RULE_ZOMBIE_SPEED_MULTIPLIER;
    }

    @Override
    public List<Identifier> rules() {
        return List.of(PvzceIds.RULE_ZOMBIE_SPEED_MULTIPLIER,
                PvzceIds.RULE_SLOW_DURATION_MULTIPLIER);
    }

    /**
     * The two fixed factors, written to their two rules.
     *
     * <p>Not the roll: this mutation's numbers are what the mutation <em>is</em> ("the ground is
     * icy" is 1.5 and 2, not 1.5 and something the tier rolled), and the roll exists only so the
     * panel and the save file have a number to carry. What the save unwinds is what
     * {@link #appliedFactor} reports from the state below, which is why the two can differ.
     */
    @Override
    public Object apply(LevelServer level, Mutation.Roll roll) {
        level.setRule(PvzceIds.RULE_ZOMBIE_SPEED_MULTIPLIER,
                level.rules().getFloat(PvzceIds.RULE_ZOMBIE_SPEED_MULTIPLIER) * SPEED_FACTOR);
        level.setRule(PvzceIds.RULE_SLOW_DURATION_MULTIPLIER,
                level.rules().getFloat(PvzceIds.RULE_SLOW_DURATION_MULTIPLIER) * CHILL_FACTOR);
        return new Applied(SPEED_FACTOR, CHILL_FACTOR);
    }

    /**
     * What each rule really got, read from the state that applied it.
     *
     * <p>The alternative - the roll's single number for both - left the chill's extra 2/1.5 in the
     * save file and multiplied it in again on every resume, which is the "a resumed run compounds
     * one of them for ever" failure the interface's own doc warns about.
     */
    @Override
    public float appliedFactor(Identifier rule, Mutation.Roll roll, Object state) {
        if (!(state instanceof Applied applied)) {
            return roll == null ? 1F : roll.multiplier();
        }
        return PvzceIds.RULE_SLOW_DURATION_MULTIPLIER.equals(rule)
                ? applied.chillFactor() : applied.speedFactor();
    }

    /**
     * On a restore both rules are written again.
     *
     * <p>The same shape as {@code RateMutation.applyFromSave}: the rules in a save are the level's
     * own, and this mutation's share was never part of the file.
     */
    @Override
    public Object applyFromSave(LevelServer level, Mutation.Roll roll) {
        return apply(level, roll);
    }

    @Override
    public void revert(LevelServer level, Mutation.Roll roll, Object state) {
        float speed = state instanceof Applied applied ? applied.speedFactor() : SPEED_FACTOR;
        float chill = state instanceof Applied applied ? applied.chillFactor() : CHILL_FACTOR;
        level.setRule(PvzceIds.RULE_ZOMBIE_SPEED_MULTIPLIER,
                level.rules().getFloat(PvzceIds.RULE_ZOMBIE_SPEED_MULTIPLIER) / speed);
        level.setRule(PvzceIds.RULE_SLOW_DURATION_MULTIPLIER,
                level.rules().getFloat(PvzceIds.RULE_SLOW_DURATION_MULTIPLIER) / chill);
    }

    @Override
    public Mutation.Roll roll(LevelServer level) {
        // Fixed factors, so the roll is the "nothing was rolled" shape with the speed's factor as
        // its number: the save unwinds by it, and the panel prints ×1.50, which is exactly what the
        // player sees happen.
        return Mutation.Roll.of(SPEED_FACTOR);
    }

    @Override
    public MutationEffects clientEffects() {
        // Nothing to draw: the ice is the speed and the chill, both of which the player reads off
        // the zombies rather than off a white overlay. A frozen lawn is a picture this build has no
        // art for, and inventing one would say "this cell is ice" about a cell that is not.
        return MutationEffects.NONE;
    }

    @Override
    public java.util.Optional<String> announcement(LevelServer level, Mutation.Roll roll,
                                                  Object state) {
        return java.util.Optional.of("结冰地面：僵尸跑得更快，冰冻减速也更久");
    }

    /** What this activation remembers: the factor each rule was written with. */
    private record Applied(float speedFactor, float chillFactor) {
    }
}
