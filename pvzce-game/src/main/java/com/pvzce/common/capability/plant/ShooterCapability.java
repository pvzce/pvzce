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
 * Fires straight-line projectiles down its own row while a valid target exists.
 *
 * <p>Replaces the old {@code pvzce:shooter} behaviour branch: the cooldown, the
 * "look for a target before committing" rule and the one-tick {@code idle} reset
 * now live here instead of inside {@code PlantEntity}.
 */
public final class ShooterCapability implements PlantCapability {
    public static final int DEFAULT_INTERVAL = 90;
    /** Boosted shots (energy bean / coffee bean) may never fire faster than this. */
    private static final int MIN_BOOSTED_INTERVAL = 15;

    private final int intervalTicks;
    private final List<ProjectileRef> shots;
    private final Optional<Identifier> sound;
    private final int firstDelayTicks;

    private int cooldown;
    private boolean boosted;

    public ShooterCapability(int intervalTicks, List<ProjectileRef> shots, Optional<Identifier> sound,
                             int firstDelayTicks) {
        this.intervalTicks = Math.max(1, intervalTicks);
        this.shots = List.copyOf(shots);
        this.sound = sound;
        this.firstDelayTicks = Math.max(0, firstDelayTicks);
        this.cooldown = Math.min(this.intervalTicks, this.firstDelayTicks == 0 ? 1 : this.firstDelayTicks);
    }

    public static final MapCodec<ShooterCapability> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            com.mojang.serialization.Codec.INT.optionalFieldOf("interval", DEFAULT_INTERVAL)
                    .forGetter(ShooterCapability::intervalTicks),
            ProjectileRef.CODEC.listOf().optionalFieldOf("shots", List.of()).forGetter(ShooterCapability::shots),
            Identifier.CODEC.optionalFieldOf("sound").forGetter(ShooterCapability::sound),
            com.mojang.serialization.Codec.INT.optionalFieldOf("first_delay", 0)
                    .forGetter(ShooterCapability::firstDelayTicks)
    ).apply(i, ShooterCapability::new));

    public int intervalTicks() {
        return intervalTicks;
    }

    public List<ProjectileRef> shots() {
        return shots;
    }

    public Optional<Identifier> sound() {
        return sound;
    }

    public int firstDelayTicks() {
        return firstDelayTicks;
    }

    @Override
    public PlantCapability instantiate() {
        return new ShooterCapability(intervalTicks, shots, sound, firstDelayTicks);
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
        List<ZombieEntity> targets = level.zombiesInRow(plant.gridY()).stream()
                .filter(z -> !z.isRemoved() && z.cellX() > plant.cellX() && z.canBeHitByGround())
                .sorted((a, b) -> Float.compare(a.cellX(), b.cellX()))
                .toList();
        if (targets.isEmpty()) {
            plant.setAnimation(EntityAnimations.IDLE);
            return;
        }
        plant.setAnimation(EntityAnimations.SHOOT);
        for (ProjectileRef shot : shots) {
            for (int i = 0; i < shot.count(); i++) {
                level.spawnProjectile(shot, plant.cellX() + PlantShots.MUZZLE_OFFSET_X, plant.cellY(), plant);
            }
        }
        level.emitEffect("pvzce:muzzle", plant.cellX() + 0.5F, plant.cellY(),
                sound.orElseGet(() -> plant.def().sounds().shoot().orElse(PvzceSounds.PLANT_SHOOT_PEA)));
        cooldown = boosted ? Math.max(MIN_BOOSTED_INTERVAL, intervalTicks / 2) : intervalTicks;
        boosted = false;
    }

    /** Energy bean / coffee bean activation: the next attack fires immediately. */
    @Override
    public void boost(PlantEntity plant) {
        cooldown = 0;
        boosted = true;
        plant.setAnimation(EntityAnimations.SHOOT);
    }

    @Override
    public void save(CompoundTag tag) {
        tag.putInt("cooldown", cooldown);
        tag.putInt("boosted", boosted ? 1 : 0);
    }

    @Override
    public void load(CompoundTag tag) {
        cooldown = tag.getInt("cooldown");
        boosted = tag.getInt("boosted") != 0;
    }
}
