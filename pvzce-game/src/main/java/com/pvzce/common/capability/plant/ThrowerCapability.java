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

    private final com.pvzce.common.level.RateClock clock = new com.pvzce.common.level.RateClock();
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
        firePendingLobs(plant, level);
        if (cooldown > 0) {
            cooldown = Math.max(0, cooldown - clock.step(plant.actionRate()));
            if (cooldown == 0) {
                plant.setState(EntityAnimations.IDLE);
            }
            return;
        }
        ZombieEntity target = targetOf(plant, level);
        if (target == null) {
            plant.setState(EntityAnimations.IDLE);
            return;
        }
        lob(plant, level, target);
    }

    /** This plant attacks on its own clock, so a hold-fire level makes it wait for an order. */
    @Override
    public boolean holdsFire(PlantEntity plant) {
        return true;
    }

    /**
     * Lobs one volley at whatever is in front, now.
     *
     * <p>A lob is the one shot that cannot be fired at nothing: it lands <em>on</em> a zombie's
     * cell, and a cabbage thrown down an empty lane has nowhere to come down. So this is the
     * clock's own target search, and an empty lane answers "no attack" rather than a wasted lob.
     */
    @Override
    public boolean strike(PlantEntity plant, LevelAccess level) {
        ZombieEntity target = targetOf(plant, level);
        if (target == null) {
            return false;
        }
        lob(plant, level, target);
        return true;
    }

    /** The nearest zombie in front of this plant, which is what a lob would come down on. */
    private ZombieEntity targetOf(PlantEntity plant, LevelAccess level) {
        return level.enemiesInRow(plant.gridY(), plant.team()).stream()
                .filter(z -> z.isAlive() && z.canBeHitByArc() && z.cellX() > plant.cellX())
                .sorted((a, b) -> Float.compare(a.cellX(), b.cellX()))
                .findFirst()
                .orElse(null);
    }

    /** One volley of arc shots at a target; shared by the clock and by {@link #strike}. */
    private void lob(PlantEntity plant, LevelAccess level, ZombieEntity target) {
        // How many times this volley is repeated: the rhythm levels' energy bar, the same number
        // the straight shooters read (see `LevelAccess#projectileCountMultiplier`). A lob has no
        // burst delay of its own to space the extra shots with, so it borrows the shooters': three
        // cabbages born on the same tick at the same point are one cabbage on the screen and three
        // hits on the zombie, which is a buff the player cannot see.
        int repeats = Math.max(1, level.projectileCountMultiplier(plant));
        float muzzleX = plant.cellX() + PlantShots.LOB_MUZZLE_OFFSET_X;
        for (ProjectileRef shot : shots) {
            float chance = level.butterChance(plant, butterChance);
            boolean butter = chance > 0F && level.random().nextFloat() < chance;
            ProjectileRef ref = butter
                    ? new ProjectileRef(butterProjectile, shot.damage(), shot.count())
                    : shot;
            // A lob is one projectile whatever its `count` says - the arc is aimed at a cell, and
            // this capability has never read the field (see `ProjectileRef#count`). The level's
            // multiplier is the only thing that makes it more than one.
            plant.beginAction(butter ? "shoot_butter" : EntityAnimations.SHOOT);
            pendingLobs.add(new PendingLob(ref, muzzleX, plant.cellY(), target,
                    com.pvzce.common.PvzceConstants.LOB_RELEASE_TICKS));
            for (int i = 1; i < repeats; i++) {
                pendingLobs.add(new PendingLob(ref, muzzleX, plant.cellY(), target,
                        com.pvzce.common.PvzceConstants.LOB_RELEASE_TICKS
                                + i * ShooterCapability.MULTIPLIED_BURST_DELAY));
            }
        }
        cooldown = intervalTicks;
    }

    /** One lob ordered but not yet thrown; the shooter's twin, and there for the same reason. */
    private record PendingLob(ProjectileRef ref, float x, float y, ZombieEntity target,
                              int ticksLeft) {
    }

    /**
     * Lobbed shots that are still on their way out, with the ticks left before each.
     *
     * <p>Every lob waits for the release point in the shooting gesture; multiplied volleys add
     * staggered releases. Like the shooter's burst queue, this transient work is not saved.
     */
    private final List<PendingLob> pendingLobs = new java.util.ArrayList<>();

    /** A lob still to come is work owed: without this a hold-fire level would swallow it. */
    @Override
    public boolean hasPendingWork(PlantEntity plant) {
        return !pendingLobs.isEmpty();
    }

    @Override
    public void tickPending(PlantEntity plant, LevelAccess level) {
        firePendingLobs(plant, level);
    }

    /** Throws the lobs whose ticks have come; no cooldown, no target search, no new decision. */
    private void firePendingLobs(PlantEntity plant, LevelAccess level) {
        if (pendingLobs.isEmpty()) {
            return;
        }
        // Reverse order so removing an entry does not shift the ones still to come - the shooter's
        // own loop, and the same countdown: an entry with one tick left fires now.
        for (int i = pendingLobs.size() - 1; i >= 0; i--) {
            PendingLob pending = pendingLobs.get(i);
            int ticksLeft = pending.ticksLeft() - 1;
            if (ticksLeft > 0) {
                pendingLobs.set(i, new PendingLob(pending.ref(), pending.x(), pending.y(),
                        pending.target(), ticksLeft));
                continue;
            }
            pendingLobs.remove(i);
            // A target that died while the shot was in the air takes the shot with it: the arc's
            // height is read off the target's own cell (see `LevelServer.spawnArcProjectile`), so a
            // lob with nothing to fall on is a shot that lands nowhere. Aim is not re-decided -
            // the lob was thrown, and looking again here would be a second answer to "what is it
            // falling on".
            if (pending.target() == null || pending.target().isRemoved()) {
                continue;
            }
            level.spawnArcProjectile(pending.ref(), pending.x(), pending.y(), plant, pending.target());
            level.emitEffect(PlantShots.MUZZLE_PARTICLE, pending.x(),
                    pending.y() + plant.height() + PlantShots.LOB_MUZZLE_HEIGHT,
                    sound.orElseGet(() -> plant.def().sounds().shoot().orElse(PvzceSounds.PLANT_THROW)));
        }
    }

    @Override
    public void save(CompoundTag tag) {
        clock.save(tag);
        tag.putInt("cooldown", cooldown);
    }

    @Override
    public void load(CompoundTag tag) {
        clock.load(tag);
        cooldown = tag.getInt("cooldown");
    }
}
