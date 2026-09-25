package com.pvzce.common.capability.projectile;

import com.mojang.serialization.MapCodec;
import com.pvzce.api.content.StatusEffectDef;
import com.pvzce.api.content.capability.ProjectileCapability;
import com.pvzce.api.entity.LevelAccess;
import com.pvzce.server.entity.ProjectileEntity;
import com.pvzce.server.entity.ZombieEntity;

import java.util.List;

/**
 * Applies status effects to whatever the projectile hits (butter immobilises,
 * snow peas slow). The strength and duration come from the data file rather than
 * from a magic effect id, so a pack can ship "slow 50% for 2s" without new code.
 */
public final class StatusOnHitCapability implements ProjectileCapability {
    private final List<StatusEffectDef> effects;

    public StatusOnHitCapability(List<StatusEffectDef> effects) {
        this.effects = List.copyOf(effects);
    }

    public static final MapCodec<StatusOnHitCapability> CODEC =
            StatusEffectDef.CODEC.listOf().optionalFieldOf("effects", List.of())
                    .xmap(StatusOnHitCapability::new, StatusOnHitCapability::effects);

    public List<StatusEffectDef> effects() {
        return effects;
    }

    @Override
    public ProjectileCapability instantiate() {
        return this;
    }

    @Override
    public void onHit(ProjectileEntity projectile, ZombieEntity zombie, LevelAccess level) {
        if (zombie == null) {
            return;
        }
        for (StatusEffectDef effect : effects) {
            zombie.applyStatus(effect.status(),
                    com.pvzce.common.level.StatusDurations.scale(level, effect.ticks()),
                    effect.magnitude());
        }
    }
}
