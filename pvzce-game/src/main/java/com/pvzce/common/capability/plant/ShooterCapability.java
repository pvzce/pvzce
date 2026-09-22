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
    /** Cells within which an enemy makes this plant hide instead of firing; 0 = never. */
    private final float hideWithin;

    private int cooldown;

    public ShooterCapability(int intervalTicks, List<ProjectileRef> shots, Optional<Identifier> sound,
                             int firstDelayTicks) {
        this(intervalTicks, shots, sound, firstDelayTicks, 0F);
    }

    public ShooterCapability(int intervalTicks, List<ProjectileRef> shots, Optional<Identifier> sound,
                             int firstDelayTicks, float hideWithin) {
        this.intervalTicks = Math.max(1, intervalTicks);
        this.shots = List.copyOf(shots);
        this.sound = sound;
        this.firstDelayTicks = Math.max(0, firstDelayTicks);
        this.hideWithin = Math.max(0F, hideWithin);
        this.cooldown = Math.min(this.intervalTicks, this.firstDelayTicks == 0 ? 1 : this.firstDelayTicks);
    }

    public static final MapCodec<ShooterCapability> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            com.mojang.serialization.Codec.INT.optionalFieldOf("interval", DEFAULT_INTERVAL)
                    .forGetter(ShooterCapability::intervalTicks),
            ProjectileRef.CODEC.listOf().optionalFieldOf("shots", List.of()).forGetter(ShooterCapability::shots),
            Identifier.CODEC.optionalFieldOf("sound").forGetter(ShooterCapability::sound),
            com.mojang.serialization.Codec.INT.optionalFieldOf("first_delay", 0)
                    .forGetter(ShooterCapability::firstDelayTicks),
            com.mojang.serialization.Codec.FLOAT.optionalFieldOf("hide_within", 0F)
                    .forGetter(ShooterCapability::hideWithin)
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

    /**
     * How close an enemy has to be before this plant ducks, in cells.
     *
     * <p>Zero - the default, and what every shooter written before this field means - is "it
     * never ducks", which is every shooter but the scaredy-shroom.
     */
    public float hideWithin() {
        return hideWithin;
    }

    @Override
    public PlantCapability instantiate() {
        return new ShooterCapability(intervalTicks, shots, sound, firstDelayTicks, hideWithin);
    }

    @Override
    public void tick(PlantEntity plant, LevelAccess level) {
        // A frightened shooter ducks first and does nothing else - the cooldown is not even
        // ticked down, so a scaredy-shroom that comes back up fires immediately rather than
        // finishing the pause it was in when the zombie arrived. That is the original's
        // "stops shooting entirely while hidden", and it is why this is asked before the
        // cooldown rather than as another reason not to fire.
        if (hideWithin > 0F && somethingTooClose(plant, level)) {
            plant.setState(EntityAnimations.HIDE);
            return;
        }
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
     * Whether something worth being afraid of is standing next to this plant.
     *
     * <p>The original's scaredy-shroom ducks when a zombie is anywhere in the 3x3 block of
     * cells around it - its own lane and the two beside it, one cell either way - which is
     * why this asks the rows rather than only its own. Zombies this plant would not shoot at
     * (a charmed one, a balloon it cannot reach) do not frighten it either: the check is the
     * same {@code canBeHitByGround} rule the firing search uses, so "what it shoots" and
     * "what it hides from" cannot drift apart.
     */
    private boolean somethingTooClose(PlantEntity plant, LevelAccess level) {
        for (int rowOffset = -1; rowOffset <= 1; rowOffset++) {
            int row = plant.gridY() + rowOffset;
            if (row < 0 || row >= level.height()) {
                continue;
            }
            boolean found = level.enemiesInRow(row, plant.team()).stream()
                    .filter(z -> !z.isRemoved() && z.canBeHitByGround())
                    .anyMatch(z -> Math.abs(z.cellX() - plant.cellX()) <= hideWithin
                            // A zombie standing in the plant's own cell has already arrived;
                            // it is as close as close gets, and `cellX` alone would read it as
                            // zero distance away in every plant that shares the column.
                            || z.gridX() == plant.gridX() && z.gridY() == plant.gridY());
            if (found) {
                return true;
            }
        }
        return false;
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
