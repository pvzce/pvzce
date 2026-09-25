package com.pvzce.common.level.mutation;

import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.server.level.LevelServer;


/**
 * Extra sun falls from the sky on a clock of the mutation's own: "阳光暴雨".
 *
 * <p>A gift rather than a curse, and the only mutation in the catalogue that is purely one - which
 * is why it is written as its own class instead of a {@code RateMutation}: the sun rate rule scales
 * what the level already does, and this adds a second, independent shower. A level whose own sky is
 * silent (2-5 pays for kills instead) still gets rain here, and that is the point: the mutation is
 * not "twice as much sun", it is "there is a shower now".
 *
 * <p>The sun lands as an ordinary drop the player collects by hand, because that is what a sun is -
 * the auto-collect buff is what makes it free, and a mutation that quietly banked sun would be
 * either invisible or a different buff.
 */
final class SunShowerMutation implements Mutation, MutationManager.SaveHandle {
    /** The gap between two extra suns, before the tier's multiplier. */
    private static final int SHOWER_INTERVAL_TICKS = 600;
    /**
     * How far right the shower lands, as a fraction of the board.
     *
     * <p>The sky's own drops use the whole width; this one is aimed at the lawn the player is
     * actually using, because a gift that lands in the far right column is a gift the player has to
     * walk into a wave to collect.
     */
    private static final float SHOWER_SPAN = 0.75F;

    @Override
    public Identifier id() {
        return PvzceIds.MUTATION_SUN_SHOWER;
    }

    @Override
    public Object apply(LevelServer level, Mutation.Roll roll) {
        return new Applied(level.mutations().difficulty().scaledInterval(SHOWER_INTERVAL_TICKS));
    }

    /**
     * On a restore the clock comes back and no sun appears.
     *
     * <p>The suns it already dropped are entities in the save; dropping one more at load would be
     * the mutation paying out twice for the same wait.
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
    @Override
    public void savingState(Object state) {
        this.savingState = state instanceof Applied applied ? applied : null;
    }

    @Override
    public void tick(LevelServer level, Mutation.Roll roll, Object state) {
        if (!(state instanceof Applied applied)) {
            return;
        }
        if (--applied.ticksUntilDrop > 0) {
            return;
        }
        applied.ticksUntilDrop = applied.interval();
        int span = Math.max(1, Math.round(level.width() * SHOWER_SPAN));
        int x = level.random().nextInt(span);
        int y = level.random().nextInt(Math.max(1, level.height()));
        level.dropSkySun(x + 0.5F, y + 0.5F);
    }

    @Override
    public java.util.Optional<String> announcement(LevelServer level, Mutation.Roll roll,
                                                  Object state) {
        return java.util.Optional.of("阳光暴雨：天上开始额外掉阳光");
    }

    /** This activation's countdown. */
    private static final class Applied {
        private final int interval;
        private int ticksUntilDrop;

        private Applied(int intervalTicks) {
            this.interval = Math.max(1, intervalTicks);
            this.ticksUntilDrop = this.interval;
        }

        int interval() {
            return interval;
        }

        void save(CompoundTag tag) {
            tag.putInt("Interval", interval);
            tag.putInt("TicksUntilDrop", ticksUntilDrop);
        }

        static Applied load(CompoundTag tag) {
            if (tag == null || !tag.contains("Interval")) {
                return null;
            }
            Applied applied = new Applied(tag.getInt("Interval"));
            applied.ticksUntilDrop = Math.max(1, tag.getInt("TicksUntilDrop"));
            return applied;
        }
    }
}
