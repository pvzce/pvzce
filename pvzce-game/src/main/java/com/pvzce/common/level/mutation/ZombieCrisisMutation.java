package com.pvzce.common.level.mutation;

import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.server.level.LevelServer;

import java.util.List;

/**
 * One zombie, over and over, until something stops it: "the Buckethead crisis".
 *
 * <p>The subject is rolled out of {@code #pvzce:mutation_crisis} and is the mutation's whole
 * identity - the panel shows "僵尸危机：铁桶僵尸", and a second crisis with a different roll is a
 * different entry that stacks with the first. That is why the zombie travels in the
 * {@link Mutation.Roll} rather than being picked per spawn: the mutation has to mean the same thing
 * for as long as it is on the field.
 *
 * <p>They walk in from the right edge like any other zombie rather than rising out of the ground,
 * so the level's own pacing still applies: a crisis fills the road, it does not bypass it.
 */
final class ZombieCrisisMutation implements Mutation {
    /** The gap between two zombies, before the tier's multiplier. */
    private static final int SPAWN_INTERVAL_TICKS = 100;
    /** How many the mutation may have on the lawn at once, so a long run cannot lock the tick up. */
    private static final int MAX_ALIVE = 12;

    @Override
    public Identifier id() {
        return PvzceIds.MUTATION_ZOMBIE_CRISIS;
    }

    @Override
    public boolean canRun(LevelServer level) {
        return !MutantZombies.crisisPool().isEmpty();
    }

    @Override
    public Mutation.Roll roll(LevelServer level) {
        List<Identifier> pool = MutantZombies.crisisPool();
        if (pool.isEmpty()) {
            return Mutation.Roll.NONE;
        }
        return Mutation.Roll.of(pool.get(level.random().nextInt(pool.size())));
    }

    @Override
    public Object apply(LevelServer level, Mutation.Roll roll) {
        if (roll.subject().isEmpty()) {
            return null;
        }
        return new Applied(level.mutations().difficulty().scaledInterval(SPAWN_INTERVAL_TICKS));
    }

    /**
     * On a restore the clock comes back, so the next zombie of this crisis arrives when it was
     * due rather than immediately. The subject rode in the {@link Mutation.Roll}, which the save
     * carries, and the zombies already on the lawn came back as entities.
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
        if (!(state instanceof Applied applied) || roll.subject().isEmpty()) {
            return;
        }
        if (level.hostileZombieCount() >= MAX_ALIVE) {
            return;
        }
        if (--applied.ticksUntilSpawn > 0) {
            return;
        }
        applied.ticksUntilSpawn = applied.interval();
        int row = level.random().nextInt(level.height());
        // Off the right edge, like a wave: a crisis is pressure from the road, not a spawn on the
        // lawn the player never sees coming.
        level.spawnZombie(roll.subject().get(), level.width() + 0.5F, row);
    }

    /** This activation's countdown. */
    private static final class Applied {
        private final int interval;
        private int ticksUntilSpawn;

        private Applied(int intervalTicks) {
            this.interval = Math.max(1, intervalTicks);
            this.ticksUntilSpawn = this.interval;
        }

        int interval() {
            return interval;
        }

        void save(CompoundTag tag) {
            tag.putInt("Interval", interval);
            tag.putInt("TicksUntilSpawn", ticksUntilSpawn);
        }

        static Applied load(CompoundTag tag) {
            if (tag == null || !tag.contains("Interval")) {
                return null;
            }
            Applied applied = new Applied(tag.getInt("Interval"));
            applied.ticksUntilSpawn = Math.max(1, tag.getInt("TicksUntilSpawn"));
            return applied;
        }
    }
}
