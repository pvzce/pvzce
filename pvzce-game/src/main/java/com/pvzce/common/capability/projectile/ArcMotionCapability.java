package com.pvzce.common.capability.projectile;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.content.capability.ProjectileCapability;
import com.pvzce.api.entity.LevelAccess;
import com.pvzce.common.PvzceConstants;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.server.entity.ProjectileEntity;

/**
 * Ballistic arc motion. The launcher solves the initial vertical velocity for a
 * given target; the capability then integrates gravity and reports when the shot
 * has come back down to ground level.
 */
public final class ArcMotionCapability implements ProjectileCapability {
    public static final float DEFAULT_SPEED = 2.2F;
    public static final float DEFAULT_GRAVITY = 9.0F;
    /** The shot is considered to have landed at or below this height. */
    public static final float GROUND_EPSILON = 0.15F;

    private final float speedCellsPerSecond;
    private final float gravityCellsPerSecondSquared;

    private float vy;
    private boolean launched;

    public ArcMotionCapability(float speedCellsPerSecond, float gravityCellsPerSecondSquared) {
        this.speedCellsPerSecond = speedCellsPerSecond;
        this.gravityCellsPerSecondSquared = gravityCellsPerSecondSquared;
    }

    public static final MapCodec<ArcMotionCapability> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.FLOAT.optionalFieldOf("speed", DEFAULT_SPEED)
                    .forGetter(ArcMotionCapability::speedCellsPerSecond),
            Codec.FLOAT.optionalFieldOf("gravity", DEFAULT_GRAVITY)
                    .forGetter(ArcMotionCapability::gravityCellsPerSecondSquared)
    ).apply(i, ArcMotionCapability::new));

    public float speedCellsPerSecond() {
        return speedCellsPerSecond;
    }

    public float gravityCellsPerSecondSquared() {
        return gravityCellsPerSecondSquared;
    }

    public float speedPerTick() {
        return speedCellsPerSecond / PvzceConstants.TICKS_PER_SECOND;
    }

    private float gravityPerTick() {
        return gravityCellsPerSecondSquared
                / (PvzceConstants.TICKS_PER_SECOND * (float) PvzceConstants.TICKS_PER_SECOND);
    }

    @Override
    public ProjectileCapability instantiate() {
        return new ArcMotionCapability(speedCellsPerSecond, gravityCellsPerSecondSquared);
    }

    /**
     * Solves the launch velocity once the target is known. Called by the level
     * right after the projectile is created.
     */
    public void launch(float startX, float startHeight, float targetX, float targetHeight) {
        float distance = Math.max(0.5F, targetX - startX);
        float time = distance / Math.max(0.0001F, speedPerTick());
        this.vy = (targetHeight - startHeight) / time + 0.5F * gravityPerTick() * time;
        this.launched = true;
    }

    public boolean launched() {
        return launched;
    }

    @Override
    public boolean move(ProjectileEntity projectile, LevelAccess level) {
        projectile.setCellX(projectile.cellX() + speedPerTick());
        vy -= gravityPerTick();
        projectile.setHeight(Math.max(0F, projectile.height() + vy));
        return true;
    }

    @Override
    public void save(CompoundTag tag) {
        tag.putFloat("vy", vy);
        tag.putInt("launched", launched ? 1 : 0);
    }

    @Override
    public void load(CompoundTag tag) {
        vy = tag.getFloat("vy");
        launched = tag.getInt("launched") != 0;
    }
}
