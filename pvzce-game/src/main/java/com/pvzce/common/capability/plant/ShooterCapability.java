package com.pvzce.common.capability.plant;

import com.pvzce.common.level.WorldPosition;
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

    /**
     * How far apart the extra projectiles of a multiplied volley leave, when the shot has no
     * burst delay of its own.
     *
     * <p>A peashooter's one pea at triple firepower is three peas, and three born on the same tick
     * at the same point are one pea as far as the lawn is concerned - which is exactly why the
     * repeater's second pea has a burst delay at all (see {@code ProjectileRef#burstDelay}). Six
     * ticks is a tenth of a second: long enough that the shots are separate objects on the screen
     * and separate hits, short enough that the volley still reads as one.
     */
    public static final int MULTIPLIED_BURST_DELAY = 6;

    private final int intervalTicks;
    private final List<ProjectileRef> shots;
    private final Optional<Identifier> sound;
    private final int firstDelayTicks;
    /** Cells within which an enemy makes this plant hide instead of firing; 0 = never. */
    private final float hideWithin;

    private final boolean independentShots;
    private final String shootState;
    private int cooldown;
    private final int[] shotCooldowns;
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
        this(intervalTicks, shots, sound, firstDelayTicks, hideWithin, false, EntityAnimations.SHOOT);
    }

    public ShooterCapability(int intervalTicks, List<ProjectileRef> shots, Optional<Identifier> sound,
                             int firstDelayTicks, float hideWithin, boolean independentShots, String shootState) {
        this.independentShots = independentShots;
        this.shootState = shootState;
        this.intervalTicks = Math.max(1, intervalTicks);
        this.shots = List.copyOf(shots);
        this.sound = sound;
        this.firstDelayTicks = Math.max(0, firstDelayTicks);
        this.hideWithin = Math.max(0F, hideWithin);
        this.cooldown = Math.min(this.intervalTicks, this.firstDelayTicks == 0 ? 1 : this.firstDelayTicks);
        this.shotCooldowns = new int[shots.size()];
        java.util.Arrays.fill(shotCooldowns, cooldown);
    }

    public static final MapCodec<ShooterCapability> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            com.mojang.serialization.Codec.INT.optionalFieldOf("interval", DEFAULT_INTERVAL)
                    .forGetter(ShooterCapability::intervalTicks),
            ProjectileRef.CODEC.listOf().optionalFieldOf("shots", List.of()).forGetter(ShooterCapability::shots),
            Identifier.CODEC.optionalFieldOf("sound").forGetter(ShooterCapability::sound),
            com.mojang.serialization.Codec.INT.optionalFieldOf("first_delay", 0)
                    .forGetter(ShooterCapability::firstDelayTicks),
            com.mojang.serialization.Codec.FLOAT.optionalFieldOf("hide_within", 0F)
                    .forGetter(ShooterCapability::hideWithin),
            Codec.BOOL.optionalFieldOf("independent_shots", false).forGetter(c -> c.independentShots),
            Codec.STRING.optionalFieldOf("shoot_state", EntityAnimations.SHOOT).forGetter(c -> c.shootState)
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
        return new ShooterCapability(intervalTicks, shots, sound, firstDelayTicks, hideWithin, independentShots, shootState);
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
        if (independentShots) {
            int step = clock.step(plant.actionRate());
            List<ProjectileRef> ready = new ArrayList<>();
            for (int i = 0; i < shots.size(); i++) {
                if (shotCooldowns[i] > 0) {
                    shotCooldowns[i] = Math.max(0, shotCooldowns[i] - step);
                } else if (hasTarget(shots.get(i), plant, level)) {
                    ready.add(shots.get(i));
                    shotCooldowns[i] = intervalTicks;
                }
            }
            if (!ready.isEmpty()) fire(plant, level, ready);
            return;
        }
        if (cooldown > 0) {
            // The plant's own rate: watered counts a quarter faster, and a mutation may have
            // rewritten how fast plants work at all (see PlantEntity.actionRate).
            cooldown = Math.max(0, cooldown - clock.step(plant.actionRate()));
            if (cooldown == 0) {
                plant.setState(EntityAnimations.IDLE);
            }
            return;
        }
        if (!hasTarget(plant, level)) {
            plant.setState(EntityAnimations.IDLE);
            return;
        }
        fire(plant, level, shots);
    }

    /**
     * Whether this shooter attacks on a clock - which it does, and it therefore waits for an order
     * on a level whose plants hold their fire (see {@code PlantCapability#holdsFire}).
     */
    @Override
    public boolean holdsFire(PlantEntity plant) {
        return true;
    }

    /**
     * A burst still on its way out is owed work: the peas were fired, they have not been born yet.
     *
     * <p>Which is what makes a repeater a repeater on a level that holds its fire - the alternative
     * was a two-shot plant that dealt one shot's damage (see {@code PlantCapability#hasPendingWork}).
     */
    @Override
    public boolean hasPendingWork(PlantEntity plant) {
        return !pendingShots.isEmpty();
    }

    /** The tail of a volley whose ticks have come; no cooldown, no target, no new decision. */
    @Override
    public void tickPending(PlantEntity plant, LevelAccess level) {
        firePendingShots(plant, level);
    }

    /**
     * Fires one volley now, at nothing in particular.
     *
     * <p>A play-ordered shot does not look for a target first: the player pressed the key for this
     * column, and a peashooter that answered a beat with nothing at all because the lane happened
     * to be empty would read as a broken key. The row is still the plant's own, so the shot goes
     * where the plant aims; if nothing is there it flies off the lawn and expires.
     */
    @Override
    public boolean strike(PlantEntity plant, LevelAccess level) {
        if (removed(plant)) {
            return false;
        }
        fire(plant, level, shots);
        java.util.Arrays.fill(shotCooldowns, intervalTicks);
        return true;
    }

    /** True for a plant that has already left the field; a strike on one does nothing. */
    private static boolean removed(PlantEntity plant) {
        return plant == null || plant.isRemoved();
    }

    /**
     * One volley: every shot entry, with the burst entries left on their own clocks.
     *
     * <p>Shared by the clock and by {@link #strike} so that "what this plant does" has one answer.
     * The cooldown is set here too: a play-ordered volley that did not reset it would let a plant
     * fire on its own the moment the level stopped holding fire, as if the note had never happened.
     */
    private void fire(PlantEntity plant, LevelAccess level, List<ProjectileRef> volley) {
        boolean front = false;
        boolean back = false;
        // The level's say over how big a volley is (the rhythm levels' energy bar doubles and
        // triples it). Read per volley, because the bar moves while the run is going.
        int repeats = Math.max(1, level.projectileCountMultiplier(plant));
        for (ProjectileRef shot : volley) {
            if (shot.backward()) back = true; else front = true;
            // The muzzle sits on the firing side, so a backward shot leaves the plant
            // from its other edge instead of appearing inside it.
            float muzzleX = plant.cellX() + PlantShots.MUZZLE_OFFSET_X * shot.direction();
            float row = aimRow(shot, plant, level);
            int count = shot.count() * repeats;
            // A shot with a burst delay keeps it, multiplied or not - the repeater's rhythm is the
            // plant's. One without one only needs spacing once the volley has been multiplied:
            // three peas born on the same tick at the same point are one pea (see
            // `ProjectileRef#burstDelay`), and a single-pea plant at 1x has nothing to space.
            int burst = shot.burstDelay() > 0 ? shot.burstDelay()
                    : (repeats > 1 ? MULTIPLIED_BURST_DELAY : 0);
            if (shot.initialDelay() > 0) {
                // The whole entry waits - a volley that does not leave on the firing tick at all.
                // No shipped shot does (see `ProjectileRef#initialDelay`); the field is kept for a
                // content author whose art opens its heads one at a time. Every projectile of the
                // entry rides the same countdown, spaced by its own burst delay on top.
                for (int i = 0; i < count; i++) {
                    pendingShots.add(new PendingShot(shot, muzzleX, row,
                            shot.initialDelay() + i * burst));
                }
                continue;
            }
            if (burst <= 0) {
                // One tick, one volley: the shape every single-pea plant has, and the one a
                // multi-row volley has to keep - the threepeater's three peas belong to different
                // lanes, so they are not a burst at all and leave together.
                for (int i = 0; i < count; i++) {
                    level.spawnProjectile(shot, muzzleX, row, plant);
                }
                continue;
            }
            // A burst: the first pea leaves now, the rest on their own ticks. This is the
            // repeater - see ProjectileRef#burstDelay for why firing them together is the same
            // as firing one.
            if (count > 0) {
                level.spawnProjectile(shot, muzzleX, row, plant);
            }
            for (int i = 1; i < count; i++) {
                pendingShots.add(new PendingShot(shot, muzzleX, row, i * burst));
            }
        }
        level.emitEffect(PvzceParticles.PUFF_SHROOM_MUZZLE.toString(), new WorldPosition(plant.cellX() + 0.5F, plant.cellY(), plant.height()), plant.surfaceId(), sound.orElseGet(() -> plant.def().sounds().shoot().orElse(PvzceSounds.PLANT_SHOOT_PEA)));
        plant.beginAction(independentShots && back
                ? (front ? "shoot_both" : "shoot_back") : shootState);
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
            boolean found = level.enemiesInRow(row, plant.team(), plant.surfaceId()).stream()
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
     * The row a shot leaves in. Homing shots launch from the plant's own row;
     * ordinary target-row shots use the nearest reachable target's row.
     */
    private float aimRow(ProjectileRef shot, PlantEntity plant, LevelAccess level) {
        if (!shot.targetRow() || homing(shot)) {
            return plant.cellY() + shot.rowOffset();
        }
        ZombieEntity nearest = nearestTarget(shot, plant, level);
        return nearest == null ? plant.cellY() + shot.rowOffset() : nearest.gridY();
    }

    private static boolean homing(ProjectileRef shot) {
        var def = com.pvzce.common.core.BuiltInRegistries.PROJECTILES.get(shot.projectile());
        return def != null && def.resolvedCapabilities().stream().anyMatch(c ->
                c.value() instanceof com.pvzce.common.capability.projectile.HomingMotionCapability);
    }

    private ZombieEntity nearestTarget(ProjectileRef shot, PlantEntity plant, LevelAccess level) {
        if (homing(shot)) {
            return com.pvzce.common.capability.projectile.HomingMotionCapability.target(
                    level, plant.team(), plant.cellX(), plant.cellY());
        }
        float muzzleX = plant.cellX() + PlantShots.MUZZLE_OFFSET_X * shot.direction();
        ZombieEntity best = null;
        float bestDistance = Float.MAX_VALUE;
        for (int row = 0; row < level.height(); row++) {
            for (ZombieEntity zombie : level.enemiesInRow(row, plant.team(), plant.surfaceId())) {
                if (zombie.isRemoved() || !zombie.canBeHitByGround()
                        || !shot.covers(muzzleX, zombie.cellX())) {
                    continue;
                }
                float distance = Math.abs(zombie.cellX() - muzzleX);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = zombie;
                }
            }
        }
        return best;
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
    public boolean hasTarget(PlantEntity plant, LevelAccess level) {
        return shots.stream().anyMatch(shot -> hasTarget(shot, plant, level));
    }

    private boolean hasTarget(ProjectileRef raw, PlantEntity plant, LevelAccess level) {
        ProjectileRef shot = PlantShots.scaled(raw, plant, level);
        float muzzleX = plant.cellX() + PlantShots.MUZZLE_OFFSET_X * shot.direction();
        var def = com.pvzce.common.core.BuiltInRegistries.PROJECTILES.get(shot.projectile());
        boolean air = def != null && def.isAirLayer() && shot.launchHeight() > 0F;
        if (shot.targetRow()) return nearestTarget(shot, plant, level) != null;
        if (shot.vectorY() != 0F || shot.vectorX() != 1F) {
            return level.enemiesOf(plant.team()).stream()
                    .anyMatch(z -> !z.isRemoved() && z.canBeHitByGround()
                            && shot.covers(muzzleX, plant.cellY(), z.cellX(), z.cellY()));
        }
        for (int offset : shot.coveredRowOffsets()) {
            int row = plant.gridY() + offset;
            if (row < 0 || row >= level.height()) continue;
            if (level.enemiesInRow(row, plant.team(), plant.surfaceId()).stream().anyMatch(z -> !z.isRemoved()
                    && (air ? !z.isGrounded() : z.canBeHitByGround())
                    && shot.covers(muzzleX, z.cellX()))) return true;
        }
        return false;
    }

    @Override
    public void save(CompoundTag tag) {
        tag.putInt("cooldown", cooldown);
        if (independentShots) {
            for (int i = 0; i < shotCooldowns.length; i++) tag.putInt("shotCooldown" + i, shotCooldowns[i]);
        }
        clock.save(tag);
    }

    @Override
    public void load(CompoundTag tag) {
        cooldown = tag.getInt("cooldown");
        for (int i = 0; i < shotCooldowns.length; i++) {
            shotCooldowns[i] = tag.contains("shotCooldown" + i) ? tag.getInt("shotCooldown" + i) : cooldown;
        }
        clock.load(tag);
    }
}
