package com.pvzce.common.level.mutation;

import com.pvzce.api.content.PlantDef;
import com.pvzce.api.content.SceneElementDef;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.core.PlantPlacement;
import com.pvzce.common.tag.PvzceTags;
import com.pvzce.server.entity.PlantEntity;
import com.pvzce.server.level.LevelServer;

/**
 * Where a tangle kelp grows next: the rule the mutation and the buff share.
 *
 * <p>Extracted rather than written twice because the two are the <em>same</em> rule with different
 * owners - the mutation spreads a kelp every few seconds on its own, and the buff spreads one when
 * the player plants one - and two copies of "a water cell next to a kelp, four-neighbour, skipping
 * cells that already have one" would eventually disagree about what "next to" means.
 *
 * <p>The user's own words for it: "种植一个水草后，会在旁边或上下的空格种植另外一株水草，
 * 除非四面都有植物或不是水池" - which is exactly four neighbours, water only, and nothing already
 * growing there.
 */
public final class KelpSpread {

    private KelpSpread() {
    }

    /**
     * Grows one more kelp next to {@code source}, if there is anywhere to grow it.
     *
     * @return true when a new kelp appeared
     */
    public static boolean spreadFrom(LevelServer level, int fromX, int fromY) {
        PlantDef kelp = com.pvzce.common.core.BuiltInRegistries.PLANTS.get(PvzceIds.TANGLE_KELP);
        if (kelp == null || level.plantPlayer() == null) {
            return false;
        }
        int[] target = nearbyWater(level, fromX, fromY);
        if (target == null) {
            return false;
        }
        // Whatever was growing there goes first: replacing a plant is not something `spawnPlant`
        // does, and leaving the old one would put two plants in one cell.
        PlantEntity occupant = level.plantAt(target[0], target[1]);
        if (occupant != null) {
            occupant.remove();
            level.requestEntitySync();
        }
        level.spawnPlant(kelp, level.plantPlayer().team(), target[0], target[1]);
        level.emitEffect(com.pvzce.common.PvzceParticles.POOL_SPLASH.toString(),
                target[0] + 0.5F, target[1] + 0.5F, com.pvzce.common.PvzceSounds.ZOMBIE_SPLASH);
        return true;
    }

    /**
     * The first tangle kelp already on the lawn, or {@code null}.
     *
     * <p>The mutation needs it - it spreads "from a kelp that is already in the water", so no kelp
     * planted means no spread, which is why the mutation is worth a panel entry even before it does
     * anything.
     */
    public static PlantEntity firstKelp(LevelServer level) {
        for (PlantEntity plant : allPlants(level)) {
            if (PvzceIds.TANGLE_KELP.equals(plant.def().id())) {
                return plant;
            }
        }
        return null;
    }

    /** True when the board has any water at all, for the "may it run" answer. */
    public static boolean hasWater(LevelServer level) {
        for (int x = 0; x < level.width(); x++) {
            for (int y = 0; y < level.height(); y++) {
                if (isWater(level, x, y)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static java.util.List<PlantEntity> allPlants(LevelServer level) {
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
}
