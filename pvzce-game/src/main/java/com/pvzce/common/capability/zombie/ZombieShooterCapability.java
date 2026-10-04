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
public final class ZombieShooterCapability implements ZombieCapability {
    public static final int DEFAULT_INTERVAL_TICKS = 90;

    private final int intervalTicks;
    private final int damage;
    private final int count;
    private final Identifier projectile;
    private final Optional<Identifier> sound;

    private int cooldown;

    public ZombieShooterCapability(int intervalTicks, int damage, int count, Identifier projectile,
                                   Optional<Identifier> sound) {
        this.intervalTicks = Math.max(1, intervalTicks);
        this.damage = Math.max(0, damage);
        this.count = Math.max(1, count);
        this.projectile = projectile == null
                ? Identifier.withDefaultNamespace("pea") : projectile;
        this.sound = sound;
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
    public ZombieCapability instantiate() {
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
    public void tick(ZombieEntity zombie, LevelAccess level) {
        if (cooldown > 0) {
            cooldown--;
            return;
        }
        PlantEntity target = nearestPlant(zombie, level);
        if (target == null) {
            // Nothing in front of it: the clock is not reset, so the first plant to come into
            // the lane is fired at immediately rather than up to a second and a half later.
            return;
        }
        for (int shot = 0; shot < count; shot++) {
            // Every shot leaves on the same tick, and a burst is expressed by `count` rather than
            // by a delay: this is the gatling head's whole difference from the pea head, and a
            // stagger would make it look like a slower plant rather than a faster one.
            // `backward` is what makes it travel toward the house: a projectile's direction is
            // +1 down the lawn, and a zombie's shot goes the other way. Firing a forward ref
            // looked exactly like "the zombie never shoots", because the pea left the screen.
            ProjectileRef ref = new ProjectileRef(projectile, damage, 1, 0, true, 1,
                    ProjectileRef.UNLIMITED_RANGE, 0);
            // `cellY()` is already the lane's centre: adding a half would fire into the lane
            // below, which is a mistake that looks like "the zombie never shoots".
            level.spawnZombieProjectile(ref, zombie.cellX() - 0.3F, zombie.cellY(), zombie);
        }
        zombie.setAnimation(EntityAnimations.SHOOT);
        level.emitEffect("", zombie.position(), zombie.surfaceId(), sound.orElse(PvzceSounds.PLANT_SHOOT_PEA));
        cooldown = intervalTicks;
    }

    /**
     * The nearest plant to this zombie's left.
     *
     * <p>"To its left" because a zombie walks left: the plants behind it are the ones it has
     * already passed, and shooting backwards would be a different zombie.
     */
    private PlantEntity nearestPlant(ZombieEntity zombie, LevelAccess level) {
        PlantEntity best = null;
        for (int column = 0; column <= zombie.gridX(); column++) {
            PlantEntity plant = level.plantAt(column, zombie.gridY(), zombie.surfaceId());
            if (plant != null && !plant.isRemoved()) {
                // The loop runs left to right, so the last one found is the nearest.
                best = plant;
            }
        }
        return best;
    }

    @Override
    public void save(CompoundTag tag) {
        tag.putInt("cooldown", cooldown);
    }

    @Override
    public void load(CompoundTag tag) {
        cooldown = Math.max(0, tag.getInt("cooldown"));
    }
}
