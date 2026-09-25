package com.pvzce.common.level.mutation;

import com.pvzce.api.content.SceneElementDef;
import com.pvzce.api.content.ResourceCost;
import com.pvzce.api.content.ToolData;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.level.SceneGrid;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.server.level.LevelServer;

import java.util.List;

/**
 * Whack-a-Zombie, as a mutation: the mallet in the player's hand, and graves that keep erupting.
 *
 * <p>Two halves, and both are needed for the mode to read as itself: the mallet is what the player
 * does about the zombies, and the graves are what there is to do something about. The player's
 * cards stay - a mutation that took the whole arsenal away would be {@code conveyor} or
 * {@code slot_replace} wearing a third name.
 *
 * <p>The rise clock is the tier's, not a constant: at HELL three zombies a second out of the ground
 * is the point, and at 简单 one every three seconds is survivable. The standing grave count scales
 * with the tier too, so a harder lawn has both more holes and more coming out of them.
 */
final class WhackAZombieMutation implements Mutation, MutationHooks {
    /** The gap between two zombies climbing out, before the tier's multiplier. */
    private static final int RISE_INTERVAL_TICKS = 100;
    /** How many graves the lawn is kept at, before the tier's multiplier. */
    private static final int GRAVE_TARGET = 8;
    /** A ceiling on holes: a 9x5 lawn has nowhere to put more, and the rest are wasted scans. */
    private static final int MAX_GRAVES = 40;

    @Override
    public Identifier id() {
        return PvzceIds.MUTATION_WHACK_A_ZOMBIE;
    }

    @Override
    public MutationEffects clientEffects() {
        return MutationEffects.WHACK_CURSOR;
    }

    @Override
    public boolean canRun(LevelServer level) {
        // With nothing to raise, a mallet alone is a mutation that does nothing at all - worse
        // than not appearing, because the panel would claim it is doing something.
        return !MutantZombies.crisisPool().isEmpty();
    }

    @Override
    public Object apply(LevelServer level, Mutation.Roll roll) {
        level.installTool(defaultHammer());
        return new Applied(Math.round(GRAVE_TARGET * roll.multiplier()),
                level.mutations().difficulty().scaledInterval(RISE_INTERVAL_TICKS));
    }

    /**
     * The mallet block this mutation hands over: the tool, free, and the level's plain click.
     *
     * <p>Written as a block rather than borrowed from 2-5's file, because the two levels are
     * allowed to price the same hammer differently - that is the whole point of a tool block.
     */
    private static ToolData defaultHammer() {
        return new ToolData(PvzceIds.HAMMER, true, 0, java.util.Optional.of(ResourceCost.FREE));
    }

    /**
     * On a restore the mallet is handed over again but no grave is raised.
     *
     * <p>The two halves are not the same kind of thing: the tool is a grant the level holds in a
     * list (a save does not carry it, and a resumed run has to be holding the mallet or the
     * mutation reads as having been half undone), while the graves it raised are <em>terrain</em>
     * and came back with the scene. A rerun that raised them again would double them.
     */
    @Override
    public Object applyFromSave(LevelServer level, Mutation.Roll roll) {
        level.installTool(defaultHammer());
        return pendingState == null
                ? new Applied(Math.round(GRAVE_TARGET * roll.multiplier()),
                        level.mutations().difficulty().scaledInterval(RISE_INTERVAL_TICKS))
                : pendingState;
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
        // The clock is rebuilt here and handed to `applyFromSave`, which is the call that follows.
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
    public void revert(LevelServer level, Mutation.Roll roll, Object state) {
        level.removeTool(PvzceIds.HAMMER);
    }

    @Override
    public void tick(LevelServer level, Mutation.Roll roll, Object state) {
        if (!(state instanceof Applied applied)) {
            return;
        }
        keepGravesUp(level, applied);
        if (--applied.ticksUntilRise > 0) {
            return;
        }
        applied.ticksUntilRise = applied.intervalTicks();
        raiseOne(level);
    }

    /** Tops the lawn back up to the target, two stones per tick at most. */
    private static void keepGravesUp(LevelServer level, Applied applied) {
        int present = level.graveCells().size();
        if (present >= applied.graveTarget()) {
            return;
        }
        int wanted = Math.min(2, applied.graveTarget() - present);
        for (int i = 0; i < wanted; i++) {
            int x = level.random().nextInt(level.width());
            int y = level.random().nextInt(level.height());
            level.placeGrave(MutantZombies.graveDesign(level.random()), x, y);
        }
    }

    /** One zombie out of one standing grave. */
    private static void raiseOne(LevelServer level) {
        List<SceneGrid.Cell<SceneElementDef>> graves = level.graveCells();
        List<Identifier> pool = MutantZombies.crisisPool();
        if (graves.isEmpty() || pool.isEmpty()) {
            return;
        }
        SceneGrid.Cell<SceneElementDef> grave = graves.get(level.random().nextInt(graves.size()));
        Identifier zombie = pool.get(level.random().nextInt(pool.size()));
        level.raiseZombieFromGrave(zombie, grave.x(), grave.y());
    }

    /**
     * This activation's run state.
     *
     * <p>Mutable and owned by the activation rather than by the (shared) mutation instance, which
     * is the rule every mutation follows: a registry entry serves every level at once.
     */
    private static final class Applied {
        private final int graveTarget;
        private final int intervalTicks;
        private int ticksUntilRise;

        private Applied(int graveTarget, int intervalTicks) {
            this.graveTarget = Math.min(MAX_GRAVES, Math.max(1, graveTarget));
            this.intervalTicks = Math.max(1, intervalTicks);
            this.ticksUntilRise = Math.max(1, intervalTicks / 2);
        }

        int graveTarget() {
            return graveTarget;
        }

        int intervalTicks() {
            return intervalTicks;
        }

        void save(CompoundTag tag) {
            tag.putInt("GraveTarget", graveTarget);
            tag.putInt("Interval", intervalTicks);
            tag.putInt("TicksUntilRise", ticksUntilRise);
        }

        /** The clock as it was saved, or {@code null} when the block is missing. */
        static Applied load(CompoundTag tag) {
            if (tag == null || !tag.contains("Interval")) {
                return null;
            }
            Applied applied = new Applied(tag.getInt("GraveTarget"), tag.getInt("Interval"));
            applied.ticksUntilRise = Math.max(1, tag.getInt("TicksUntilRise"));
            return applied;
        }
    }
}
