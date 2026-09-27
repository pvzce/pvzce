package com.pvzce.common.capability.plant;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.content.capability.PlantCapability;
import com.pvzce.api.entity.EntityAnimations;
import com.pvzce.api.entity.EntityLayers;
import com.pvzce.api.entity.LevelAccess;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.PvzceSounds;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.server.entity.PlantEntity;
import com.pvzce.server.entity.ZombieEntity;

import java.util.Optional;

/**
 * Hurts whatever is standing on it, over and over (the spikeweed).
 *
 * <p>The one plant a zombie does not stop for. Everything else on the lawn is eaten - that is what
 * a plant is for - but a spikeweed is a thing you walk over, and the damage is what happens while
 * you do. That behaviour is not this capability's: {@code LevelServer.biteTargetAt} skips plants
 * tagged {@code #c:walk_over}, which is where "the zombie does not stop" lives. What lives here is
 * the other half, the stabbing.
 *
 * <h2>Why the clock is per plant, not per zombie</h2>
 *
 * <p>A spikeweed damages <em>everything in its cell</em> on its own interval, rather than each
 * zombie being damaged on arrival. The difference is what a row of them does to a wave: with a
 * per-zombie clock, ten zombies walking over one spikeweed take ten times the damage of one, which
 * makes the plant scale with the wave rather than with the lawn. The original's own spikeweed is a
 * fixed damage-per-second patch, and that is what this is.
 *
 * <p>The animation is asked for on the ticks it fires, so the plant visibly jabs. Nothing else
 * publishes {@code attack} for it - it has no target and no cooldown of its own - so without this
 * the spikeweed's attack clip would be a clip nothing can ask for.
 */
public final class SpikeCapability implements PlantCapability {
    /** How often it stabs, in ticks. */
    public static final int DEFAULT_INTERVAL_TICKS = 30;
    /** What one stab is worth. */
    public static final int DEFAULT_DAMAGE = 20;
    /**
     * The damage type, and it is deliberately not {@code ash}.
     *
     * <p>{@code pvzce:impact} is what the mallet uses: armour absorbs one hit and shatters, and
     * nothing passes through to the body. That is exactly right for a spike - a buckethead can walk
     * over a spikeweed twice and lose the bucket, which is a real answer to a real problem, and it
     * is not the instant kill the spikeweed would be with an armour-ignoring type.
     */
    public static final Identifier DEFAULT_DAMAGE_TYPE = PvzceIds.DAMAGE_IMPACT;

    private final int intervalTicks;
    private final int damage;
    private final Identifier damageType;
    private final float range;
    /**
     * How many rows either side of the plant the patch reaches; 0 is the plant's own row.
     *
     * <p>A spikeweed is one cell wide, so the original never needed the question. The gloom-shroom
     * does: it bursts spores into every adjacent space, and a patch that hit only its own row would
     * be a plant whose art says "all around" and whose damage says "in front of it".
     */
    private final int rows;
    private final Optional<Identifier> sound;

    /** Ticks until the next stab; counts down from {@link #intervalTicks}. */
    private int cooldown;
    /** True on the tick a stab happened, so the attack clip is asked for exactly once per stab. */
    private boolean stabbing;

    public SpikeCapability(int intervalTicks, int damage, Identifier damageType, float range,
                           int rows, Optional<Identifier> sound) {
        this.intervalTicks = Math.max(1, intervalTicks);
        this.damage = Math.max(1, damage);
        this.damageType = damageType == null ? DEFAULT_DAMAGE_TYPE : damageType;
        this.range = Math.max(0.1F, range);
        this.rows = Math.max(0, rows);
        this.sound = sound;
    }

    public static final MapCodec<SpikeCapability> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.INT.optionalFieldOf("interval", DEFAULT_INTERVAL_TICKS)
                    .forGetter(SpikeCapability::intervalTicks),
            Codec.INT.optionalFieldOf("damage", DEFAULT_DAMAGE).forGetter(SpikeCapability::damage),
            Identifier.CODEC.optionalFieldOf("damage_type", DEFAULT_DAMAGE_TYPE)
                    .forGetter(SpikeCapability::damageType),
            Codec.FLOAT.optionalFieldOf("range", 0.6F).forGetter(SpikeCapability::range),
            Codec.INT.optionalFieldOf("rows", 0).forGetter(SpikeCapability::rows),
            Identifier.CODEC.optionalFieldOf("sound").forGetter(SpikeCapability::sound)
    ).apply(i, SpikeCapability::new));

    public int intervalTicks() {
        return intervalTicks;
    }

    public int damage() {
        return damage;
    }

    public Identifier damageType() {
        return damageType;
    }

    public float range() {
        return range;
    }

    public int rows() {
        return rows;
    }

    public Optional<Identifier> sound() {
        return sound;
    }

    @Override
    public PlantCapability instantiate() {
        return new SpikeCapability(intervalTicks, damage, damageType, range, rows, sound);
    }

    @Override
    public void tick(PlantEntity plant, LevelAccess level) {
        stabbing = false;
        cooldown -= 1;
        if (cooldown > 0) {
            plant.setState(EntityAnimations.IDLE);
            return;
        }
        if (stab(plant, level)) {
            stabbing = true;
            // The plant's own rate: watered counts faster, and a mutation that rewrites how fast
            // plants work at all reaches this clock for free.
            cooldown = Math.max(1, Math.round(intervalTicks / Math.max(0.1F, plant.actionRate(1F))));
            plant.setState(EntityAnimations.ATTACK);
            if (sound.isPresent()) {
                level.emitEffect("", plant.cellX(), plant.cellY(), sound.get());
            }
        } else {
            // Nothing to stab: the clock still ran out, so it is reset rather than left at zero.
            // A spikeweed that checked every tick until something arrived would stab on the very
            // tick a zombie stepped on rather than on its own beat, which is faster than the
            // interval the plant declares.
            cooldown = intervalTicks;
            plant.setState(EntityAnimations.IDLE);
        }
    }

    /** This plant attacks on its own clock, so a hold-fire level makes it wait for an order. */
    @Override
    public boolean holdsFire(PlantEntity plant) {
        return true;
    }

    /**
     * Stabs everything standing on the patch, now.
     *
     * <p>An order does not restart the clock: the plant's own interval is left where it was, so a
     * played note adds a stab rather than replacing the one the patch was already counting down
     * to. The animation is asked for here as the clock asks for it, because a patch that hurt
     * something without visibly jabbing would read as a bug.
     */
    @Override
    public boolean strike(PlantEntity plant, LevelAccess level) {
        boolean hit = stab(plant, level);
        if (hit) {
            stabbing = true;
            plant.setState(EntityAnimations.ATTACK);
            if (sound.isPresent()) {
                level.emitEffect("", plant.cellX(), plant.cellY(), sound.get());
            }
        }
        return hit;
    }

    /** One stab across every row this patch reaches; answers whether it hit anything. */
    private boolean stab(PlantEntity plant, LevelAccess level) {
        boolean hit = false;
        // Its own row, plus `rows` either side: the spikeweed's one row and the gloom-shroom's
        // "every adjacent space" are this same loop with a different number.
        for (int row = plant.gridY() - rows; row <= plant.gridY() + rows; row++) {
            if (row >= 0 && row < level.height()) {
                hit |= stabRow(plant, level, row);
            }
        }
        return hit;
    }

    /** True on the tick this plant last stabbed. Read by the animation, not by the simulation. */
    public boolean stabbing() {
        return stabbing;
    }

    /** Damages everything this patch reaches in one row; answers whether it hit anything. */
    private boolean stabRow(PlantEntity plant, LevelAccess level, int row) {
        boolean hit = false;
        for (ZombieEntity zombie : level.enemiesInRow(row, plant.team())) {
            if (zombie.isRemoved() || zombie.layer() != EntityLayers.GROUND) {
                // Underground diggers and fliers pass over it for the same reason they pass over
                // a mower: the predicate is the zombie's own layer, so "on the ground" has one
                // definition in this engine rather than one per fixture.
                continue;
            }
            if (Math.abs(zombie.cellX() - plant.cellX()) > range) {
                continue;
            }
            zombie.damage(damage, ZombieEntity.damageType(damageType), level);
            hit = true;
        }
        return hit;
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
