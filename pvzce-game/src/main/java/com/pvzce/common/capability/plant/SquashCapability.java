package com.pvzce.common.capability.plant;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.content.capability.PlantCapability;
import com.pvzce.api.entity.EntityAnimations;
import com.pvzce.api.entity.LevelAccess;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.PvzceSounds;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.server.entity.PlantEntity;
import com.pvzce.server.entity.ZombieEntity;

import java.util.Optional;

/**
 * Leaps onto the zombie that walks into range and squashes it flat (the squash).
 *
 * <p>Not an {@link ExplosiveCapability}, and the difference is the point. A blast is an
 * <em>area</em> - it hurts everything standing in a footprint, leaves a crater, and reads as a
 * bomb. The squash is a <em>target</em>: it picks the one zombie that tripped it, lands on that
 * one, and is spent. It used to be written as a proximity explosive (radius 1.0, square, 1800
 * damage), which meant it killed the neighbours too, scorched the lawn, and - because every
 * proximity explosive publishes {@code armed}/{@code armed_loop} while it waits - asked for two
 * clips the squash's art does not have, so it silently animated as {@code idle} the whole time it
 * was winding up.
 *
 * <h2>How the four beats are told</h2>
 *
 * <p>{@code idle} → {@code look_left}/{@code look_right} (the glance: the original draws the squash
 * turning its eyes towards the zombie that tripped it) → {@code grow} (the leap, which the art
 * authors as a crouch and then a jump up) → the strike → {@code explode} (the art's landing pose;
 * the clip is named for what the original's animator called it, and it is the shape a squash leaves
 * behind). The plant removes itself after the landing pose, so there is no "armed" state to draw.
 *
 * <p><b>The fuse is the leap's own length, not a round second.</b> The art's jump clip is 1.25s and
 * the strike used to happen on a 1.0s fuse, so the squash was cut off mid-jump: the player saw it
 * mash itself into a pancake and then teleport into the landing pose. {@link #DEFAULT_FUSE_TICKS}
 * is 75 ticks for that reason, and a test pins it against the clip's length.
 *
 * <p><b>It lands on the zombie.</b> The plant is moved to the target's own cell as it comes down
 * (nudged to the near edge, so the two sprites overlap rather than one hiding the other) - the
 * original draws a squash flattening the thing it landed on, and a mash in the neighbouring cell
 * reads as "it flattened the grass next to me".
 *
 * <p>The damage goes through {@link ZombieEntity#damage} as {@code pvzce:crush}: it ignores armour
 * (a squash flattens a Buckethead, bucket and all - the whole reason to bring one) and it does not
 * burn, so the zombie dies an ordinary death instead of being left as the ash line's charred body.
 */
public final class SquashCapability implements PlantCapability {
    /** How close a zombie has to be, in cells, before the squash commits. */
    public static final float DEFAULT_TRIGGER_RANGE = 0.5F;
    /**
     * How long the leap takes, in ticks.
     *
     * <p>Matches the art's own {@code grow} clip (1.25s): a shorter fuse cuts the jump off, which
     * is exactly what the old 60 ticks did.
     */
    public static final int DEFAULT_FUSE_TICKS = 75;
    /**
     * How long the glance lasts, in ticks.
     *
     * <p>A quarter of a second: the art's {@code look_left}/{@code look_right} clip is half a
     * second long, and this is "it noticed you", not a pose to sit in.
     */
    public static final int LOOK_TICKS = 15;
    /** How far short of the target's centre the squash comes down, in cells. */
    public static final float LANDING_OFFSET = 0.3F;
    /**
     * What one landing is worth.
     *
     * <p>Deliberately far above any ordinary zombie's health and far below a Gargantuar's: the
     * squash is the answer to "one zombie I cannot shoot down in time", not to a whole wave.
     */
    public static final int DEFAULT_DAMAGE = 1800;

    private final float triggerRange;
    private final int fuseTicks;
    private final int damage;
    private final Identifier damageType;
    private final Optional<Identifier> sound;

    /** Ticks left of the leap, or 0 when the squash is still standing there deciding. */
    private int fuseLeft;
    /** Ticks left of the glance, or 0 when it has not noticed anything yet. */
    private int lookLeft;
    /** Which way the glance goes: the side the target is on. */
    private boolean targetOnTheLeft;
    /** The zombie the leap was committed to, as an entity id, or -1. */
    private int targetId = -1;
    /** Ticks the flattened pose is held before the plant goes; see {@link #land}. */
    private int lingerLeft;
    /**
     * How long the landing pose is held, in ticks.
     *
     * <p>Public because a test reads it against the {@code explode} clip's own length: a linger
     * shorter than the clip cuts the landing off mid-gesture, and the squash's clip is exactly this
     * long. Same invariant the ash-line plants are held to.
     */
    public static final int LINGER_TICKS = 40;

    public SquashCapability(float triggerRange, int fuseTicks, int damage, Identifier damageType,
                            Optional<Identifier> sound) {
        this.triggerRange = Math.max(0F, triggerRange);
        this.fuseTicks = Math.max(1, fuseTicks);
        this.damage = Math.max(1, damage);
        this.damageType = damageType == null ? PvzceIds.DAMAGE_CRUSH : damageType;
        this.sound = sound;
    }

    public static final MapCodec<SquashCapability> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.FLOAT.optionalFieldOf("trigger_range", DEFAULT_TRIGGER_RANGE)
                    .forGetter(SquashCapability::triggerRange),
            Codec.INT.optionalFieldOf("fuse_ticks", DEFAULT_FUSE_TICKS)
                    .forGetter(SquashCapability::fuseTicks),
            Codec.INT.optionalFieldOf("damage", DEFAULT_DAMAGE).forGetter(SquashCapability::damage),
            Identifier.CODEC.optionalFieldOf("damage_type", PvzceIds.DAMAGE_CRUSH)
                    .forGetter(SquashCapability::damageType),
            Identifier.CODEC.optionalFieldOf("sound").forGetter(SquashCapability::sound)
    ).apply(i, SquashCapability::new));

    public float triggerRange() {
        return triggerRange;
    }

    public int fuseTicks() {
        return fuseTicks;
    }

    public int damage() {
        return damage;
    }

    /** How long the landing pose is held after the strike. */
    public int lingerTicks() {
        return LINGER_TICKS;
    }

    public Identifier damageType() {
        return damageType;
    }

    public Optional<Identifier> sound() {
        return sound;
    }

    @Override
    public PlantCapability instantiate() {
        return new SquashCapability(triggerRange, fuseTicks, damage, damageType, sound);
    }

    @Override
    public void tick(PlantEntity plant, LevelAccess level) {
        if (lingerLeft > 0) {
            // The flattened pose, held for a beat before the plant goes. Drawn from the same
            // "explode" clip the art already had - it is the shape a squash leaves behind, and the
            // name is the original animator's, not a claim that anything exploded.
            lingerLeft--;
            plant.setState(EntityAnimations.EXPLODE);
            if (lingerLeft == 0) {
                plant.remove();
            }
            return;
        }
        if (lookLeft > 0) {
            // The glance: the original turns the eyes towards the zombie before it jumps, which is
            // what tells the player *which* zombie this squash has picked.
            lookLeft--;
            plant.setState(targetOnTheLeft ? EntityAnimations.LOOK_LEFT : EntityAnimations.LOOK_RIGHT);
            if (lookLeft == 0) {
                fuseLeft = fuseTicks;
            }
            return;
        }
        if (fuseLeft > 0) {
            fuseLeft--;
            plant.setState(EntityAnimations.GROW);
            if (fuseLeft == 0) {
                land(plant, level);
            }
            return;
        }
        ZombieEntity target = findTarget(plant, level);
        if (target == null) {
            plant.setState(EntityAnimations.IDLE);
            return;
        }
        targetId = target.id();
        targetOnTheLeft = target.cellX() < plant.cellX();
        lookLeft = LOOK_TICKS;
        plant.setState(targetOnTheLeft ? EntityAnimations.LOOK_LEFT : EntityAnimations.LOOK_RIGHT);
    }

    /**
     * Comes down on the zombie it committed to.
     *
     * <p>The target is looked up again rather than held: in the second between noticing and
     * landing, something else may have killed it - a pea, another squash, a mower. A squash that
     * landed on a corpse anyway would be a card spent on nothing, which is why a leap whose target
     * is gone still spends the plant (it jumped) but does not pretend to have hit anything.
     */
    private void land(PlantEntity plant, LevelAccess level) {
        ZombieEntity target = null;
        for (ZombieEntity zombie : level.enemiesInRow(plant.gridY(), plant.team())) {
            if (zombie.id() == targetId && !zombie.isRemoved()) {
                target = zombie;
                break;
            }
        }
        if (target != null) {
            // Down onto the zombie, not beside it. A nudge towards the side the squash came from
            // keeps the two drawings overlapping instead of one covering the other - the zombies
            // draw after the plants (see EntityVisuals#renderOrder), so landing dead centre would
            // put the corpse in front of the mash.
            boolean fromTheLeft = plant.cellX() <= target.cellX();
            plant.setCellX(target.cellX() + (fromTheLeft ? -LANDING_OFFSET : LANDING_OFFSET));
            // `pvzce:crush` by default: armour does not save the zombie, and nothing is burned.
            target.damage(damage, ZombieEntity.damageType(damageType), level);
        }
        level.emitEffect("", plant.cellX(), plant.cellY(),
                sound.orElseGet(() -> plant.def().sounds().explode()
                        .orElse(PvzceSounds.EFFECT_BONK)));
        plant.setState(EntityAnimations.EXPLODE);
        lingerLeft = LINGER_TICKS;
    }

    /**
     * The nearest enemy in this row that is close enough to commit to.
     *
     * <p>Same row only. The squash is a physical plant standing on one lane; reaching into the lane
     * above because a zombie happened to be near in world units is what the blast used to do, and
     * it is exactly the behaviour the report asked to be rid of.
     */
    private ZombieEntity findTarget(PlantEntity plant, LevelAccess level) {
        ZombieEntity best = null;
        float bestDistance = Float.MAX_VALUE;
        for (ZombieEntity zombie : level.enemiesInRow(plant.gridY(), plant.team())) {
            if (zombie.isRemoved()) {
                continue;
            }
            float distance = Math.abs(zombie.cellX() - plant.cellX());
            if (distance <= triggerRange && distance < bestDistance) {
                best = zombie;
                bestDistance = distance;
            }
        }
        return best;
    }

    @Override
    public void save(CompoundTag tag) {
        tag.putInt("fuse", fuseLeft);
        tag.putInt("look", lookLeft);
        tag.putInt("lookLeft", targetOnTheLeft ? 1 : 0);
        tag.putInt("target", targetId);
        tag.putInt("linger", lingerLeft);
    }

    @Override
    public void load(CompoundTag tag) {
        fuseLeft = Math.max(0, tag.getInt("fuse"));
        lookLeft = Math.max(0, tag.getInt("look"));
        targetOnTheLeft = tag.getInt("lookLeft") != 0;
        targetId = tag.contains("target") ? tag.getInt("target") : -1;
        lingerLeft = Math.max(0, tag.getInt("linger"));
    }
}
