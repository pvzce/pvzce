package com.pvzce.common.level.mutation;

import com.pvzce.common.level.WorldPosition;
import com.pvzce.common.level.SceneBoard;
import com.pvzce.api.content.DamageTypeDef;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.PvzceParticles;
import com.pvzce.common.PvzceSounds;
import com.pvzce.common.core.BuiltInRegistries;
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
     * <h2>No chains</h2>
     *
     * <p>Every victim is <em>marked</em> as blast-killed before the damage lands, because a lethal
     * hit runs the death callback - and with it the mutation that would otherwise detonate again -
     * inside {@code damage} itself. The mark is what breaks the reaction: a zombie that dies to a
     * blast does not itself explode, and neither does a plant, so one death cannot cascade down a
     * lane. The ceilings above are what the mark cannot catch - two blasts landing on the same
     * body - so both rules stay.
     *
     * <p>The mark survives on a plant until the end of the tick because a plant's death is
     * processed in the level's removal pass rather than inside {@code damage}; it carries the tick
     * number for exactly that reason. A survivor is unmarked immediately, so a later death from a
     * pea is an ordinary death again.
     */
    public static void detonate(LevelServer level, float x, float y) {
        detonate(level, level.sceneBoard().ground(SceneBoard.DEFAULT_SURFACE, x, y),
                SceneBoard.DEFAULT_SURFACE);
    }
    public static void detonate(LevelServer level, WorldPosition center, String surface) {
        DamageTypeDef ash = BuiltInRegistries.DAMAGE_TYPES.get(PvzceIds.DAMAGE_ASH);
        DamageTypeDef type = ash == null
                ? ZombieEntity.damageType(PvzceIds.DAMAGE_ASH) : ash;
        int zombieDamage = zombieDamage();
        int plantDamage = plantDamage();
        int tick = level.tickCount();
        // One pass over every zombie in the footprint, friends included. This used to be
        // `damageArea` (enemies only) plus a hand-written friendly-fire half; the mutation's own
        // mark has to be laid down around every hit either way, and `damageArea` cannot do that.
        for (PvzceEntity entity : new java.util.ArrayList<>(level.entities())) {
            if (entity instanceof ZombieEntity zombie && zombie.isAlive()
                    && level.reachedByBlast(center, zombie, RADIUS_CELLS, true)) {
                zombie.markBlastDeath(tick);
                zombie.damage(zombieDamage, type, level);
                if (zombie.isAlive()) {
                    zombie.clearBlastDeath();
                }
            }
        }
        for (PvzceEntity entity : new java.util.ArrayList<>(level.entities())) {
            if (entity instanceof PlantEntity plant && !plant.isRemoved()
                    && level.reachedByBlast(center, plant, RADIUS_CELLS, true)) {
                // Deliberately `damage` and not `damageFrom`: a bomb sitting on its fuse should be
                // caught by the blast that goes off beside it, and `damageFrom` would let its
                // `isInvulnerable` arming state shrug this off. The consequence - a blast can
                // damage a plant the ordinary bite rules would not reach - is bounded by the
                // ceiling above.
                plant.markBlastDeath(tick);
                plant.damage(plantDamage);
                if (!plant.isRemoved() && plant.health() > 0) {
                    plant.clearBlastDeath();
                }
            }
        }
        // Smoke, not the mine's white flash: see the class doc. The sound stays, because a
        // silent explosion reads as a dropped frame.
        level.emitEffect(PvzceParticles.EXPLOSION_POW.toString(), center, surface,
                PvzceSounds.EFFECT_EXPLOSION);
    }

}
