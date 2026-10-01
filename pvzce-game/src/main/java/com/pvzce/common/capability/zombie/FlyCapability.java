package com.pvzce.common.capability.zombie;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.content.capability.ZombieCapability;
import com.pvzce.api.entity.EntityAnimations;
import com.pvzce.api.entity.EntityLayers;
import com.pvzce.api.entity.LevelAccess;
import com.pvzce.common.PvzceConstants;
import com.pvzce.server.entity.ZombieEntity;

/**
 * Balloon-style flier: ignores plants entirely, flies at the air layer and drops
 * to the ground the first time it is hit (see {@code ZombieEntity.damage}).
 */
public final class FlyCapability implements ZombieCapability {
    public static final float DEFAULT_HEIGHT = 1F;

    private final float height;
    private int fallLeft;

    public FlyCapability(float height) {
        this.height = height;
    }

    public static final MapCodec<FlyCapability> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.FLOAT.optionalFieldOf("height", DEFAULT_HEIGHT).forGetter(FlyCapability::height)
    ).apply(i, FlyCapability::new));

    public float height() {
        return height;
    }

    public boolean falling() { return fallLeft > 0; }

    @Override
    public ZombieCapability instantiate() {
        return new FlyCapability(height);
    }

    @Override
    public boolean spawnsAirborne() {
        return true;
    }

    @Override
    public void tick(ZombieEntity zombie, LevelAccess level) {
        if (zombie.isGrounded()) {
            return;
        }
        zombie.setHeight(height);
    }

    @Override
    public boolean tickMovement(ZombieEntity zombie, LevelAccess level) {
        if (zombie.isGrounded()) {
            if (fallLeft > 0) {
                zombie.setAnimation(EntityAnimations.FALL);
                zombie.setHeight(height * --fallLeft / PvzceConstants.BALLOON_FALL_TICKS);
                return true;
            }
            return false;
        }
        zombie.setAnimation(EntityAnimations.FLY);
        zombie.setHeight(height);
        zombie.setCellX(zombie.cellX() - zombie.moveSpeed(level) / PvzceConstants.TICKS_PER_SECOND);
        zombie.checkReachedLeft(level);
        return true;
    }

    @Override
    public int layerOverride(ZombieEntity zombie) {
        return zombie.isGrounded() ? EntityLayers.GROUND : EntityLayers.AIR;
    }

    @Override
    public boolean canBeHitByGround(ZombieEntity zombie) {
        return zombie.isGrounded();
    }

    /** Called by the zombie when a hit pops the balloon. */
    public void pop(ZombieEntity zombie, LevelAccess level) {
        zombie.setGrounded(true);
        fallLeft = PvzceConstants.BALLOON_FALL_TICKS;
    }
    @Override public float speedMultiplier(ZombieEntity zombie) {
        return zombie.isGrounded() ? PvzceConstants.BALLOON_GROUND_SPEED / PvzceConstants.BALLOON_AIR_SPEED : 1F;
    }
    @Override public void save(com.pvzce.common.nbt.CompoundTag tag) { tag.putInt("fallLeft", fallLeft); }
    @Override public void load(com.pvzce.common.nbt.CompoundTag tag) { fallLeft = tag.getInt("fallLeft"); }

}
