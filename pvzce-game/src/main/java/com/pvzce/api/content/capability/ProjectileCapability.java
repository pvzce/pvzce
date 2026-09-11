package com.pvzce.api.content.capability;

import com.pvzce.api.entity.LevelAccess;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.server.entity.ProjectileEntity;
import com.pvzce.server.entity.ZombieEntity;

/**
 * One composable projectile behaviour: how it moves, what it does on impact and
 * whether it survives a hit.
 */
public interface ProjectileCapability {
    default ProjectileCapability instantiate() {
        return this;
    }

    /**
     * Advances the projectile for one tick.
     *
     * @return {@code true} when the capability moved the projectile itself and
     *         the default straight-line motion must be skipped
     */
    default boolean move(ProjectileEntity projectile, LevelAccess level) {
        return false;
    }

    /**
     * Impact effect (splash damage, status application, ...).
     *
     * @param target the zombie that was touched, or {@code null} when a homing
     *               shot landed on empty ground - capabilities that need a
     *               position must fall back to the projectile's own cell
     */
    default void onHit(ProjectileEntity projectile, ZombieEntity target, LevelAccess level) {
    }

    /**
     * Whether the direct hit damage is replaced by {@link #onHit}. Splash
     * projectiles damage an area instead of the single target they touched.
     */
    default boolean replacesDirectHit() {
        return false;
    }

    /** Whether the projectile keeps flying after a hit. */
    default boolean pierces() {
        return false;
    }

    /** Overrides the render/logic layer, or {@link Integer#MIN_VALUE} for the default. */
    default int layerOverride(ProjectileEntity projectile) {
        return Integer.MIN_VALUE;
    }

    default void save(CompoundTag tag) {
    }

    default void load(CompoundTag tag) {
    }
}
