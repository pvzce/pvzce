package com.pvzce.common.capability.plant;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.content.ProjectileRef;
import com.pvzce.api.content.capability.PlantCapability;
import com.pvzce.api.entity.EntityAnimations;
import com.pvzce.api.entity.LevelAccess;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceSounds;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.server.entity.PlantEntity;
import com.pvzce.server.entity.ZombieEntity;

import java.util.List;
import java.util.Optional;

/**
 * Lobbed-arc attacker (kernel-pult and friends) with an optional chance to fire a
 * different "butter" projectile that carries its own effects.
 */
public final class ThrowerCapability implements PlantCapability {
    public static final int DEFAULT_INTERVAL = 90;

    private final int intervalTicks;
    private final List<ProjectileRef> shots;
    private final float butterChance;
    private final Identifier butterProjectile;
    private final Optional<Identifier> sound;
    private final int firstDelayTicks;

    private int cooldown;

    public ThrowerCapability(int intervalTicks, List<ProjectileRef> shots, float butterChance,
                             Identifier butterProjectile, Optional<Identifier> sound, int firstDelayTicks) {
        this.intervalTicks = Math.max(1, intervalTicks);
        this.shots = List.copyOf(shots);
        this.butterChance = Math.max(0F, Math.min(1F, butterChance));
        this.butterProjectile = butterProjectile;
        this.sound = sound;
        this.firstDelayTicks = Math.max(0, firstDelayTicks);
        this.cooldown = Math.min(this.intervalTicks, this.firstDelayTicks == 0 ? 1 : this.firstDelayTicks);
    }

    public static final MapCodec<ThrowerCapability> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.INT.optionalFieldOf("interval", DEFAULT_INTERVAL).forGetter(ThrowerCapability::intervalTicks),
            ProjectileRef.CODEC.listOf().optionalFieldOf("shots", List.of()).forGetter(ThrowerCapability::shots),
            Codec.FLOAT.optionalFieldOf("butter_chance", 0F).forGetter(ThrowerCapability::butterChance),
            Identifier.CODEC.optionalFieldOf("butter_projectile", Identifier.withDefaultNamespace("butter"))
                    .forGetter(ThrowerCapability::butterProjectile),
            Identifier.CODEC.optionalFieldOf("sound").forGetter(ThrowerCapability::sound),
            Codec.INT.optionalFieldOf("first_delay", 0).forGetter(ThrowerCapability::firstDelayTicks)
    ).apply(i, ThrowerCapability::new));

    public int intervalTicks() {
        return intervalTicks;
    }

    public List<ProjectileRef> shots() {
        return shots;
    }

    public float butterChance() {
        return butterChance;
    }

    public Identifier butterProjectile() {
        return butterProjectile;
    }

    public Optional<Identifier> sound() {
        return sound;
    }

    public int firstDelayTicks() {
        return firstDelayTicks;
    }

    @Override
    public PlantCapability instantiate() {
        return new ThrowerCapability(intervalTicks, shots, butterChance, butterProjectile, sound, firstDelayTicks);
    }

    @Override
    public void tick(PlantEntity plant, LevelAccess level) {
        if (cooldown > 0) {
            cooldown--;
            if (cooldown == 0) {
                plant.setAnimation(EntityAnimations.IDLE);
            }
            return;
        }
        ZombieEntity target = level.zombiesInRow(plant.gridY()).stream()
                .filter(z -> !z.isRemoved() && z.cellX() > plant.cellX())
                .sorted((a, b) -> Float.compare(a.cellX(), b.cellX()))
                .findFirst()
                .orElse(null);
        if (target == null) {
            plant.setAnimation(EntityAnimations.IDLE);
            return;
        }
        plant.setAnimation(EntityAnimations.SHOOT);
        for (ProjectileRef shot : shots) {
            boolean butter = butterChance > 0F && level.random().nextFloat() < butterChance;
            ProjectileRef ref = butter
                    ? new ProjectileRef(butterProjectile, shot.damage(), shot.count())
                    : shot;
            level.spawnArcProjectile(ref, plant.cellX() + PlantShots.MUZZLE_OFFSET_X, plant.cellY(), plant, target);
        }
        level.emitEffect(PlantShots.MUZZLE_PARTICLE, plant.cellX() + 0.5F, plant.cellY(),
                sound.orElseGet(() -> plant.def().sounds().shoot().orElse(PvzceSounds.PLANT_THROW)));
        cooldown = intervalTicks;
    }

    @Override
    public void boost(PlantEntity plant) {
        cooldown = 0;
        plant.setAnimation(EntityAnimations.SHOOT);
    }

    @Override
    public void save(CompoundTag tag) {
        tag.putInt("cooldown", cooldown);
    }

    @Override
    public void load(CompoundTag tag) {
        cooldown = tag.getInt("cooldown");
    }
}
