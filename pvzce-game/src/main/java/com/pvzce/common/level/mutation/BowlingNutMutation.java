package com.pvzce.common.level.mutation;

import com.pvzce.api.content.PlantDef;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.server.level.LevelServer;

/**
 * Every Wall-nut planted becomes a bowling Wall-nut, and cards come back twice as fast.
 *
 * <p>The wall-nut bowling mini-game's two halves: the projectile-like nut ({@code pvzce:bowling_nut}
 * carries the {@code bowl} capability, so it rolls and ricochets and pays a coin per hit) and the
 * shorter recharge that makes throwing them away affordable. Both are needed - bowl nuts alone on
 * a 300-tick cooldown is a novelty, not a mini-game.
 *
 * <p>The substitution is a {@link MutationHooks#replacePlantedPlant} rather than a swap after the
 * fact, because capabilities are read from the definition when the entity is constructed: telling
 * a Wall-nut that it is a bowling ball afterwards would leave it with a Wall-nut's (empty)
 * capability list.
 */
final class BowlingNutMutation implements Mutation, MutationHooks {
    /** The cooldown factor: the original's bowling nut costs half its usual recharge. */
    private static final float COOLDOWN_FACTOR = 0.5F;

    @Override
    public Identifier id() {
        return PvzceIds.MUTATION_BOWLING_NUT;
    }

    @Override
    public PlantDef replacePlantedPlant(PlantDef def, int x, int y) {
        if (def == null || !PvzceIds.WALL_NUT.equals(def.id())) {
            return null;
        }
        return BuiltInRegistries.PLANTS.get(PvzceIds.BOWLING_NUT);
    }

    @Override
    public Object apply(LevelServer level, Mutation.Roll roll) {
        // Divided rather than multiplied: the rule reads as "how long cards take", so halving the
        // cooldown is a factor below one. A mutation that rolls high should be the *harsher*
        // reading, and for a cooldown the harsher reading is a longer wait - which is what
        // `1 / factor` gives, and why this one is written out instead of reusing RateMutation.
        float factor = COOLDOWN_FACTOR;
        level.setRule(PvzceIds.RULE_MUTATION_SEED_COOLDOWN_FACTOR,
                level.rules().getFloat(PvzceIds.RULE_MUTATION_SEED_COOLDOWN_FACTOR) * factor);
        return new Applied(factor);
    }

    /**
     * On a restore the cooldown factor is applied again, for the same reason a rate mutation's is:
     * the level's rules are in the save and this factor is not.
     */
    @Override
    public Object applyFromSave(LevelServer level, Mutation.Roll roll) {
        level.setRule(PvzceIds.RULE_MUTATION_SEED_COOLDOWN_FACTOR,
                level.rules().getFloat(PvzceIds.RULE_MUTATION_SEED_COOLDOWN_FACTOR) * COOLDOWN_FACTOR);
        return new Applied(COOLDOWN_FACTOR);
    }

    @Override
    public void revert(LevelServer level, Mutation.Roll roll, Object state) {
        float factor = state instanceof Applied applied ? applied.factor() : COOLDOWN_FACTOR;
        level.setRule(PvzceIds.RULE_MUTATION_SEED_COOLDOWN_FACTOR,
                level.rules().getFloat(PvzceIds.RULE_MUTATION_SEED_COOLDOWN_FACTOR) / factor);
    }

    /** The factor this activation applied. */
    private record Applied(float factor) {
    }
}
