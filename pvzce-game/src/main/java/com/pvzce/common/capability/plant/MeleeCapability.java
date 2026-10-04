package com.pvzce.common.capability.plant;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.content.capability.PlantCapability;
import com.pvzce.api.entity.EntityAnimations;
import com.pvzce.api.entity.LevelAccess;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceSounds;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.server.entity.PlantEntity;
import com.pvzce.server.entity.ZombieEntity;
import com.pvzce.common.PvzceParticles;
import java.util.Optional;

/**
 * Swallows a weak zombie that steps into range, then chews for a while and
 * disappears (chomper).
 */
public final class MeleeCapability implements PlantCapability {
    public static final float DEFAULT_RANGE = 0.7F;
    public static final int DEFAULT_CHEW_TICKS = 240;

    private final com.pvzce.common.level.RateClock clock = new com.pvzce.common.level.RateClock();
    private final float range;
    private final int swallowMaxHealth;
    private final int chewTicks;
    private final Optional<Identifier> sound;

    private int remainingChewTicks;

    public MeleeCapability(float range, int swallowMaxHealth, int chewTicks, Optional<Identifier> sound) {
        this.range = Math.max(0F, range);
        this.swallowMaxHealth = Math.max(0, swallowMaxHealth);
        this.chewTicks = Math.max(0, chewTicks);
        this.sound = sound;
    }

    public static final MapCodec<MeleeCapability> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.FLOAT.optionalFieldOf("range", DEFAULT_RANGE).forGetter(MeleeCapability::range),
            Codec.INT.optionalFieldOf("swallow_max_health", 0).forGetter(MeleeCapability::swallowMaxHealth),
            Codec.INT.optionalFieldOf("chew_ticks", DEFAULT_CHEW_TICKS).forGetter(MeleeCapability::chewTicks),
            Identifier.CODEC.optionalFieldOf("sound").forGetter(MeleeCapability::sound)
    ).apply(i, MeleeCapability::new));

    public float range() {
        return range;
    }

    public int swallowMaxHealth() {
        return swallowMaxHealth;
    }

    public int chewTicks() {
        return chewTicks;
    }

    public Optional<Identifier> sound() {
        return sound;
    }

    @Override
    public PlantCapability instantiate() {
        return new MeleeCapability(range, swallowMaxHealth, chewTicks, sound);
    }

    @Override
    public void tick(PlantEntity plant, LevelAccess level) {
        if (chew(plant)) {
            return;
        }
        if (!swallow(plant, level)) {
            plant.setState(EntityAnimations.IDLE);
        }
    }

    /**
     * A mouth full of zombie is owed work: the bite happened, the thirty seconds of chewing have
     * not.
     *
     * <p>Without this a chomper ordered to bite on a level that holds its fire stayed mid-chew
     * forever - it never finished, so it never left the lawn, so the cell it was standing in stayed
     * occupied by a plant that would never act again (see {@code PlantCapability#hasPendingWork}).
     */
    @Override
    public boolean hasPendingWork(PlantEntity plant) {
        return remainingChewTicks > 0;
    }

    /** One tick of the chew, and nothing else. */
    @Override
    public void tickPending(PlantEntity plant, LevelAccess level) {
        chew(plant);
    }

    /** Counts the chew down; answers whether there was one to count. */
    private boolean chew(PlantEntity plant) {
        if (remainingChewTicks <= 0) {
            return false;
        }
        remainingChewTicks = Math.max(0, remainingChewTicks - clock.step(plant.actionRate()));
        plant.setState(EntityAnimations.CHEW);
        if (remainingChewTicks == 0) {
            plant.remove();
        }
        return true;
    }

    /** This plant attacks on its own clock, so a hold-fire level makes it wait for an order. */
    @Override
    public boolean holdsFire(PlantEntity plant) {
        return true;
    }

    /**
     * Bites, when it is not already chewing and something edible is in reach.
     *
     * <p>A mouth is the one attack that cannot be aimed at nothing: a chomper ordered to bite an
     * empty lane stays shut rather than swallowing air, which is what "the plants in this column
     * attack" means for a column that has no zombie in front of it.
     */
    @Override
    public boolean strike(PlantEntity plant, LevelAccess level) {
        return remainingChewTicks == 0 && swallow(plant, level);
    }

    /** Swallows what is in reach, if anything is; answers whether a bite happened. */
    private boolean swallow(PlantEntity plant, LevelAccess level) {
        ZombieEntity target = level.enemiesInRow(plant.gridY(), plant.team(), plant.surfaceId()).stream()
                .filter(z -> !z.isRemoved() && Math.abs(z.cellX() - plant.cellX()) < range)
                .findFirst()
                .orElse(null);
        if (target == null || target.health() > swallowMaxHealth) {
            return false;
        }
        target.remove();
        remainingChewTicks = Math.max(1, chewTicks);
        plant.setState(EntityAnimations.CHEW);
        level.emitEffect(PvzceParticles.CHOMP.toString(), plant.position(), plant.surfaceId(), sound.orElseGet(() -> plant.def().sounds().melee().orElse(PvzceSounds.EFFECT_BITE)));
        return true;
    }

    @Override
    public void save(CompoundTag tag) {
        clock.save(tag);
        tag.putInt("chew", remainingChewTicks);
    }

    @Override
    public void load(CompoundTag tag) {
        clock.load(tag);
        remainingChewTicks = tag.getInt("chew");
    }
}
