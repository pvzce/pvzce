package com.pvzce.common.capability.zombie;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.content.capability.ZombieCapability;
import com.pvzce.api.entity.EntityAnimations;
import com.pvzce.api.entity.EntityLayers;
import com.pvzce.api.entity.LevelAccess;
import com.pvzce.common.PvzceConstants;
import com.pvzce.common.PvzceSounds;
import com.pvzce.server.entity.ZombieEntity;

/**
 * Balloon-style flier: ignores plants entirely, flies at the air layer and drops
 * to the ground the first time it is hit (see {@code ZombieEntity.damage}).
 */
public final class FlyCapability implements ZombieCapability {
    public static final float DEFAULT_HEIGHT = 1F;

    private final float height;

    public FlyCapability(float height) {
        this.height = height;
    }

    public static final MapCodec<FlyCapability> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.FLOAT.optionalFieldOf("height", DEFAULT_HEIGHT).forGetter(FlyCapability::height)
    ).apply(i, FlyCapability::new));

    public float height() {
        return height;
    }

    @Override
    public ZombieCapability instantiate() {
        return this;
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
        level.emitEffect("", zombie.cellX(), zombie.cellY(),
                zombie.def().sounds().special().orElse(PvzceSounds.ZOMBIE_BALLOON_POP));
    }
}
