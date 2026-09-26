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
 * <p><b>It jumps there.</b> The leap is the plant's own {@code height}: an arc up to
 * {@link #JUMP_HEIGHT} and sideways to the target's cell, both ending on the landing tick, where
 * the mash plays (nudged to the edge of the target it came from, so the two sprites overlap rather
 * than one hiding the other). The art's own jump is drawn <em>in place</em> - its vertical travel
 * is about a tenth of a cell - which is why the user's report was "它只是平移了，正常应该要飞起来一点".
 *
 * <p>The damage goes through {@link ZombieEntity#damage} as {@code pvzce:crush}: it ignores armour
 * (a squash flattens a Buckethead, bucket and all - the whole reason to bring one) and it does not
 * burn, so the zombie dies an ordinary death instead of being left as the ash line's charred body.
 */
public final class SquashCapability implements PlantCapability {
    /**
     * How close a zombie has to be, in cells, before the squash commits.
     *
     * <p>One cell either side of its own: the user's own number ("前后各一格"). Half a cell was
     * the first cut of it and it read as "the squash only notices what is already eating it" - by
     * then the zombie has been chewing for a bite or two, and a plant cornered like that spends
     * its card on a zombie that is already there.
     */
    public static final float DEFAULT_TRIGGER_RANGE = 1.0F;
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
     * How high the leap takes the plant off the lawn, in cells.
     *
     * <p>The art's own jump is a stretch and a squash drawn <em>in place</em>: its vertical travel
     * converts to about a tenth of a cell, which is why the user's report was "它只是平移了，正常
     * 应该要飞起来一点". The arc is the plant's own {@code height} - the field every entity already
     * has, already streamed to the client - so the leap is a leap and not a slide.
     */
    public static final float JUMP_HEIGHT = 0.6F;
    /**
     * How far into the leap the plant leaves the ground, as a fraction of the wind-up.
     *
     * <p>The clip's first half is the crouch, so the arc starts after it: rising from the first
     * frame would have the squash climbing while the art is still squatting.
     */
    public static final float JUMP_LIFT_START = 0.35F;
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
    /** Where the leap starts: the plant's own column when it committed. */
    private float leapFromX;
    /** Where it comes down: the target's column, nudged towards the side it leapt from. */
    private float leapToX;
    /** The plant's resting height, restored when it lands (a flower pot lifts it). */
    private float leapBaseHeight;
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
                beginLeap(plant, level);
                fuseLeft = fuseTicks;
            }
            return;
        }
        if (fuseLeft > 0) {
            fuseLeft--;
            plant.setState(EntityAnimations.GROW);
            advanceLeap(plant);
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
        // "Hmm?" - the noise belongs to *noticing*, which is what the user reported: it used to
        // be played on the landing, so the squash grunted after it had already flattened
        // something. The capability's own `sound` is that notice; the plant's `explode` slot is
        // the landing (the art has two takes of the same line, and this uses both).
        if (sound.isPresent()) {
            level.emitEffect("", plant.cellX(), plant.cellY(), sound.get());
        }
        lookLeft = LOOK_TICKS;
        plant.setState(targetOnTheLeft ? EntityAnimations.LOOK_LEFT : EntityAnimations.LOOK_RIGHT);
    }

    /**
     * Leaves the ground: the arc's two endpoints and the height it will come back to.
     *
     * <p>Both endpoints are read <em>now</em> rather than tracked while flying: a zombie that walks
     * on during the second the leap takes must not drag the landing point with it, or the squash
     * would land on grass the player never saw it aim at.
     */
    private void beginLeap(PlantEntity plant, LevelAccess level) {
        leapFromX = plant.cellX();
        leapBaseHeight = plant.height();
        float targetX = targetX(plant, level);
        // The side it leapt from decides which edge of the target it lands on, so the two sprites
        // overlap instead of one covering the other (see `land`).
        boolean fromTheLeft = leapFromX <= targetX;
        leapToX = targetX + (fromTheLeft ? -LANDING_OFFSET : LANDING_OFFSET);
    }

    /** Where the zombie it committed to is standing, or the plant's own column when it is gone. */
    private float targetX(PlantEntity plant, LevelAccess level) {
        // Looked up by id rather than remembered: the coordinates of a body that has since been
        // killed are not a place to land.
        ZombieEntity target = zombieById(plant, level);
        return target == null ? plant.cellX() : target.cellX();
    }

    /**
     * The zombie this squash committed to, or {@code null} when it is no longer there.
     *
     * <p>Looked up by id in the plant's own row rather than held as a reference: in the second
     * between noticing and landing something else may have killed it - a pea, another squash, a
     * mower - and a squash that landed on a corpse anyway would be a card spent on nothing. It
     * still spends the plant (it jumped), and does not pretend to have hit anything.
     */
    private ZombieEntity zombieById(PlantEntity plant, LevelAccess level) {
        for (ZombieEntity zombie : level.enemiesInRow(plant.gridY(), plant.team())) {
            if (zombie.id() == targetId && !zombie.isRemoved()) {
                return zombie;
            }
        }
        return null;
    }

    /**
     * One tick of the leap: sideways towards the target, and up in an arc.
     *
     * <p>Height is the entity's own field, so the client lifts the art with no new packet - and
     * the arc ends exactly on the landing tick, which is when the strike lands and the squash
     * comes back down to its resting height (a flower pot's lift included).
     */
    private void advanceLeap(PlantEntity plant) {
        float progress = 1F - fuseLeft / (float) fuseTicks;
        float lifted = Math.max(0F, (progress - JUMP_LIFT_START) / (1F - JUMP_LIFT_START));
        plant.setCellX(leapFromX + (leapToX - leapFromX) * lifted);
        plant.setHeight(leapBaseHeight
                + JUMP_HEIGHT * (float) Math.sin(lifted * Math.PI / 2D));
    }

    /**
     * Comes down on the cell it leapt at, and flattens everything standing in it.
     *
     * <p><b>The whole cell, not the one zombie it aimed at.</b> A squash is a very large vegetable
     * landing on one square of lawn: the original flattens every zombie in the cell it lands in, and
     * anything that walked in beside the target by that tick is under it. This used to damage only
     * the entity it had committed to, so a squash that came down between two zombies killed one and
     * left the other chewing - the reported "倭瓜的伤害应该是针对整个格子的僵尸都有压扁的伤害".
     * The target is what the leap <em>aims</em> at; the cell is what it <em>hits</em>.
     *
     * <p>Which cell is the landing point's own, i.e. the cell the arc already carried the plant to
     * (see {@link #advanceLeap}), so "what it is drawn lying on" and "what takes the damage" are the
     * same fact by construction rather than two roundings that have to agree.
     *
     * <p>The targeted zombie is deliberately no longer looked up: a corpse that died on the way down
     * changes nothing, because the damage was never about that one body.
     */
    private void land(PlantEntity plant, LevelAccess level) {
        // Down where the arc put it: the leap already carried the plant to the target's cell
        // (see `advanceLeap`), so there is no teleport here - that was what read as "只是平移了".
        plant.setCellX(leapToX);
        plant.setHeight(leapBaseHeight);
        int cell = Math.round(plant.cellX() - 0.5F);
        for (ZombieEntity zombie : level.enemiesInRow(plant.gridY(), plant.team())) {
            if (zombie.isRemoved() || Math.round(zombie.cellX() - 0.5F) != cell) {
                continue;
            }
            // `pvzce:crush` by default: armour does not save the zombie, and nothing is burned.
            zombie.damage(damage, ZombieEntity.damageType(damageType), level);
        }
        level.emitEffect("", plant.cellX(), plant.cellY(),
                plant.def().sounds().explode().orElse(PvzceSounds.EFFECT_BONK));
        plant.setState(EntityAnimations.EXPLODE);
        lingerLeft = LINGER_TICKS;
    }

    /**
     * Invulnerable from the moment it notices something, not from the moment it lands.
     *
     * <p>Reported as "倭瓜不应该被啃掉……可啃，但是激活时无敌": once a squash has committed it is in the
     * air, and a zombie must not be able to cancel the leap by chewing on the square it left. The
     * fuse is a second and a quarter, and a zombie standing on it bites it down in about a second -
     * so before this the plant was regularly eaten mid-jump and spent its card on nothing.
     *
     * <p>Before it notices anything it is an ordinary plant and is eaten like one: {@code idle} is
     * the state whose art is a squash sitting on the lawn, and that is the squash a zombie is
     * allowed to take away.
     */
    @Override
    public boolean invulnerable(PlantEntity plant) {
        return lookLeft > 0 || fuseLeft > 0 || lingerLeft > 0;
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
        tag.putFloat("leapFrom", leapFromX);
        tag.putFloat("leapTo", leapToX);
        tag.putFloat("leapBase", leapBaseHeight);
    }

    @Override
    public void load(CompoundTag tag) {
        fuseLeft = Math.max(0, tag.getInt("fuse"));
        lookLeft = Math.max(0, tag.getInt("look"));
        targetOnTheLeft = tag.getInt("lookLeft") != 0;
        targetId = tag.contains("target") ? tag.getInt("target") : -1;
        lingerLeft = Math.max(0, tag.getInt("linger"));
        leapFromX = tag.getFloat("leapFrom");
        leapToX = tag.getFloat("leapTo");
        leapBaseHeight = tag.getFloat("leapBase");
    }
}
