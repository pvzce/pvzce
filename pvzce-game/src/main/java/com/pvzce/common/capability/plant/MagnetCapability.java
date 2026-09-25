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
import com.pvzce.server.entity.ZombieEntity;

import java.util.Optional;

/**
 * Pulls the armour off a nearby zombie (the magnet-shroom).
 *
 * <p>The answer to a lane of bucketheads: instead of out-damaging the armour, it takes the armour
 * away, and every plant behind it is suddenly shooting at a bare zombie. One target at a time, on
 * a long clock - a magnet-shroom that stripped a whole wave would make every armoured zombie in the
 * game pointless.
 *
 * <h2>What "stripping" means</h2>
 *
 * <p>The armour is removed outright rather than damaged: {@code ArmorCapability} already knows how
 * to lose a piece (that is what happens when its durability runs out), so this asks it to and lets
 * the existing consequences follow - the equipment stops being drawn, the zombie's speed and its
 * eating rate go back to the bare ones, and a subsequent hit reaches the body. Nothing about that
 * is re-implemented here, which is why the capability is twenty lines of targeting around one call.
 *
 * <p>A zombie with nothing to take is not a target, so a magnet-shroom on a lawn of bare zombies
 * simply waits: it is a counter to armour rather than a damage plant, and pretending otherwise
 * would make it a strictly better puff-shroom.
 */
public final class MagnetCapability implements PlantCapability {
    /** How far it reaches, in cells. Longer than a shooter's lane: it is a utility, not a gun. */
    public static final float DEFAULT_RANGE = 4.5F;
    /** How often it can pull, in ticks. */
    public static final int DEFAULT_INTERVAL_TICKS = 300;

    private final float range;
    private final int intervalTicks;
    private final Optional<Identifier> sound;

    private int cooldown;
    private boolean pulling;

    public MagnetCapability(float range, int intervalTicks, Optional<Identifier> sound) {
        this.range = Math.max(0.5F, range);
        this.intervalTicks = Math.max(1, intervalTicks);
        this.sound = sound;
    }

    public static final MapCodec<MagnetCapability> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.FLOAT.optionalFieldOf("range", DEFAULT_RANGE).forGetter(MagnetCapability::range),
            Codec.INT.optionalFieldOf("interval", DEFAULT_INTERVAL_TICKS)
                    .forGetter(MagnetCapability::intervalTicks),
            Identifier.CODEC.optionalFieldOf("sound").forGetter(MagnetCapability::sound)
    ).apply(i, MagnetCapability::new));

    public float range() {
        return range;
    }

    public int intervalTicks() {
        return intervalTicks;
    }

    public Optional<Identifier> sound() {
        return sound;
    }

    @Override
    public PlantCapability instantiate() {
        return new MagnetCapability(range, intervalTicks, sound);
    }

    @Override
    public void tick(PlantEntity plant, LevelAccess level) {
        pulling = false;
        if (cooldown > 0) {
            cooldown -= 1;
            plant.setState(EntityAnimations.IDLE);
            return;
        }
        ZombieEntity target = nearestArmoured(plant, level);
        if (target == null) {
            // Nothing wearing anything within reach. The clock is *not* reset, so the pull happens
            // on the tick armour walks into range rather than up to five seconds later.
            plant.setState(EntityAnimations.IDLE);
            return;
        }
        if (target.stripArmor(level)) {
            pulling = true;
            plant.setState(EntityAnimations.SHOOT);
            level.emitEffect("", plant.cellX(), plant.cellY(),
                    sound.orElseGet(() -> plant.def().sounds().shoot().orElse(null)));
        }
        cooldown = intervalTicks;
    }

    /** True on the tick this magnet pulled something off. */
    public boolean pulling() {
        return pulling;
    }

    /**
     * The closest enemy within reach that is actually wearing something.
     *
     * <p>Distance in cells on both axes, through the same "how far is this" arithmetic a mallet
     * swing uses: a magnet reaches over the lane boundary, and pretending it only sees its own row
     * would make it useless on the two rows beside a pool.
     */
    private ZombieEntity nearestArmoured(PlantEntity plant, LevelAccess level) {
        ZombieEntity best = null;
        float bestDistance = Float.MAX_VALUE;
        for (ZombieEntity zombie : level.enemiesOf(plant.team())) {
            if (zombie.isRemoved() || !zombie.hasArmor()) {
                continue;
            }
            float dx = zombie.cellX() - plant.cellX();
            float dy = zombie.cellY() - plant.cellY();
            float distance = (float) Math.sqrt(dx * dx + dy * dy);
            if (distance <= range && distance < bestDistance) {
                best = zombie;
                bestDistance = distance;
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
