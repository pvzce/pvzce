package com.pvzce.common.capability.plant;

import com.pvzce.common.PvzceParticles;

/** Shared geometry for plant-launched projectiles. */
public final class PlantShots {
    /** Projectiles leave slightly ahead of the plant centre so they clear the sprite. */
    public static final float MUZZLE_OFFSET_X = 0.3F;
    /**
     * Generic muzzle puff used by every shooting capability.
     *
     * <p>Named through {@link PvzceParticles} rather than as a literal: the effect id
     * is looked up in the particle registry at spawn time, so a typo here would have
     * produced an invisible puff and a log line with no address.
     */
    public static final String MUZZLE_PARTICLE = PvzceParticles.PUFF_SHROOM_MUZZLE.toString();

    private PlantShots() {
    }
}
