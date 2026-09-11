package com.pvzce.common.capability.zombie;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.content.capability.ZombieCapability;
import com.pvzce.api.entity.EntityAnimations;
import com.pvzce.api.entity.EntityLayers;
import com.pvzce.api.entity.LevelAccess;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceConstants;
import com.pvzce.common.PvzceSounds;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.server.entity.ZombieEntity;

import java.util.Optional;

/**
 * Digger: burrows under the lawn, tunnels to the far side of the lawn, surfaces
 * facing right and then walks back through the plants.
 *
 * <p>The whole state machine (buried / surfacing / walking right / turning round)
 * lives here; {@code ZombieEntity} only exposes the movement primitives.
 */
public final class DigCapability implements ZombieCapability {
    public static final float DEFAULT_SPEED = 1.5F;
    public static final int DEFAULT_EMERGE_TICKS = 60;
    /** The zombie digs as soon as it is this many cells from the right edge. */
    public static final float DIG_TRIGGER_MARGIN = 2F;

    private final float digSpeed;
    private final int emergeTicks;
    private final Optional<Identifier> sound;

    private boolean underground;
    private boolean movingRight;
    private int emergeCooldown;

    public DigCapability(float digSpeed, int emergeTicks, Optional<Identifier> sound) {
        this.digSpeed = digSpeed;
        this.emergeTicks = Math.max(0, emergeTicks);
        this.sound = sound;
    }

    public static final MapCodec<DigCapability> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.FLOAT.optionalFieldOf("speed", DEFAULT_SPEED).forGetter(DigCapability::digSpeed),
            Codec.INT.optionalFieldOf("emerge_ticks", DEFAULT_EMERGE_TICKS).forGetter(DigCapability::emergeTicks),
            Identifier.CODEC.optionalFieldOf("sound").forGetter(DigCapability::sound)
    ).apply(i, DigCapability::new));

    public float digSpeed() {
        return digSpeed;
    }

    public int emergeTicks() {
        return emergeTicks;
    }

    public Optional<Identifier> sound() {
        return sound;
    }

    public boolean isUnderground() {
        return underground;
    }

    @Override
    public ZombieCapability instantiate() {
        return new DigCapability(digSpeed, emergeTicks, sound);
    }

    @Override
    public boolean tickMovement(ZombieEntity zombie, LevelAccess level) {
        float perTick = digSpeed / PvzceConstants.TICKS_PER_SECOND;

        if (emergeCooldown > 0) {
            emergeCooldown--;
            zombie.setAnimation(EntityAnimations.DIG_EXIT);
            return true;
        }

        if (!underground) {
            if (movingRight) {
                // Surfaced facing right: walk back across the lawn until the edge.
                if (zombie.cellX() >= level.width() - 0.5F) {
                    movingRight = false;
                    return false;
                }
                zombie.setAnimation(EntityAnimations.WALK);
                zombie.setCellX(zombie.cellX() + zombie.moveSpeed(level) / PvzceConstants.TICKS_PER_SECOND);
                return true;
            }
            if (zombie.cellX() > level.width() - DIG_TRIGGER_MARGIN) {
                return false;
            }
            underground = true;
            zombie.setAnimation(EntityAnimations.DIG);
            level.emitEffect("", zombie.cellX(), zombie.cellY(),
                    sound.orElseGet(() -> zombie.def().sounds().special().orElse(PvzceSounds.ZOMBIE_DIGGER)));
            return true;
        }

        if (zombie.cellX() > 0.5F) {
            zombie.setCellX(zombie.cellX() - perTick);
            zombie.setAnimation(EntityAnimations.DIG);
            return true;
        }
        // Reached the far side: surface facing right.
        underground = false;
        movingRight = true;
        emergeCooldown = emergeTicks;
        zombie.setAnimation(EntityAnimations.DIG_EXIT);
        level.emitEffect("", zombie.cellX(), zombie.cellY(), PvzceSounds.EFFECT_DIRT_RISE);
        return true;
    }

    @Override
    public int layerOverride(ZombieEntity zombie) {
        return underground ? EntityLayers.UNDERGROUND : Integer.MIN_VALUE;
    }

    @Override
    public boolean canBeHitByGround(ZombieEntity zombie) {
        return !underground;
    }

    @Override
    public void save(CompoundTag tag) {
        tag.putInt("underground", underground ? 1 : 0);
        tag.putInt("movingRight", movingRight ? 1 : 0);
        tag.putInt("emergeCooldown", emergeCooldown);
    }

    @Override
    public void load(CompoundTag tag) {
        underground = tag.getInt("underground") != 0;
        movingRight = tag.getInt("movingRight") != 0;
        emergeCooldown = tag.getInt("emergeCooldown");
    }
}
