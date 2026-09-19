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
import com.pvzce.common.PvzceParticles;

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

    private final int intervalTicks;
    private final List<ProjectileRef> shots;
    private final Optional<Identifier> sound;
    private final int firstDelayTicks;

    private int cooldown;

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
                plant.setState(EntityAnimations.IDLE);
            }
            return;
        }
        if (!hasTarget(plant, level)) {
            plant.setState(EntityAnimations.IDLE);
            return;
        }
        plant.setState(EntityAnimations.SHOOT);
        for (ProjectileRef shot : shots) {
            // The muzzle sits on the firing side, so a backward shot leaves the plant
            // from its other edge instead of appearing inside it.
            float muzzleX = plant.cellX() + PlantShots.MUZZLE_OFFSET_X * shot.direction();
            float row = plant.cellY() + shot.rowOffset();
            for (int i = 0; i < shot.count(); i++) {
                level.spawnProjectile(shot, muzzleX, row, plant);
            }
        }
        level.emitEffect(PvzceParticles.PUFF_SHROOM_MUZZLE.toString(), plant.cellX() + 0.5F, plant.cellY(),
                sound.orElseGet(() -> plant.def().sounds().shoot().orElse(PvzceSounds.PLANT_SHOOT_PEA)));
        cooldown = intervalTicks;
    }

    /**
     * Whether any of this plant's lanes holds something worth shooting.
     *
     * <p>A shot may cover several rows ({@code rows}) and may point backwards
     * ({@code backward}), so the search is per shot rather than per plant: the
     * threepeater fires when anything is in one of its three lanes, the split pea when
     * anything is in front <em>or</em> behind it.
     *
     * <p>{@code range} is the same number the shot itself expires at, measured from the same
     * place the projectile is born (the muzzle), so a short-ranged plant (Puff-shroom)
     * neither wastes spores on a zombie it cannot reach nor holds fire while one is walking
     * into its range.
     */
    private boolean hasTarget(PlantEntity plant, LevelAccess level) {
        for (ProjectileRef shot : shots) {
            float muzzleX = plant.cellX() + PlantShots.MUZZLE_OFFSET_X * shot.direction();
            for (int rowOffset : shot.coveredRowOffsets()) {
                int row = plant.gridY() + rowOffset;
                if (row < 0 || row >= level.height()) {
                    continue;
                }
                boolean found = level.enemiesInRow(row, plant.team()).stream()
                        .filter(z -> !z.isRemoved() && z.canBeHitByGround())
                        .anyMatch(z -> shot.covers(muzzleX, z.cellX()));
                if (found) {
                    return true;
                }
            }
        }
        return false;
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
