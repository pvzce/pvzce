package com.pvzce.common.level.mutation;

import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.level.mechanic.MowerMechanic;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.server.level.LevelServer;

import java.util.ArrayList;
import java.util.List;

/**
 * The lawn mowers stop being a one-off: "割草机补给" and "割草机自走".
 *
 * <p>Two mutations with one idea and two clocks - a spent mower comes back, or a parked one leaves
 * on its own - so they are one class parameterised by which of the two it does. Both go through the
 * rig the level already has ({@code MowerMechanic.Rig}), which is what keeps a restored mower
 * identical to one that was never used: same row, same art, same sound, and spent again once it
 * rolls.
 *
 * <p><b>Supply</b> is a gift and <b>release</b> is a small disaster, and the difference is worth
 * stating because the two share every line of code: a released mower rolls across the lane and is
 * gone, so the player loses that row's last line of defence - possibly while nothing is in the lane
 * at all. That is what makes it a mutation rather than a buff.
 *
 * <p>{@code random_supply} replaces the originally proposed "植物老化" and {@code auto_release} the
 * originally proposed "割草机失灵"; the user swapped both, which is why these are the ids the plan
 * settled on.
 */
final class MowerMutation implements Mutation, MutationManager.SaveHandle {
    /** Which of the two things this activation does. */
    enum Duty {
        /** A spent mower is restored to its row. */
        SUPPLY,
        /** A parked mower is sent out on its own. */
        RELEASE
    }

    /** The gap between two acts, before the tier's multiplier. */
    private static final int SUPPLY_INTERVAL_TICKS = 1800;
    private static final int RELEASE_INTERVAL_TICKS = 2400;

    private final Identifier id;
    private final Duty duty;
    private final int baseInterval;

    private MowerMutation(Identifier id, Duty duty, int baseInterval) {
        this.id = id;
        this.duty = duty;
        this.baseInterval = baseInterval;
    }

    /** The two shipped mower mutations. */
    static List<Mutation> all() {
        return List.of(
                new MowerMutation(PvzceIds.MUTATION_RANDOM_SUPPLY, Duty.SUPPLY,
                        SUPPLY_INTERVAL_TICKS),
                new MowerMutation(PvzceIds.MUTATION_AUTO_RELEASE, Duty.RELEASE,
                        RELEASE_INTERVAL_TICKS));
    }

    @Override
    public Identifier id() {
        return id;
    }

    @Override
    public boolean canRun(LevelServer level) {
        // A level with no mowers at all (Wall-nut Bowling) has nothing for either half to do: the
        // rig is created by the mower mechanic, and the mechanic is implicit on every ordinary
        // level - so "is there a rig" is the question, asked without creating one.
        return rig(level) != null;
    }

    /** The level's mower rig, or {@code null} when this level has no mowers. */
    private static MowerMechanic.Rig rig(LevelServer level) {
        Object state = level.mechanicStateOrNull(PvzceIds.MECHANIC_MOWER, MowerMechanic.Rig.class);
        return state instanceof MowerMechanic.Rig rig ? rig : null;
    }

    @Override
    public Object apply(LevelServer level, Mutation.Roll roll) {
        return new Applied(level.mutations().difficulty().scaledInterval(baseInterval));
    }

    /**
     * On a restore the clock comes back.
     *
     * <p>What it already did is in the save: a restored mower is a mower in its row (the rig's own
     * save block), and a released one is an entity that is rolling. Doing either again on load would
     * either spend a mower the player still had or hand back one they had lost.
     */
    @Override
    public Object applyFromSave(LevelServer level, Mutation.Roll roll) {
        return pendingState;
    }

    @Override
    public void tick(LevelServer level, Mutation.Roll roll, Object state) {
        if (!(state instanceof Applied applied)) {
            return;
        }
        if (--applied.ticksUntilAct > 0) {
            return;
        }
        applied.ticksUntilAct = applied.interval();
        MowerMechanic.Rig rig = rig(level);
        if (rig == null) {
            return;
        }
        if (duty == Duty.SUPPLY) {
            Integer row = spentRow(level, rig);
            if (row != null) {
                rig.restore(row);
            }
            return;
        }
        List<Integer> ready = rowsInState(rig, MowerMechanic.STATE_READY);
        if (!ready.isEmpty()) {
            rig.release(ready.get(level.random().nextInt(ready.size())));
        }
    }

    /** A row whose mower is spent, or {@code null} when none is. */
    private static Integer spentRow(LevelServer level, MowerMechanic.Rig rig) {
        List<Integer> spent = rowsInState(rig, MowerMechanic.STATE_USED);
        if (spent.isEmpty()) {
            return null;
        }
        return spent.get(level.random().nextInt(spent.size()));
    }

    /** Every row whose mower is in this state, ascending. */
    private static List<Integer> rowsInState(MowerMechanic.Rig rig, int state) {
        List<Integer> rows = new ArrayList<>();
        for (MowerMechanic.Row row : rig.state().rows()) {
            if (row.state() == state) {
                rows.add(row.row());
            }
        }
        java.util.Collections.sort(rows);
        return rows;
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

    @Override
    public java.util.Optional<String> announcement(LevelServer level, Mutation.Roll roll,
                                                  Object state) {
        return java.util.Optional.of(duty == Duty.SUPPLY
                ? "割草机补给：用掉的小推车会自己回来"
                : "割草机自走：还没用的小推车会自己冲出去");
    }

    /** The clock as it stands, set by the manager around {@link #saveState}. */
    private Applied savingState;
    /** What {@link #loadState} read, waiting for {@link #applyFromSave} to consume it. */
    private Applied pendingState;

    @Override
    public void savingState(Object state) {
        this.savingState = state instanceof Applied applied ? applied : null;
    }

    /** This activation's countdown. */
    private static final class Applied {
        private final int interval;
        private int ticksUntilAct;

        private Applied(int intervalTicks) {
            this.interval = Math.max(1, intervalTicks);
            this.ticksUntilAct = this.interval;
        }

        int interval() {
            return interval;
        }

        void save(CompoundTag tag) {
            tag.putInt("Interval", interval);
            tag.putInt("TicksUntilAct", ticksUntilAct);
        }

        static Applied load(CompoundTag tag) {
            if (tag == null || !tag.contains("Interval")) {
                return null;
            }
            Applied applied = new Applied(tag.getInt("Interval"));
            applied.ticksUntilAct = Math.max(1, tag.getInt("TicksUntilAct"));
            return applied;
        }
    }
}
