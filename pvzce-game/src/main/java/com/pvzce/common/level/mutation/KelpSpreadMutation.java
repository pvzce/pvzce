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
        return BuiltInRegistries.PLANTS.get(PvzceIds.TANGLE_KELP) != null && hasWater(level);
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
        PlantEntity source = firstKelp(level);
        if (source == null) {
            return;
        }
        int[] target = nearbyWater(level, source.gridX(), source.gridY());
        if (target == null) {
            return;
        }
        // Whatever was growing there goes first: replacing a plant is not something `spawnPlant`
        // does, and leaving the old one would put two plants in one cell.
        PlantEntity occupant = level.plantAt(target[0], target[1]);
        if (occupant != null) {
            occupant.remove();
            level.requestEntitySync();
        }
        level.spawnPlant(kelp, level.plantPlayer().team(), target[0], target[1]);
        applied.spread++;
        level.emitEffect(PvzceParticles.POOL_SPLASH.toString(), target[0] + 0.5F, target[1] + 0.5F,
                PvzceSounds.ZOMBIE_SPLASH);
    }

    /** The first Tangle Kelp on the lawn, or {@code null} when the player has planted none. */
    private static PlantEntity firstKelp(LevelServer level) {
        for (PlantEntity plant : allPlants(level)) {
            if (PvzceIds.TANGLE_KELP.equals(plant.def().id())) {
                return plant;
            }
        }
        return null;
    }

    private static List<PlantEntity> allPlants(LevelServer level) {
        return level.entities().stream()
                .filter(entity -> entity instanceof PlantEntity)
                .map(entity -> (PlantEntity) entity)
                .toList();
    }

    /**
     * A water cell next to a kelp, or {@code null} when every neighbour is already taken by kelp.
     *
     * <p>Four-neighbour rather than eight: the kelp is a plant standing in a cell, and a lawn that
     * spread diagonally would fill a pool in a quarter of the time with a shape that reads as
     * cheating. Cells that are not water are skipped, so a kelp at the pool's edge spreads along
     * the pool rather than onto the grass.
     */
    private static int[] nearbyWater(LevelServer level, int fromX, int fromY) {
        int[][] offsets = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        int start = level.random().nextInt(offsets.length);
        for (int i = 0; i < offsets.length; i++) {
            int[] offset = offsets[(start + i) % offsets.length];
            int x = fromX + offset[0];
            int y = fromY + offset[1];
            if (!level.inBounds(x, y) || !isWater(level, x, y)) {
                continue;
            }
            PlantEntity occupant = level.plantAt(x, y);
            if (occupant != null && PvzceIds.TANGLE_KELP.equals(occupant.def().id())) {
                continue;
            }
            return new int[]{x, y};
        }
        return null;
    }

    /** True when that cell's terrain is water, which is the only place a kelp may go. */
    private static boolean isWater(LevelServer level, int x, int y) {
        SceneElementDef base = level.sceneAt(x, y);
        return base != null && PlantPlacement.terrainTagged(PlantPlacement.Terrain.of(base),
                PvzceTags.SCENE_WATER);
    }

    /** True when the board has any water at all, for the "may it run" answer. */
    private static boolean hasWater(LevelServer level) {
        for (int x = 0; x < level.width(); x++) {
            for (int y = 0; y < level.height(); y++) {
                if (isWater(level, x, y)) {
                    return true;
                }
            }
        }
        return false;
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
