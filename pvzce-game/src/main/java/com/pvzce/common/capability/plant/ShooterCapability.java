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

import java.util.ArrayList;
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
    /**
     * This shooter's own progress clock.
     *
     * <p>Per capability rather than per plant: a plant that shoots <em>and</em> produces would
     * otherwise have one accumulator between them, and whichever ran first would spend the tick's
     * progress - which is exactly how a Sun-shroom's production and a Peashooter's firing appeared
     * to stop working when this was a field on the plant.
     */
    private final com.pvzce.common.level.RateClock clock =
            new com.pvzce.common.level.RateClock();
    /**
     * Projectiles of a volley that are still on their way out, with the ticks left before each.
     *
     * <p>Only a shot with a {@code burst_delay} has any: a single-pea plant spawns its one
     * projectile on the firing tick and never reads this list. The repeater's second pea and the
     * gatling pea's other three live here between "the plant fired" and "the pea exists", which is
     * what makes them separate objects on the lawn instead of one coincident stack.
     *
     * <p>Not part of {@link #save}: a burst is a fifth of a second long, and a save taken inside
     * that window loses at most the tail of one volley - against the alternative of teaching the
     * save format about a queue whose entries name a content definition.
     */
    private final List<PendingShot> pendingShots = new ArrayList<>();

    /** One projectile of a volley that has been ordered but not yet born. */
    private record PendingShot(ProjectileRef shot, float muzzleX, float row, int ticksLeft) {
    }

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
        // The tail of a volley leaves even if the plant ducks or starts its next cooldown in the
        // meantime: those projectiles were fired, and a repeater's second pea appearing only if
        // its plant kept facing the right way would be a shot the player is owed and does not get.
        firePendingShots(plant, level);
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
            // The plant's own rate: watered counts a quarter faster, and a mutation may have
            // rewritten how fast plants work at all (see PlantEntity.actionRate).
            cooldown -= clock.step(plant.actionRate());
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
            if (shot.initialDelay() > 0) {
                // The whole entry waits - a volley that does not leave on the firing tick at all.
                // No shipped shot does (see `ProjectileRef#initialDelay`); the field is kept for a
                // content author whose art opens its heads one at a time. Every projectile of the
                // entry rides the same countdown, spaced by its own burst delay on top.
                for (int i = 0; i < shot.count(); i++) {
                    pendingShots.add(new PendingShot(shot, muzzleX, row,
                            shot.initialDelay() + i * shot.burstDelay()));
                }
                continue;
            }
            if (shot.burstDelay() <= 0) {
                // One tick, one volley: the shape every single-pea plant has, and the one a
                // multi-row volley has to keep - the threepeater's three peas belong to different
                // lanes, so they are not a burst at all and leave together.
                for (int i = 0; i < shot.count(); i++) {
                    level.spawnProjectile(shot, muzzleX, row, plant);
                }
                continue;
            }
            // A burst: the first pea leaves now, the rest on their own ticks. This is the
            // repeater - see ProjectileRef#burstDelay for why firing them together is the same
            // as firing one.
            if (shot.count() > 0) {
                level.spawnProjectile(shot, muzzleX, row, plant);
            }
            for (int i = 1; i < shot.count(); i++) {
                pendingShots.add(new PendingShot(shot, muzzleX, row, i * shot.burstDelay()));
            }
        }
        level.emitEffect(PvzceParticles.PUFF_SHROOM_MUZZLE.toString(), plant.cellX() + 0.5F, plant.cellY(),
                sound.orElseGet(() -> plant.def().sounds().shoot().orElse(PvzceSounds.PLANT_SHOOT_PEA)));
        cooldown = intervalTicks;
    }

    /**
     * Births the projectiles of an unfinished volley whose tick has come.
     *
     * <p>The muzzle and the row were resolved when the plant fired rather than being read again
     * here: a pea that has left the barrel does not follow the plant, and a repeat of the aiming
     * arithmetic would be a second answer to "where does this shot come from".
     */
    private void firePendingShots(PlantEntity plant, LevelAccess level) {
        if (pendingShots.isEmpty()) {
            return;
        }
        // Reverse order so removing an entry does not shift the ones still to come.
        for (int i = pendingShots.size() - 1; i >= 0; i--) {
            PendingShot pending = pendingShots.get(i);
            int ticksLeft = pending.ticksLeft() - 1;
            if (ticksLeft > 0) {
                pendingShots.set(i, new PendingShot(pending.shot(), pending.muzzleX(), pending.row(),
                        ticksLeft));
                continue;
            }
            pendingShots.remove(i);
            level.spawnProjectile(pending.shot(), pending.muzzleX(), pending.row(), plant);
        }
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
     * into its range. It is read through {@link PlantShots#scaled}, so a run rule that lengthens
     * the shot lengthens the reach that decides when to fire it - one number, both halves.
     */
    private boolean hasTarget(PlantEntity plant, LevelAccess level) {
        for (ProjectileRef raw : shots) {
            // Aimed with the scaled shot, not the definition's own number: the projectile is born
            // scaled (see LevelServer.spawnProjectile), and a plant that decided with the unscaled
            // range would never fire at the zombies its shots can now reach.
            ProjectileRef shot = PlantShots.scaled(raw, plant, level);
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
