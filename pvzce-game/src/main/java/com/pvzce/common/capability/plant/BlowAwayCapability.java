package com.pvzce.common.capability.plant;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.content.capability.PlantCapability;
import com.pvzce.api.entity.EntityAnimations;
import com.pvzce.api.entity.EntityLayers;
import com.pvzce.api.entity.LevelAccess;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceSounds;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.server.entity.PlantEntity;
import com.pvzce.server.entity.ZombieEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Blows everything in the air off the lawn, once (the blover).
 *
 * <p>A one-shot plant: it winds up for a moment, a gust crosses the whole board, and every zombie
 * that was flying is gone. It is the answer to a balloon raid and to nothing else - a blover spent
 * on a lawn with nothing in the air is a card wasted, which is the tension the original has.
 *
 * <h2>What "in the air" means</h2>
 *
 * <p>{@code EntityLayers.AIR}, which is the same predicate the mower uses to decide what it does
 * <em>not</em> run over and the projectile router uses to decide what a ground shot cannot hit.
 * There is one definition of "flying" in this engine and this capability reads it rather than
 * keeping a list of zombie ids.
 *
 * <h2>Where they go</h2>
 *
 * <p>Off the board, through {@code ZombieEntity.blowAway} - the same path a balloon zombie takes
 * when its balloon is shot out, except that this one does not drop it on the lawn first. A blover
 * is not a kill: the zombie leaves, and it does not come back, but nothing about it counts as
 * having been destroyed.
 */
public final class BlowAwayCapability implements PlantCapability {
    /** The wind-up, in ticks. Long enough to see it coming and short enough to be a reaction. */
    public static final int DEFAULT_FUSE_TICKS = 60;

    private final int fuseTicks;
    private final Optional<Identifier> sound;

    /** Ticks left of the wind-up, or 0 before it has been triggered. */
    private int fuseLeft;
    private boolean blown;

    public BlowAwayCapability(int fuseTicks, Optional<Identifier> sound) {
        this.fuseTicks = Math.max(1, fuseTicks);
        this.sound = sound;
    }

    public static final MapCodec<BlowAwayCapability> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.INT.optionalFieldOf("fuse_ticks", DEFAULT_FUSE_TICKS)
                    .forGetter(BlowAwayCapability::fuseTicks),
            Identifier.CODEC.optionalFieldOf("sound").forGetter(BlowAwayCapability::sound)
    ).apply(i, BlowAwayCapability::new));

    public int fuseTicks() {
        return fuseTicks;
    }

    public Optional<Identifier> sound() {
        return sound;
    }

    @Override
    public PlantCapability instantiate() {
        return new BlowAwayCapability(fuseTicks, sound);
    }

    @Override
    public void tick(PlantEntity plant, LevelAccess level) {
        if (blown) {
            return;
        }
        if (fuseLeft == 0) {
            // Planted and immediately winding up. The clip is the same one the gust plays, because
            // the blover's art has one gesture - it starts turning the moment it is in the ground.
            fuseLeft = fuseTicks;
            plant.setState(EntityAnimations.SHOOT);
            return;
        }
        fuseLeft--;
        if (fuseLeft > 0) {
            plant.setState(EntityAnimations.SHOOT);
            return;
        }
        blow(plant, level);
    }

    private void blow(PlantEntity plant, LevelAccess level) {
        blown = true;
        List<ZombieEntity> flying = new ArrayList<>();
        for (ZombieEntity zombie : level.enemiesOf(plant.team())) {
            if (!zombie.isRemoved() && zombie.layer() == EntityLayers.AIR) {
                flying.add(zombie);
            }
        }
        for (ZombieEntity zombie : flying) {
            zombie.blowAway(plant.cellX() < 0.5F ? 1F : -1F);
        }
        level.emitEffect("", plant.cellX(), plant.cellY(),
                sound.orElseGet(() -> plant.def().sounds().shoot()
                        .orElse(PvzceSounds.PLANT_SHOOT_PEA)));
        // One use and it is gone: a blover that stayed would be a permanent "nothing may fly"
        // marker, which is a different plant from the one the original has.
        plant.remove();
    }

    @Override
    public void save(CompoundTag tag) {
        tag.putInt("fuse", fuseLeft);
        tag.putByte("blown", (byte) (blown ? 1 : 0));
    }

    @Override
    public void load(CompoundTag tag) {
        fuseLeft = Math.max(0, tag.getInt("fuse"));
        blown = tag.getInt("blown") != 0;
    }
}
