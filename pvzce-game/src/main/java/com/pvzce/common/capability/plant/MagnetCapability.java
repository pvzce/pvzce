package com.pvzce.common.capability.plant;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.content.capability.PlantCapability;
import com.pvzce.api.entity.EntityAnimations;
import com.pvzce.api.entity.LevelAccess;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.common.network.packet.MagnetItemS2C;
import com.pvzce.server.entity.PlantEntity;
import com.pvzce.server.entity.ZombieEntity;

import java.util.Optional;

/**
 * Takes one magnetic armour piece or carried item from a nearby zombie.
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
 * is re-implemented here. Carried equipment has the same hook on its own capability.
 *
 * <p>A zombie with nothing to take is not a target, so a magnet-shroom on a lawn of bare zombies
 * simply waits: it is a counter to armour rather than a damage plant, and pretending otherwise
 * would make it a strictly better puff-shroom.
 */
public final class MagnetCapability implements PlantCapability {
    /** How far it reaches, in cells. Longer than a shooter's lane: it is a utility, not a gun. */
    public static final float DEFAULT_RANGE = 4.5F;
    /** How often it can pull, in ticks. */
    public static final int DEFAULT_INTERVAL_TICKS = com.pvzce.common.PvzceConstants.MAGNET_RECOVERY_TICKS;

    private final float range;
    private final int intervalTicks;
    private final Optional<Identifier> sound;

    private int cooldown;
    private boolean pulling;
    private String heldItem = "";
    private float itemX, itemY;
    private boolean resync;

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
            int elapsed = intervalTicks - cooldown;
            plant.setState(elapsed < com.pvzce.common.PvzceConstants.MAGNET_PULL_TICKS
                    ? EntityAnimations.SHOOT : "magnet_hold");
            if (resync) { sendItem(plant, level, elapsed); resync = false; }
            return;
        }
        ZombieEntity target = nearestArmoured(plant, level);
        if (target == null) {
            PlantEntity ladder = nearestLadder(plant, level);
            if (ladder != null) {
                ladder.setLaddered(false);
                heldItem = com.pvzce.common.PvzceIds.id("ladder").toString();
                itemX = ladder.cellX(); itemY = ladder.cellY();
                sendItem(plant, level, 0);
                pulling = true;
                cooldown = intervalTicks;
                plant.setState(EntityAnimations.SHOOT);
                level.emitEffect("", plant.cellX(), plant.cellY(),
                        sound.orElseGet(() -> plant.def().sounds().shoot().orElse(null)));
                return;
            }
            // Nothing wearing anything within reach. The clock is *not* reset, so the pull happens
            // on the tick equipment walks into range rather than another recovery later.
            plant.setState(EntityAnimations.IDLE);
            return;
        }
        var item = target.magneticItem();
        if (target.removeMagneticItem(level)) {
            heldItem = item.toString(); itemX = target.cellX(); itemY = target.cellY();
            sendItem(plant, level, 0);
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
            if (zombie.isRemoved() || zombie.magneticItem() == null) {
                continue;
            }
            if (Math.abs(zombie.gridY() - plant.gridY()) > 2) continue;
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

    private PlantEntity nearestLadder(PlantEntity plant, LevelAccess level) {
        PlantEntity best = null;
        double distance = range;
        for (int row = Math.max(0, plant.gridY() - 2); row <= Math.min(level.height() - 1, plant.gridY() + 2); row++) {
            for (int col = 0; col < level.width(); col++) {
                for (PlantEntity candidate : level.plantsAt(col, row)) {
                    double d = Math.hypot(candidate.cellX() - plant.cellX(), candidate.cellY() - plant.cellY());
                    if (candidate.laddered() && !candidate.isRemoved() && d <= distance) {
                        best = candidate; distance = d;
                    }
                }
            }
        }
        return best;
    }

    private void sendItem(PlantEntity plant, LevelAccess level, int elapsed) {
        level.emitMagnetItem(plant.id(), Identifier.parse(heldItem), itemX, itemY,
                level.tickCount() - elapsed, com.pvzce.common.PvzceConstants.MAGNET_PULL_TICKS, intervalTicks);
    }

    /** Current held object, retaining its elapsed pull/recovery time on a joining client. */
    public MagnetItemS2C itemSnapshot(PlantEntity plant, int tick) {
        if (cooldown <= 0 || heldItem.isEmpty()) return null;
        return new MagnetItemS2C(plant.id(), heldItem, itemX, itemY,
                tick - (intervalTicks - cooldown),
                com.pvzce.common.PvzceConstants.MAGNET_PULL_TICKS, intervalTicks);
    }

    @Override
    public void save(CompoundTag tag) {
        tag.putInt("cooldown", cooldown);
        tag.putString("heldItem", heldItem);
        tag.putFloat("itemX", itemX); tag.putFloat("itemY", itemY);
    }

    @Override
    public void load(CompoundTag tag) {
        cooldown = Math.max(0, tag.getInt("cooldown"));
        heldItem = tag.getString("heldItem");
        itemX = tag.getFloat("itemX"); itemY = tag.getFloat("itemY");
        resync = cooldown > 0 && !heldItem.isEmpty();
    }
}
