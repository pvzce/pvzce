package com.pvzce.common.capability.zombie;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.content.ProjectileRef;
import com.pvzce.api.content.capability.ZombieCapability;
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
 * A zombie that shoots back (the ZomBotany line).
 *
 * <p>The original's Plants vs. Zombies minigame turned the game around: the zombies wear the
 * plants' heads and use their weapons, so the lawn the player built is being shot at with the
 * same peas they have been firing. This is that, and it is the only zombie capability that
 * <em>attacks at range</em> rather than by walking into something.
 *
 * <h2>What it aims at</h2>
 *
 * <p>The nearest plant in its own lane, exactly the way a shooter picks a zombie - the same
 * question asked from the other side. A zombie shooter that fired at anything anywhere would make
 * the lawn's geometry irrelevant, which is the one thing the whole game is about.
 *
 * <h2>Where the shots come from</h2>
 *
 * <p>The zombie's own cell, travelling left. The projectile is spawned through the level's own
 * {@code spawnProjectile}, so a ZomBotany pea is an ordinary pea: it has the same art, the same
 * speed and the same damage type, and the same code decides whether it hits. Nothing about
 * "zombie ammunition" is a special case.
 */
public final class ZombieShooterCapability implements ZombieCapability, com.pvzce.api.content.capability.PlantCapability {
    public static final int DEFAULT_INTERVAL_TICKS = 90;

    private final int intervalTicks;
    private final int damage;
    private final int count;
    private final Identifier projectile;
    private final Optional<Identifier> sound;

    private final com.pvzce.common.capability.plant.ShooterCapability engine;

    public ZombieShooterCapability(int intervalTicks, int damage, int count, Identifier projectile,
                                   Optional<Identifier> sound) {
        this.intervalTicks = Math.max(1, intervalTicks);
        this.damage = Math.max(0, damage);
        this.count = Math.max(1, count);
        this.projectile = projectile == null
                ? Identifier.withDefaultNamespace("pea") : projectile;
        this.sound = sound;
        this.engine = new com.pvzce.common.capability.plant.ShooterCapability(this.intervalTicks,
                java.util.List.of(new ProjectileRef(this.projectile, this.damage, this.count)), sound, 0);
        CompoundTag initial = new CompoundTag(); initial.putInt("cooldown", 0); engine.load(initial);
    }

    public static final MapCodec<ZombieShooterCapability> CODEC =
            RecordCodecBuilder.mapCodec(i -> i.group(
                    Codec.INT.optionalFieldOf("interval", DEFAULT_INTERVAL_TICKS)
                            .forGetter(ZombieShooterCapability::intervalTicks),
                    Codec.INT.optionalFieldOf("damage", 20)
                            .forGetter(ZombieShooterCapability::damage),
                    Codec.INT.optionalFieldOf("count", 1)
                            .forGetter(ZombieShooterCapability::count),
                    Identifier.CODEC.optionalFieldOf("projectile")
                            .forGetter(capability -> Optional.of(capability.projectile())),
                    Identifier.CODEC.optionalFieldOf("sound")
                            .forGetter(ZombieShooterCapability::sound)
            ).apply(i, (interval, damage, count, projectile, sound) -> new ZombieShooterCapability(
                    interval, damage, count, projectile.orElse(null), sound)));

    public int intervalTicks() {
        return intervalTicks;
    }

    public int damage() {
        return damage;
    }

    public int count() {
        return count;
    }

    public Identifier projectile() {
        return projectile;
    }

    public Optional<Identifier> sound() {
        return sound;
    }

    @Override
    public ZombieShooterCapability instantiate() {
        return new ZombieShooterCapability(intervalTicks, damage, count, projectile, sound);
    }

    /**
     * Fires without giving up the walk.
     *
     * <p>{@code tick} rather than {@code tickMovement}: unlike the bungee zombie, a ZomBotany
     * zombie is an ordinary body that happens to be armed, so it keeps walking and biting and
     * shoots on its own clock on top of that.
     */
    @Override
    public void tick(ZombieEntity zombie, LevelAccess level) { engine.tick(zombie, level); }
    @Override
    public void tick(PlantEntity plant, LevelAccess level) { engine.tick(plant, level); }
    @Override
    public boolean holdsFire(PlantEntity plant) { return engine.holdsFire(plant); }
    @Override
    public boolean hasPendingWork(PlantEntity plant) { return engine.hasPendingWork(plant); }
    @Override
    public void tickPending(PlantEntity plant, LevelAccess level) { engine.tickPending(plant, level); }
    @Override
    public boolean strike(PlantEntity plant, LevelAccess level) { return engine.strike(plant, level); }

    @Override
    public void save(CompoundTag tag) {
        engine.save(tag);
    }

    @Override
    public void load(CompoundTag tag) {
        engine.load(tag);
    }
}
