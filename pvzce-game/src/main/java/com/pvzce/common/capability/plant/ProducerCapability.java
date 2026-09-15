package com.pvzce.common.capability.plant;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.content.capability.PlantCapability;
import com.pvzce.api.entity.EntityAnimations;
import com.pvzce.api.entity.LevelAccess;
import com.pvzce.api.util.Identifier;
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
 *
 * <p><b>A producer that declares no sound is silent</b>, and that is a real answer rather
 * than a missing value: the sunflower used to fall back to {@code sfx/ui/points}, which is
 * the sound of <em>collecting</em> a sun - so a flower that had just made one played the
 * chime of somebody picking it up, every eighteen seconds, whether or not anybody clicked
 * it. The original's sunflower is silent when it produces; the chime belongs to the pickup,
 * which is where {@code ResourceDef.pickup_sound} already plays it. The marigold
 * deliberately keeps its own coin ring, because its drop is small change rather than the
 * resource the level is played with.
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
        // No fallback sound: "the definition did not name one" means the plant makes no
        // noise, and the chime that used to stand in for it belongs to the pickup. An
        // empty id is what LevelServer already reads as "play nothing".
        level.emitEffect(PvzceParticles.LANTERN_SHINE.toString(), plant.cellX(), plant.cellY(),
                sound.or(() -> plant.def().sounds().produce()).orElse(null));
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
