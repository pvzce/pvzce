package com.pvzce.api.entity;

/**
 * Render/logic layering shared by both sides. Lower layers draw first; the
 * values are a stable wire contract (see {@code EntitySpawnS2C.layer}).
 */
public final class EntityLayers {
    /** Burrowing zombies (miner) that ground attacks cannot reach. */
    public static final int UNDERGROUND = -1;
    /** Walking zombies and anything that stands on the lawn. */
    public static final int GROUND = 0;
    /** Plants (and plants stacked on carriers). */
    public static final int PLANT = 1;
    /** Ground-layer projectiles. */
    public static final int PROJECTILE = 2;
    /** Air-layer projectiles, flying zombies and falling resource drops. */
    public static final int AIR = 3;

    private EntityLayers() {
    }
}
