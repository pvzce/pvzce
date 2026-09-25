package com.pvzce.common.level.mutation;

import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.PvzceParticles;
import com.pvzce.common.PvzceSounds;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.server.entity.ZombieEntity;
import com.pvzce.server.level.LevelServer;

/**
 * Rocks fall on the lawn: "陨石雨".
 *
 * <p>A small blast and a crater, every so often, at a cell the player is not using: the mutation's
 * damage is aimed at the <em>board</em> rather than at the player's plants, so a cell that already
 * holds a plant is skipped. That is a deliberate limit and not a kindness - a mutation that cratered
 * whatever it liked would be a mutation that plays the game for the zombies, and the interesting
 * part of a lawn that keeps losing cells is that the player has to keep moving.
 *
 * <p>The blast itself is the engine's own ({@code LevelServer.damageArea} with the ash damage type,
 * which armour does not absorb) and so is the crater ({@code leaveCraters}), so a meteor is exactly
 * as strong as the game's own explosives and no stronger: the ceilings the user set for explosions
 * ("a plant explosion may only take half a peashooter") are the ones the blast respects.
 */
final class MeteorShowerMutation implements Mutation, MutationManager.SaveHandle {
    /** The gap between two rocks, before the tier's multiplier. */
    private static final int FALL_INTERVAL_TICKS = 900;
    /** How wide the impact is, in cells: one cell and its immediate neighbours. */
    private static final float IMPACT_RADIUS_CELLS = 0.5F;

    @Override
    public Identifier id() {
        return PvzceIds.MUTATION_METEOR_SHOWER;
    }

    @Override
    public Object apply(LevelServer level, Mutation.Roll roll) {
        return new Applied(level.mutations().difficulty().scaledInterval(FALL_INTERVAL_TICKS));
    }

    /**
     * On a restore the clock comes back and no rock falls.
     *
     * <p>What it already did is terrain: the craters are in the scene block and the damage is in the
     * entities' health, so repeating it would crater the lawn a second time.
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
        if (--applied.ticksUntilFall > 0) {
            return;
        }
        applied.ticksUntilFall = applied.interval();
        int cell = freeCell(level);
        if (cell < 0) {
            return;
        }
        int x = cell % level.width();
        int y = cell / level.width();
        // The catalogue's own blast, ceilings included: a rock hurts exactly as much as the game's
        // other explosions and no more, which is what keeps "a plant explosion may only take half a
        // peashooter" true here as well.
        MutantBlast.detonate(level, x + 0.5F, y + 0.5F, null);
        level.leaveCraters(x + 0.5F, y + 0.5F, IMPACT_RADIUS_CELLS, true);
        // Dust and a thud, at the cell it landed in: the crater is the mark it leaves, and this is
        // the moment of impact. Deliberately not a flash - the user's rule for every explosion in
        // this build is that they do not throw light - so the dust is a clump of earth and the
        // sound is the arrival.
        level.emitEffect(PvzceParticles.DIRT_BIG.toString(), x + 0.5F, y + 0.5F,
                PvzceSounds.EFFECT_EXPLOSION);
    }

    /**
     * A cell with no plant in it, or -1 when the lawn is full.
     *
     * <p>Rolled rather than scanned from a corner: the rock should be able to land anywhere the
     * player is not building, and "the first free cell" would put every meteor in the same place.
     * The roll is bounded (a full lawn gives up after a few tries) so a lawn the player has packed
     * does not turn every interval into a scan of the whole board.
     */
    private static int freeCell(LevelServer level) {
        int cells = level.width() * level.height();
        if (cells <= 0) {
            return -1;
        }
        for (int attempt = 0; attempt < 24; attempt++) {
            int candidate = level.random().nextInt(cells);
            int x = candidate % level.width();
            int y = candidate / level.width();
            if (level.plantsAt(x, y).isEmpty()) {
                return candidate;
            }
        }
        return -1;
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
        return java.util.Optional.of("陨石雨：天上开始掉石头，砸过的地方种不了东西");
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
        private int ticksUntilFall;

        private Applied(int intervalTicks) {
            this.interval = Math.max(1, intervalTicks);
            this.ticksUntilFall = this.interval;
        }

        int interval() {
            return interval;
        }

        void save(CompoundTag tag) {
            tag.putInt("Interval", interval);
            tag.putInt("TicksUntilFall", ticksUntilFall);
        }

        static Applied load(CompoundTag tag) {
            if (tag == null || !tag.contains("Interval")) {
                return null;
            }
            Applied applied = new Applied(tag.getInt("Interval"));
            applied.ticksUntilFall = Math.max(1, tag.getInt("TicksUntilFall"));
            return applied;
        }
    }
}
