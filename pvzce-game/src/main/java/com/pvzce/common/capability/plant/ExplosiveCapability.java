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

import java.util.Optional;

/**
 * Single-use explosive plant, covering both classic shapes:
 *
 * <ul>
 *   <li>{@link Trigger#TIMED} - arms for {@code fuse_ticks} and then detonates
 *       (cherry bomb);</li>
 *   <li>{@link Trigger#PROXIMITY} - arms for {@code fuse_ticks}, then waits for a
 *       ground zombie to step within {@code trigger_range} cells (potato mine).</li>
 * </ul>
 *
 * <p>Previously these were two separate string branches ({@code pvzce:ash},
 * {@code pvzce:mine}) with the fuse smuggled through {@code attack_interval} and
 * the mine's proximity rule hard-coded; the two also disagreed on which position
 * they used as the blast centre.
 */
public final class ExplosiveCapability implements PlantCapability {
    public enum Trigger {
        TIMED,
        PROXIMITY
    }

    public static final int DEFAULT_FUSE = 60;
    /** Minimum blast radius so a proximity mine still covers its own cell. */
    public static final float MIN_RADIUS = 0.55F;

    private final Trigger trigger;
    private final int fuseTicks;
    private final float radius;
    private final int damage;
    private final float triggerRange;
    private final Optional<Identifier> sound;

    private int fuse;

    public ExplosiveCapability(Trigger trigger, int fuseTicks, float radius, int damage, float triggerRange,
                               Optional<Identifier> sound) {
        this.trigger = trigger;
        this.fuseTicks = Math.max(0, fuseTicks);
        this.radius = Math.max(0F, radius);
        this.damage = Math.max(0, damage);
        this.triggerRange = Math.max(0F, triggerRange);
        this.sound = sound;
        this.fuse = this.fuseTicks;
    }

    public static final MapCodec<ExplosiveCapability> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.STRING.optionalFieldOf("trigger", "timed")
                    .xmap(ExplosiveCapability::parseTrigger, Trigger::name)
                    .forGetter(ExplosiveCapability::trigger),
            Codec.INT.optionalFieldOf("fuse_ticks", DEFAULT_FUSE).forGetter(ExplosiveCapability::fuseTicks),
            Codec.FLOAT.optionalFieldOf("radius", 1F).forGetter(ExplosiveCapability::radius),
            Codec.INT.optionalFieldOf("damage", 1800).forGetter(ExplosiveCapability::damage),
            Codec.FLOAT.optionalFieldOf("trigger_range", 0.6F).forGetter(ExplosiveCapability::triggerRange),
            Identifier.CODEC.optionalFieldOf("sound").forGetter(ExplosiveCapability::sound)
    ).apply(i, ExplosiveCapability::new));

    private static Trigger parseTrigger(String name) {
        return "proximity".equalsIgnoreCase(name) ? Trigger.PROXIMITY : Trigger.TIMED;
    }

    public Trigger trigger() {
        return trigger;
    }

    public int fuseTicks() {
        return fuseTicks;
    }

    public float radius() {
        return radius;
    }

    public int damage() {
        return damage;
    }

    public float triggerRange() {
        return triggerRange;
    }

    public Optional<Identifier> sound() {
        return sound;
    }

    /** Remaining fuse ticks; zero means "armed and ready to detonate". */
    public int fuseLeft() {
        return fuse;
    }

    @Override
    public PlantCapability instantiate() {
        return new ExplosiveCapability(trigger, fuseTicks, radius, damage, triggerRange, sound);
    }

    @Override
    public void tick(PlantEntity plant, LevelAccess level) {
        if (fuse > 0) {
            fuse--;
            plant.setAnimation(trigger == Trigger.PROXIMITY
                    ? (fuse == 0 ? EntityAnimations.ARMED : EntityAnimations.GROW)
                    : EntityAnimations.GROW);
            if (trigger == Trigger.TIMED && fuse == 0) {
                detonate(plant, level);
            }
            return;
        }
        if (trigger == Trigger.TIMED) {
            detonate(plant, level);
            return;
        }
        ZombieEntity target = level.zombiesInRow(plant.gridY()).stream()
                .filter(z -> !z.isRemoved() && z.canBeHitByGround()
                        && Math.abs(z.cellX() - plant.cellX()) < triggerRange)
                .findFirst()
                .orElse(null);
        if (target == null) {
            plant.setAnimation(EntityAnimations.ARMED);
            return;
        }
        detonate(plant, level);
    }

    private void detonate(PlantEntity plant, LevelAccess level) {
        plant.setAnimation(EntityAnimations.EXPLODE);
        level.damageArea(plant.cellX(), plant.cellY(), Math.max(MIN_RADIUS, radius), damage, plant.team());
        level.emitEffect("pvzce:ash_smoke", plant.cellX(), plant.cellY(),
                sound.orElseGet(() -> plant.def().sounds().explode().orElse(PvzceSounds.EFFECT_EXPLOSION)));
        plant.remove();
    }

    @Override
    public void save(CompoundTag tag) {
        tag.putInt("fuse", fuse);
    }

    @Override
    public void load(CompoundTag tag) {
        fuse = tag.getInt("fuse");
    }
}
