package com.pvzce.common.capability.projectile;

import com.mojang.serialization.MapCodec;
import com.pvzce.api.content.capability.ProjectileCapability;

/** Keeps flying after a hit instead of being consumed. */
public final class PierceCapability implements ProjectileCapability {
    public static final PierceCapability INSTANCE = new PierceCapability();

    public static final MapCodec<PierceCapability> CODEC = MapCodec.unit(INSTANCE);

    private PierceCapability() {
    }

    @Override
    public ProjectileCapability instantiate() {
        return this;
    }

    @Override
    public boolean pierces() {
        return true;
    }
}
