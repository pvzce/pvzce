package com.pvzce.common.capability.plant;

/** Shared geometry for plant-launched projectiles. */
public final class PlantShots {
    /** Projectiles leave slightly ahead of the plant centre so they clear the sprite. */
    public static final float MUZZLE_OFFSET_X = 0.3F;
    /** Generic muzzle particle used by every shooting capability. */
    public static final String MUZZLE_PARTICLE = "pvzce:muzzle";

    private PlantShots() {
    }
}
