package com.pvzce.common.level.mutation;

import com.pvzce.api.content.PlantDef;
import com.pvzce.api.content.SceneElementDef;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceConstants;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.PvzceParticles;
import com.pvzce.common.PvzceSounds;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.core.PlantPlacement;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.common.tag.PvzceTags;
import com.pvzce.server.entity.PlantEntity;
import com.pvzce.server.level.LevelServer;

import java.util.List;

/**
 * A planted Tangle Kelp spreads through the water, taking the water plants with it.
 *
 * <p>The rule has two halves and both are the mutation: the kelp walks to every water cell it can
 * reach, and a cell that already had a Lily Pad (or anything else living in the water) ends up
 * holding kelp instead. That second half is what makes it a mutation rather than a free plant - a
 * pool lawn is built on Lily Pads, and this eats them.
 *
 * <p>It spreads on its own clock rather than on the kelp's: the plants it makes are ordinary Tangle
 * Kelp, and an ordinary Tangle Kelp does not spread. The mutation is the thing that keeps going,
 * which is also why it stops the moment it is evicted - the kelp already in the water stays, since
 * it is a plant the player now owns.
 */
final class KelpSpreadMutation implements Mutation, MutationHooks {
    /** How often the water gains a kelp, before the tier's multiplier. */
    private static final int SPREAD_INTERVAL_TICKS = PvzceConstants.TICKS_PER_SECOND;
    /** A ceiling on how many plants this mutation may create, so a long run stays bounded. */
    private static final int MAX_SPREAD = 40;

    @Override
    public Identifier id() {
        return PvzceIds.MUTATION_KELP_SPREAD;
    }

    @Override
    public boolean canRun(LevelServer level) {
        return BuiltInRegistries.PLANTS.get(PvzceIds.TANGLE_KELP) != null
                && KelpSpread.hasWater(level);
    }

    @Override
    public Object apply(LevelServer level, Mutation.Roll roll) {
        return new Applied(level.mutations().difficulty().scaledInterval(SPREAD_INTERVAL_TICKS));
    }

    /**
     * On a restore nothing is spread: the kelp this mutation grew is part of the scene, and the
     * plants it replaced are gone. Its clock and its budget come back, so the next spread is due
     * when it was and the ceiling is still where it was.
     */
    @Override
    public Object applyFromSave(LevelServer level, Mutation.Roll roll) {
        return pendingState;
    }

    @Override
    public CompoundTag saveState() {
        CompoundTag tag = new CompoundTag();
        if (savingState != null) {
            savingState.save(tag);
        }
        return tag;
    }

    @Override
    public void loadState(CompoundTag tag) {
        this.pendingState = Applied.load(tag);
    }

    /** The clock as it stands, set by the manager around {@link #saveState}. */
    private Applied savingState;
    /** What {@link #loadState} read, waiting for {@link #applyFromSave} to consume it. */
    private Applied pendingState;

    /** How the manager hands this mutation the state it should write down. */
    void savingState(Object state) {
        this.savingState = state instanceof Applied applied ? applied : null;
    }

    @Override
    public void tick(LevelServer level, Mutation.Roll roll, Object state) {
        if (!(state instanceof Applied applied) || applied.spread >= MAX_SPREAD) {
            return;
        }
        if (--applied.ticksUntilSpread > 0) {
            return;
        }
        applied.ticksUntilSpread = applied.interval();
        PlantDef kelp = BuiltInRegistries.PLANTS.get(PvzceIds.TANGLE_KELP);
        if (kelp == null || level.plantPlayer() == null) {
            return;
        }
        // From a kelp that is already in the water, so "蔓延" is literally what happens: no kelp
        // planted means no spread, which is why the mutation is worth a panel entry even before it
        // does anything.
        PlantEntity source = KelpSpread.firstKelp(level);
        if (source == null) {
            return;
        }
        // The rule itself lives in `KelpSpread`, shared with the buff the same effect is handed
        // out as at 3-9; this class owns only the clock and the budget.
        if (KelpSpread.spreadFrom(level, source.gridX(), source.gridY())) {
            applied.spread++;
        }
    }

    /** This activation's clock and its budget of new plants. */
    private static final class Applied {
        private final int interval;
        private int ticksUntilSpread;
        private int spread;

        private Applied(int intervalTicks) {
            this.interval = Math.max(1, intervalTicks);
            this.ticksUntilSpread = this.interval;
            this.spread = 0;
        }

        int interval() {
            return interval;
        }

        void save(CompoundTag tag) {
            tag.putInt("Interval", interval);
            tag.putInt("TicksUntilSpread", ticksUntilSpread);
            tag.putInt("Spread", spread);
        }

        static Applied load(CompoundTag tag) {
            if (tag == null || !tag.contains("Interval")) {
                return null;
            }
            Applied applied = new Applied(tag.getInt("Interval"));
            applied.ticksUntilSpread = Math.max(1, tag.getInt("TicksUntilSpread"));
            applied.spread = Math.max(0, tag.getInt("Spread"));
            return applied;
        }
    }
}
