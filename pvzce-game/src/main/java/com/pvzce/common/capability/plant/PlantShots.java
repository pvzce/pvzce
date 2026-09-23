package com.pvzce.common.capability.plant;

import com.pvzce.api.content.ProjectileRef;
import com.pvzce.common.PvzceParticles;
import com.pvzce.server.entity.PlantEntity;

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

    /**
     * A shot as this plant actually fires it: its own range, times whatever the run's rules say.
     *
     * <p>The one place that rule lives. A shooter asks twice - "is anything worth firing at" while
     * it decides, and "how far may this fly" when the projectile is born - and the two halves were
     * allowed to read different numbers, which is exactly how the mushroom-range buff came to do
     * nothing visible: the shot itself was lengthened, and the plant went on refusing to fire at
     * anything past its unbuffed reach.
     *
     * <p>Hands back the same reference when the multiplier is 1 or the range is unlimited, so the
     * overwhelming majority of shots allocate nothing.
     */
    public static ProjectileRef scaled(ProjectileRef ref, PlantEntity plant,
                                       com.pvzce.api.entity.LevelAccess level) {
        if (ref == null || plant == null || level == null) {
            return ref;
        }
        float multiplier = level.sporeRangeMultiplier(plant);
        return multiplier == 1F ? ref : ref.scaledRange(multiplier);
    }

    private PlantShots() {
    }
}
