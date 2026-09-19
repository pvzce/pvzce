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
        if (remainingChewTicks > 0) {
            remainingChewTicks--;
            plant.setState(EntityAnimations.CHEW);
            if (remainingChewTicks == 0) {
                plant.remove();
            }
            return;
        }
        ZombieEntity target = level.enemiesInRow(plant.gridY(), plant.team()).stream()
                .filter(z -> !z.isRemoved() && Math.abs(z.cellX() - plant.cellX()) < range)
                .findFirst()
                .orElse(null);
        if (target == null || target.health() > swallowMaxHealth) {
            plant.setState(EntityAnimations.IDLE);
            return;
        }
        target.remove();
        remainingChewTicks = Math.max(1, chewTicks);
        plant.setState(EntityAnimations.CHEW);
        level.emitEffect(PvzceParticles.CHOMP.toString(), plant.cellX(), plant.cellY(),
                sound.orElseGet(() -> plant.def().sounds().melee().orElse(PvzceSounds.EFFECT_BITE)));
    }

    @Override
    public void save(CompoundTag tag) {
        tag.putInt("chew", remainingChewTicks);
    }

    @Override
    public void load(CompoundTag tag) {
        remainingChewTicks = tag.getInt("chew");
    }
}
