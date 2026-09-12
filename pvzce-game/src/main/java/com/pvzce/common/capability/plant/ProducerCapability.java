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
import com.pvzce.common.PvzceParticles;

import java.util.Optional;

/**
 * Periodically spawns a resource drop (sunflower sun, marigold coins).
 *
 * <p>Fixes a long-standing data bug: {@code PlantSounds.produce} was parsed from
 * JSON but never emitted, so a sunflower's {@code sfx/ui/points} override was
 * silently ignored. The production sound is now played at the moment of
 * production, where the JSON always claimed it would be.
 */
public final class ProducerCapability implements PlantCapability {
    public static final int DEFAULT_AMOUNT = 25;
    /** The first drop arrives sooner than the steady-state interval. */
    public static final int DEFAULT_FIRST_DELAY = 300;

    private final Identifier resource;
    private final int amount;
    private final int everyTicks;
    private final int firstDelayTicks;
    private final Optional<Identifier> sound;

    private int cooldown;

    public ProducerCapability(Identifier resource, int amount, int everyTicks, int firstDelayTicks,
                              Optional<Identifier> sound) {
        this.resource = resource;
        this.amount = Math.max(1, amount);
        this.everyTicks = Math.max(1, everyTicks);
        this.firstDelayTicks = firstDelayTicks < 0
                ? Math.min(this.everyTicks, DEFAULT_FIRST_DELAY)
                : Math.min(firstDelayTicks, this.everyTicks);
        this.sound = sound;
        this.cooldown = this.firstDelayTicks;
    }

    public static final MapCodec<ProducerCapability> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Identifier.CODEC.fieldOf("resource").forGetter(ProducerCapability::resource),
            Codec.INT.optionalFieldOf("amount", DEFAULT_AMOUNT).forGetter(ProducerCapability::amount),
            Codec.INT.fieldOf("every").forGetter(ProducerCapability::everyTicks),
            Codec.INT.optionalFieldOf("first_delay", -1).forGetter(ProducerCapability::firstDelayTicks),
            Identifier.CODEC.optionalFieldOf("sound").forGetter(ProducerCapability::sound)
    ).apply(i, ProducerCapability::new));

    public Identifier resource() {
        return resource;
    }

    public int amount() {
        return amount;
    }

    public int everyTicks() {
        return everyTicks;
    }

    public int firstDelayTicks() {
        return firstDelayTicks;
    }

    public Optional<Identifier> sound() {
        return sound;
    }

    @Override
    public PlantCapability instantiate() {
        return new ProducerCapability(resource, amount, everyTicks, firstDelayTicks, sound);
    }

    @Override
    public void tick(PlantEntity plant, LevelAccess level) {
        if (cooldown > 0) {
            cooldown--;
        }
        if (cooldown > 0) {
            plant.setAnimation(EntityAnimations.IDLE);
            return;
        }
        cooldown = everyTicks;
        plant.setAnimation(EntityAnimations.PRODUCE);
        level.spawnProducedResource(resource, amount, plant.cellX(), plant.cellY(), plant.team());
        level.emitEffect(PvzceParticles.LANTERN_SHINE.toString(), plant.cellX(), plant.cellY(),
                sound.orElseGet(() -> plant.def().sounds().produce().orElse(PvzceSounds.UI_POINTS)));
    }

    @Override
    public void boost(PlantEntity plant) {
        cooldown = 0;
        plant.setAnimation(EntityAnimations.PRODUCE);
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
