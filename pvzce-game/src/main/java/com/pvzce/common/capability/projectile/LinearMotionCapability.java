package com.pvzce.common.capability.projectile;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.content.capability.ProjectileCapability;
import com.pvzce.api.entity.LevelAccess;
import com.pvzce.common.PvzceConstants;
import com.pvzce.server.entity.ProjectileEntity;

/** Straight-line motion at a fixed speed; the default for peas and similar shots. */
public final class LinearMotionCapability implements ProjectileCapability {
    public static final float DEFAULT_SPEED = 2.0F;

    private final float speedCellsPerSecond;

    public LinearMotionCapability(float speedCellsPerSecond) {
        this.speedCellsPerSecond = speedCellsPerSecond;
    }

    public static final MapCodec<LinearMotionCapability> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.FLOAT.optionalFieldOf("speed", DEFAULT_SPEED).forGetter(LinearMotionCapability::speedCellsPerSecond)
    ).apply(i, LinearMotionCapability::new));

    public float speedCellsPerSecond() {
        return speedCellsPerSecond;
    }

    /** Cells advanced per tick at the server's 60tps baseline. */
    public float speedPerTick() {
        return speedCellsPerSecond / PvzceConstants.TICKS_PER_SECOND;
    }

    @Override
    public ProjectileCapability instantiate() {
        return this;
    }

    @Override
    public boolean move(ProjectileEntity projectile, LevelAccess level) {
        projectile.setCellX(projectile.cellX() + speedPerTick());
        return true;
    }
}
