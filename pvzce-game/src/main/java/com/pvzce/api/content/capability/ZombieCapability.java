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

    /** Called once when the zombie's health reaches zero. */
    default void onDeath(ZombieEntity zombie, LevelAccess level) {
    }

    default void save(CompoundTag tag) {
    }

    default void load(CompoundTag tag) {
    }
}
