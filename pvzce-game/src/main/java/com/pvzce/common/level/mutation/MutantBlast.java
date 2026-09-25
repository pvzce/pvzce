package com.pvzce.common.level.mutation;

import com.pvzce.api.content.DamageTypeDef;
import com.pvzce.api.util.Identifier;
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
 * <p>The original's Potato Mine, exactly: a 0.5-cell square footprint, the {@code pvzce:ash} damage
 * type (armour does not absorb it - that is why a mine kills a Buckethead), and a puff of smoke.
 * Shared by the two blast mutations because they differ only in <em>whose</em> death sets it off,
 * and two copies of these numbers would eventually disagree about what a potato mine is.
 *
 * <p>Friendly fire is the point of these two mutations and it is written out by hand rather than
 * asked of {@code damageArea}: that method is the engine's "a blast hits the other side" and
 * deliberately skips everything on the source's team. A mutation that says "不分敌我" has to do the
 * other half itself.
 *
 * <h2>The two ceilings</h2>
 *
 * <p>A mutation that fires on <em>every</em> death is a chain reaction waiting to happen: one
 * zombie dies, the blast kills the next, that one's blast kills a third. The reported symptom was
 * exactly that, plus the muzzle flash of a potato mine going off several times a second. Both
 * halves are answered here rather than by weakening the numbers one at a time:
 *
 * <ul>
 *   <li><b>A blast may at most kill a normal zombie.</b> The ceiling is read from the registry, not
 *       written down, so retuning the basic zombie moves the ceiling with it.</li>
 *   <li><b>A blast may at most halve a peashooter.</b> That is the plant side's ceiling, and it is
 *       what stops the chain among plants: nothing short of two blasts can take one down.</li>
 * </ul>
 *
 * <p>The flash is gone with them. {@code POTATO_MINE_FLASH} is a near-white full-cell quad, which
 * is the right punctuation for one deliberate mine and unreadable when it is the background of the
 * whole lawn. What is left is the smoke, which says "something happened here" without strobing.
 */
public final class MutantBlast {
    /** The radius the shipped Potato Mine declares, in cells. */
    private static final float RADIUS_CELLS = 0.5F;
    /** The damage the shipped Potato Mine declares, before the ceilings below. */
    private static final int DAMAGE = 1800;
    /** The zombie a blast is not allowed to be stronger than, and the plant it may only halve. */
    private static final Identifier ORDINARY_ZOMBIE = Identifier.withDefaultNamespace("basic_zombie");
    private static final Identifier ORDINARY_PLANT =
            Identifier.withDefaultNamespace("pea_shooter");

    private MutantBlast() {
    }

    /**
     * The most damage one blast may do to a zombie: exactly one ordinary zombie's health.
     *
     * <p>Read from the registry so the ceiling is a fact about the game rather than a number that
     * silently stops meaning what it says the first time the basic zombie is retuned.
     */
    public static int zombieDamage() {
        com.pvzce.api.content.ZombieDef basic = BuiltInRegistries.ZOMBIES.get(ORDINARY_ZOMBIE);
        return basic == null ? DAMAGE : Math.min(DAMAGE, Math.max(1, basic.health()));
    }

    /**
     * The most damage one blast may do to a plant: half an ordinary plant's health.
     *
     * <p>Half rather than "one less than a peashooter" on purpose: the reported ask was "只能使豌豆
     * 血量掉一半", and half also means a plant standing in two overlapping blasts still survives the
     * first one, which is what breaks the chain.
     */
    public static int plantDamage() {
        com.pvzce.api.content.PlantDef ordinary =
                BuiltInRegistries.PLANTS.get(ORDINARY_PLANT);
        return ordinary == null ? DAMAGE : Math.min(DAMAGE, Math.max(1, ordinary.health() / 2));
    }

    /**
     * Detonates once at a point, hurting every plant and zombie within the footprint.
     *
     * @param sourceTeam the team whose <em>enemies</em> the ordinary damage pass hits; the plants'
     *                   own half is applied to both sides regardless, because a Potato Mine's
     *                   blast does not check whose plants are standing in it
     */
    public static void detonate(LevelServer level, float x, float y, Team sourceTeam) {
        DamageTypeDef ash = BuiltInRegistries.DAMAGE_TYPES.get(PvzceIds.DAMAGE_ASH);
        DamageTypeDef type = ash == null
                ? ZombieEntity.damageType(PvzceIds.DAMAGE_ASH) : ash;
        int zombieDamage = zombieDamage();
        if (type != null) {
            // `square` adds the half-cell term itself, so the radius stays the mine's own number.
            level.damageArea(type, x, y, RADIUS_CELLS, zombieDamage, sourceTeam, true);
        }
        for (PvzceEntity entity : level.entities()) {
            if (entity instanceof ZombieEntity zombie && zombie.isAlive()
                    && !LevelServer.isEnemyOf(zombie.team(), sourceTeam)
                    && within(zombie.cellX(), zombie.cellY(), x, y)) {
                // The friendly-fire half of the zombie blast: a zombie standing in its own side's
                // explosion takes it too. `damageArea` skipped it on purpose - it is the engine's
                // "a blast hits the other side" - and this is the mutation saying otherwise.
                zombie.damage(zombieDamage, type, level);
            }
        }
        int plantDamage = plantDamage();
        for (PvzceEntity entity : level.entities()) {
            if (entity instanceof PlantEntity plant && !plant.isRemoved()
                    && within(plant.cellX(), plant.cellY(), x, y)) {
                // Deliberately `damage` and not `damageFrom`: a bomb sitting on its fuse should be
                // caught by the blast that goes off beside it, and `damageFrom` would let its
                // `isInvulnerable` arming state shrug this off. The consequence - a blast can
                // damage a plant the ordinary bite rules would not reach - is bounded by the
                // ceiling above.
                plant.damage(plantDamage);
            }
        }
        // Smoke, not the mine's white flash: see the class doc. The sound stays, because a
        // silent explosion reads as a dropped frame.
        level.emitEffect(PvzceParticles.EXPLOSION_POW.toString(), x, y,
                PvzceSounds.EFFECT_EXPLOSION);
    }

    /** True when a point is inside the square footprint a potato mine covers. */
    private static boolean within(float cellX, float cellY, float centerX, float centerY) {
        return Math.abs(cellX - centerX) <= RADIUS_CELLS + 0.5F
                && Math.abs(cellY - centerY) <= RADIUS_CELLS + 0.5F;
    }
}
