package com.pvzce.common.capability.plant;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.content.capability.PlantCapability;
import com.pvzce.api.entity.EntityLayers;
import com.pvzce.api.entity.LevelAccess;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceParticles;
import com.pvzce.common.PvzceSounds;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.server.entity.PlantEntity;
import com.pvzce.server.entity.ZombieEntity;

import java.util.Optional;

/**
 * Drags one zombie under the surface and drowns it (the tangle kelp).
 *
 * <p>The pool's answer to a zombie that is too big to shoot in time, and the only plant in the
 * game that kills by terrain rather than by damage: what it does is hold the first thing that
 * walks onto it below the waterline. Three consequences, and all three are the original's:
 *
 * <ul>
 *   <li><b>It kills one zombie, and only one.</b> The plant is spent by the grab, so a wave of
 *       six floaties costs six kelp - the reason it is cheap (25 sun) and the reason it is not
 *       a substitute for a whole lane's defence.</li>
 *   <li><b>Armour is irrelevant.</b> A buckethead is dragged under by its feet, exactly like a
 *       bare zombie: the hit lands as {@code pvzce:drag_under}, whose whole meaning is that
 *       armour does not absorb it. Only a body too heavy to pull down is immune, which is what
 *       {@link #ignoreAboveHealth} states - the original's gargantuar walks over the kelp.</li>
 *   <li><b>What it cannot reach is not triggered.</b> A balloon is in the air and a miner is
 *       under the lawn, so neither sets it off: the predicate is the same
 *       {@code canBeHitByGround} / ground-layer pair the mower uses.</li>
 * </ul>
 *
 * <p>The original also refuses the bodies it cannot pull down - a gargantuar walks over a tangle
 * kelp - and that rule has no home here yet, because it cannot be reached: a kelp may only be
 * planted in water, only a swimmer can stand in water, and every swimmer the game has is an
 * ordinary-sized body. When a heavy swimmer exists, "too heavy to drag" belongs here, next to
 * the Ground-layer test.
 *
 * <p>The victim dies <em>when it is grabbed</em> rather than at the end of the animation, and the
 * plant then stays on the field for {@code drag_ticks} with the {@code grab} clip playing - the
 * same "the effect is instant, the art is not" split an explosive plant has, and for the same
 * reason: entity state travels every third tick, and a plant removed in the tick it acted is a
 * plant the client never saw do anything.
 */
public final class DragUnderCapability implements PlantCapability {
    /**
     * The clip the grab plays.
     *
     * <p>Not one of the shared animation states: it belongs to this plant's own file, and it is
     * named against the converter's output ({@code animations/plant/environment/tangle_kelp.json})
     * where the source reanim's {@code anim_grab} mask became this clip.
     */
    public static final String GRAB_STATE = "grab";

    /** How far from the plant's own cell centre a victim is grabbed, in cells. */
    public static final float DEFAULT_RANGE = 0.5F;
    /** How long the drag is drawn for after the victim is already gone. */
    public static final int DEFAULT_DRAG_TICKS = 24;
    private final float range;
    private final int dragTicks;
    private final Optional<Identifier> sound;

    /** Ticks of the drag left; zero while the plant is waiting for something to grab. */
    private int remainingDragTicks;

    public DragUnderCapability(float range, int dragTicks, Optional<Identifier> sound) {
        this.range = Math.max(0F, range);
        this.dragTicks = Math.max(1, dragTicks);
        this.sound = sound;
    }

    public static final MapCodec<DragUnderCapability> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.FLOAT.optionalFieldOf("range", DEFAULT_RANGE).forGetter(DragUnderCapability::range),
            Codec.INT.optionalFieldOf("drag_ticks", DEFAULT_DRAG_TICKS)
                    .forGetter(DragUnderCapability::dragTicks),
            Identifier.CODEC.optionalFieldOf("sound").forGetter(DragUnderCapability::sound)
    ).apply(i, DragUnderCapability::new));

    public float range() {
        return range;
    }

    public int dragTicks() {
        return dragTicks;
    }

    public Optional<Identifier> sound() {
        return sound;
    }

    @Override
    public PlantCapability instantiate() {
        return new DragUnderCapability(range, dragTicks, sound);
    }

    @Override
    public void tick(PlantEntity plant, LevelAccess level) {
        if (remainingDragTicks > 0) {
            plant.setState(GRAB_STATE);
            if (--remainingDragTicks == 0) {
                plant.remove();
            }
            return;
        }
        ZombieEntity victim = firstVictim(plant, level);
        if (victim == null) {
            return;
        }
        grab(plant, level, victim);
    }

    /** The zombie nearest the plant's own cell that this kelp can pull under, or {@code null}. */
    private ZombieEntity firstVictim(PlantEntity plant, LevelAccess level) {
        ZombieEntity best = null;
        float bestDistance = range;
        for (ZombieEntity zombie : level.zombiesInRow(plant.gridY())) {
            if (zombie.isRemoved() || !zombie.canBeHitByGround()) {
                continue;
            }
            // The layer, not a tag: a digger is below the lawn and a flier above it, and neither
            // is standing in the water this plant is rooted in.
            if (zombie.layer() != EntityLayers.GROUND) {
                continue;
            }
            // The victim's front edge, so a zombie whose body is over the kelp is grabbed even
            // though its centre is still a little to the right of it.
            float distance = Math.abs(zombie.cellX() - plant.cellX());
            if (distance <= bestDistance) {
                best = zombie;
                bestDistance = distance;
            }
        }
        return best;
    }

    private void grab(PlantEntity plant, LevelAccess level, ZombieEntity victim) {
        remainingDragTicks = dragTicks;
        plant.setState(GRAB_STATE);
        // Instant, and through the armour: the kelp pulls the body under, it does not chew
        // through a bucket. `zombie.health()` rather than a constant so that whatever the body
        // has left is exactly what it takes - a damage type that ignores armour still has to be
        // worth more than the health bar it is cancelling.
        victim.damage(victim.health(), dragType(), level);
        level.emitEffect(PvzceParticles.POOL_SPLASH.toString(), plant.cellX(), plant.cellY(),
                sound.orElse(PvzceSounds.ZOMBIE_SPLASH));
        level.emitRippleAt(plant.gridX(), plant.gridY(), 1F);
    }

    /** What a drag lands as: armour does not absorb it. */
    private static com.pvzce.api.content.DamageTypeDef dragType() {
        return ZombieEntity.damageType(com.pvzce.common.PvzceIds.DAMAGE_DRAG_UNDER);
    }

    @Override
    public void save(CompoundTag tag) {
        tag.putInt("drag", remainingDragTicks);
    }

    @Override
    public void load(CompoundTag tag) {
        remainingDragTicks = Math.max(0, tag.getInt("drag"));
    }
}
