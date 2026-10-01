package com.pvzce.api.content.capability;

import com.pvzce.api.content.ProjectileDef;
import com.pvzce.api.entity.LevelAccess;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.server.entity.ZombieEntity;

/**
 * One composable zombie behaviour (armor, vaulting, flying, digging, hammering,
 * boss phases).
 *
 * <p>The walking/eating loop stays in {@code ZombieEntity} because every zombie
 * shares it; capabilities only override the parts that differ. Hooks return
 * {@code false} / neutral values when they do not apply, so an unknown capability
 * can never silently disable base movement.
 */
public interface ZombieCapability {
    /** A carried metal object, independent of whether it protects the body. */
    default com.pvzce.api.util.Identifier magneticItem(ZombieEntity zombie) { return null; }

    default boolean removeMagneticItem(ZombieEntity zombie, LevelAccess level) { return false; }

    /** NaN leaves the shared walking direction in charge. */
    default float walkDirection(ZombieEntity zombie) { return Float.NaN; }

    default String eatState(ZombieEntity zombie) { return null; }

    /** Ordinary death may retain facing and removed-equipment state. */
    default String deathState(ZombieEntity zombie) { return null; }

    default ZombieCapability instantiate() {
        return this;
    }

    /** Side actions that run before movement (giant hammer, boss phases, ...). */
    default void tick(ZombieEntity zombie, LevelAccess level) {
    }

    /**
     * Takes over this tick's movement.
     *
     * @return {@code true} when the capability moved the zombie itself and the
     *         default walk/eat step must be skipped
     */
    default boolean tickMovement(ZombieEntity zombie, LevelAccess level) {
        return false;
    }

    /** Multiplies the base move speed (armor loss, charge phases, ...). */
    default float speedMultiplier(ZombieEntity zombie) {
        return 1F;
    }

    /**
     * The animation state the walking loop should publish, or {@code null} for the default.
     *
     * <p>A zombie that carries something changes how it walks, not whether it walks: the
     * pole vaulter jogs with its pole held out until it has vaulted, and walks empty-handed
     * afterwards. The state is asked for rather than set from {@link #tick} because the walk
     * loop publishes its state after the capabilities have run - anything a capability set
     * there would be overwritten in the same tick (which is the whole reason a vaulting
     * zombie's jump was never visible).
     */
    default String walkState(ZombieEntity zombie) {
        return null;
    }

    /**
     * Whether the zombie enters the level airborne (balloon zombies). The entity
     * asks its capabilities instead of hardcoding which one flies, so a new
     * airborne capability works without touching {@code ZombieEntity}.
     */
    default boolean spawnsAirborne() {
        return false;
    }

    /** Overrides the render/logic layer, or {@link Integer#MIN_VALUE} for the default. */
    default int layerOverride(ZombieEntity zombie) {
        return Integer.MIN_VALUE;
    }

    /** Whether ground-layer projectiles may hit this zombie. */
    default boolean canBeHitByGround(ZombieEntity zombie) {
        return true;
    }

    /**
     * Intercepts incoming projectile damage.
     *
     * @return {@code true} when the capability fully handled the hit (armor
     *         absorbed it) and the body must not take damage
     */
    default boolean onProjectileHit(ZombieEntity zombie, ProjectileDef projectile, int damage, LevelAccess level) {
        return false;
    }

    /**
     * Intercepts a non-projectile impact (a rolling bowling nut, a giant's fist).
     *
     * <p>Separate from {@link #onProjectileHit} because there is no shot to describe the
     * hit with - no layer to choose top or front armor from, no impact sound - while the
     * rule that armor absorbs before the body still applies. {@code false} means the
     * capability has nothing to say and the body takes the damage.
     */
    default boolean onImpact(ZombieEntity zombie, int damage, LevelAccess level) {
        return false;
    }

    /**
     * As above, for a hit whose damage type the caller knows.
     *
     * <p>The type is what says whether a shield counts for this hit at all - see
     * {@code DamageTypeDef.ignoresFrontArmor}, which is the fume-shroom's spray going through a
     * screen door while a cone still stops it. Kept as a separate overload rather than a changed
     * signature so an existing capability keeps compiling and keeps its behaviour: the default
     * here is the three-argument answer.
     */
    default boolean onImpact(ZombieEntity zombie, int damage, LevelAccess level,
                             com.pvzce.api.content.DamageTypeDef type) {
        return onImpact(zombie, damage, level);
    }

    /** Called once when the zombie's health reaches zero. */
    default void onDeath(ZombieEntity zombie, LevelAccess level) {
    }

    /**
     * A container let this zombie out, rather than a wave putting it down in a lane.
     *
     * <p>The vase level's pots are the case: a zombie that comes out of one appears wherever the
     * pot stood, in the middle of the player's board, instead of walking in from the right-hand
     * edge. Anything a capability counts <em>from the walk</em> - a fuse measured in cells covered,
     * in the one shipped example (see {@code JackInTheBoxCapability}) - has nothing to count, and
     * "how far along the lane is it" is not a question the player can answer about a zombie that
     * never walked a lane.
     *
     * <p>Called once, at the spawn, and only by a container's own release path.
     */
    default void onReleased(ZombieEntity zombie, LevelAccess level) {
    }

    default void save(CompoundTag tag) {
    }

    default void load(CompoundTag tag) {
    }
}
