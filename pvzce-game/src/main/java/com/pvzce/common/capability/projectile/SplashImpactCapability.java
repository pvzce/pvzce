package com.pvzce.common.capability.projectile;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.content.capability.ProjectileCapability;
import com.pvzce.api.entity.LevelAccess;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceSounds;
import com.pvzce.server.entity.ProjectileEntity;
import com.pvzce.server.entity.ZombieEntity;

import java.util.Optional;

/**
 * Area-of-effect impact (melon, cherry-like pults): the touched target is
 * replaced by a blast that hits every zombie within {@code radius} cells.
 */
public final class SplashImpactCapability implements ProjectileCapability {
    public static final float DEFAULT_RADIUS = 1.5F;

    private final float radius;
    private final Optional<Identifier> sound;

    public SplashImpactCapability(float radius, Optional<Identifier> sound) {
        this.radius = Math.max(0F, radius);
        this.sound = sound;
    }

    public static final MapCodec<SplashImpactCapability> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.FLOAT.optionalFieldOf("radius", DEFAULT_RADIUS).forGetter(SplashImpactCapability::radius),
            Identifier.CODEC.optionalFieldOf("sound").forGetter(SplashImpactCapability::sound)
    ).apply(i, SplashImpactCapability::new));

    public float radius() {
        return radius;
    }

    public Optional<Identifier> sound() {
        return sound;
    }

    @Override
    public ProjectileCapability instantiate() {
        return this;
    }

    @Override
    public boolean replacesDirectHit() {
        return true;
    }

    @Override
    public void onHit(ProjectileEntity projectile, ZombieEntity zombie, LevelAccess level) {
        float x = zombie != null ? zombie.cellX() : projectile.cellX();
        float y = zombie != null ? zombie.cellY() : projectile.cellY();
        level.damageArea(x, y, radius, projectile.damage(), projectile.team());
        level.emitEffect("pvzce:splash", x, y,
                sound.orElseGet(() -> projectile.def().sounds().impact().orElse(PvzceSounds.PROJECTILE_HIT)));
    }
}
