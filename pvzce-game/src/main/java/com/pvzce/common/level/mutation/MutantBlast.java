package com.pvzce.common.level.mutation;

import com.pvzce.api.content.DamageTypeDef;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.PvzceParticles;
import com.pvzce.common.PvzceSounds;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.server.Team;
import com.pvzce.server.entity.PlantEntity;
import com.pvzce.server.entity.PvzceEntity;
import com.pvzce.server.entity.ZombieEntity;
import com.pvzce.server.level.LevelServer;

/**
 * What "a potato-mine blast where this died" means, in one place.
 *
 * <p>The original's Potato Mine, exactly: a 0.5-cell square footprint, 1800 damage, the
 * {@code pvzce:ash} damage type (armour does not absorb it - that is why a mine kills a
 * Buckethead), and the little flash. Shared by the two blast mutations because they differ only in
 * <em>whose</em> death sets it off, and two copies of these numbers would eventually disagree about
 * what a potato mine is.
 *
 * <p>Friendly fire is the point of these two mutations and it is written out by hand rather than
 * asked of {@code damageArea}: that method is the engine's "a blast hits the other side" and
 * deliberately skips everything on the source's team. A mutation that says "不分敌我" has to do the
 * other half itself.
 */
final class MutantBlast {
    /** The radius the shipped Potato Mine declares, in cells. */
    private static final float RADIUS_CELLS = 0.5F;
    /** The damage the shipped Potato Mine declares. */
    private static final int DAMAGE = 1800;

    private MutantBlast() {
    }

    /**
     * Detonates once at a point, hurting every plant and zombie within the footprint.
     *
     * @param sourceTeam the team whose <em>enemies</em> the ordinary damage pass hits; the plants'
     *                   own half is applied to both sides regardless, because a Potato Mine's
     *                   blast does not check whose plants are standing in it
     */
    static void detonate(LevelServer level, float x, float y, Team sourceTeam) {
        DamageTypeDef ash = BuiltInRegistries.DAMAGE_TYPES.get(PvzceIds.DAMAGE_ASH);
        DamageTypeDef type = ash == null
                ? ZombieEntity.damageType(PvzceIds.DAMAGE_ASH) : ash;
        if (type != null) {
            // `square` adds the half-cell term itself, so the radius stays the mine's own number.
            level.damageArea(type, x, y, RADIUS_CELLS, DAMAGE, sourceTeam, true);
        }
        for (PvzceEntity entity : level.entities()) {
            if (entity instanceof ZombieEntity zombie && zombie.isAlive()
                    && !LevelServer.isEnemyOf(zombie.team(), sourceTeam)
                    && within(zombie.cellX(), zombie.cellY(), x, y)) {
                // The friendly-fire half of the zombie blast: a zombie standing in its own side's
                // explosion takes it too. `damageArea` skipped it on purpose - it is the engine's
                // "a blast hits the other side" - and this is the mutation saying otherwise.
                zombie.damage(DAMAGE, type, level);
            }
        }
        for (PvzceEntity entity : level.entities()) {
            if (entity instanceof PlantEntity plant && !plant.isRemoved()
                    && within(plant.cellX(), plant.cellY(), x, y)) {
                plant.damage(DAMAGE);
            }
        }
        level.emitEffect(PvzceParticles.POTATO_MINE_FLASH.toString(), x, y,
                PvzceSounds.EFFECT_EXPLOSION);
    }

    /** True when a point is inside the square footprint a potato mine covers. */
    private static boolean within(float cellX, float cellY, float centerX, float centerY) {
        return Math.abs(cellX - centerX) <= RADIUS_CELLS + 0.5F
                && Math.abs(cellY - centerY) <= RADIUS_CELLS + 0.5F;
    }
}
