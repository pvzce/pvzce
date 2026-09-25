package com.pvzce.common.level.mutation;

import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.PvzceConstants;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.server.level.LevelServer;

/**
 * A gravestone comes up out of the lawn every twenty seconds - fewer, at a harder tier.
 *
 * <p>Slower than {@code whack_a_zombie}'s eruption on purpose: this one changes the shape of the
 * board over a long run, taking cells away from the player one at a time, and a lawn that filled
 * up in thirty seconds would be a different mutation. The tier still scales it, so HELL grows a
 * stone every seven seconds or so.
 *
 * <p>The stones the mutation raises are <em>not</em> taken back when it leaves: they are terrain
 * now, the same as any other grave on the lawn, and removing them would delete whatever the player
 * built around them in the meantime. What the mutation owns is the clock.
 */
final class GraveGrowthMutation implements Mutation {
    /** The gap between two stones, before the tier's multiplier. */
    private static final int GROWTH_INTERVAL_TICKS = 20 * PvzceConstants.TICKS_PER_SECOND;
    /** A ceiling, for the same reason {@code whack_a_zombie} has one: the lawn is only so big. */
    private static final int MAX_GRAVES = 40;

    @Override
    public Identifier id() {
        return PvzceIds.MUTATION_GRAVE_GROWTH;
    }

    @Override
    public Object apply(LevelServer level, Mutation.Roll roll) {
        return new Applied(level.mutations().difficulty().scaledInterval(GROWTH_INTERVAL_TICKS),
                Math.min(MAX_GRAVES, Math.round(10 * roll.multiplier())));
    }

    /**
     * On a restore nothing is raised: the stones this mutation already grew are terrain, and they
     * came back with the scene. Only the clock is put back, so the next one arrives when it was
     * going to.
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
        if (!(state instanceof Applied applied)) {
            return;
        }
        if (--applied.ticksUntilGrowth > 0) {
            return;
        }
        applied.ticksUntilGrowth = applied.interval();
        if (level.graveCells().size() >= applied.cap()) {
            return;
        }
        // A random cell, and `placeGrave` refuses one that is occupied or holds a plant: the
        // mutation never takes a cell out from under something the player is using, which is what
        // keeps it a pressure rather than a thief.
        int x = level.random().nextInt(level.width());
        int y = level.random().nextInt(level.height());
        level.placeGrave(MutantZombies.graveDesign(level.random()), x, y);
    }

    /** This activation's clock and ceiling. */
    private static final class Applied {
        private final int interval;
        private final int cap;
        private int ticksUntilGrowth;

        private Applied(int intervalTicks, int graveCap) {
            this.interval = Math.max(1, intervalTicks);
            this.cap = Math.max(1, graveCap);
            this.ticksUntilGrowth = this.interval;
        }

        int interval() {
            return interval;
        }

        int cap() {
            return cap;
        }

        void save(CompoundTag tag) {
            tag.putInt("Interval", interval);
            tag.putInt("Cap", cap);
            tag.putInt("TicksUntilGrowth", ticksUntilGrowth);
        }

        static Applied load(CompoundTag tag) {
            if (tag == null || !tag.contains("Interval")) {
                return null;
            }
            Applied applied = new Applied(tag.getInt("Interval"), tag.getInt("Cap"));
            applied.ticksUntilGrowth = Math.max(1, tag.getInt("TicksUntilGrowth"));
            return applied;
        }
    }
}
