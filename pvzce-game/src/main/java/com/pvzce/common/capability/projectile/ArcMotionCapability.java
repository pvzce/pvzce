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

    private final float speedCellsPerSecond;
    private final float gravityCellsPerSecondSquared;

    private float vy;
    private boolean launched;
    /**
     * Which way the shot travels: from the muzzle towards what it was aimed at.
     *
     * <p>+1 until a launch says otherwise, so a shot that never solves an arc (a straight-flying
     * projectile) keeps going the way it always did. It is solved at launch rather than assumed
     * because the cob cannon can be aimed at any cell on the lawn - including one <em>behind</em>
     * it - and a lobber whose target has walked past it has the same problem. Assuming "rightwards"
     * made both of those shots pop straight up and come down on the plant that fired them.
     */
    private float direction = 1F;
    private float horizontalStep;

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
        float delta = targetX - startX;
        this.direction = delta < 0F ? -1F : 1F;
        // The absolute distance: a shot aimed behind the plant is the same flight, mirrored, and
        // a signed one would give it a negative time of flight (and so a downward launch).
        float distance = Math.max(0.5F, Math.abs(delta));
        float time = Math.max(PvzceConstants.LOB_MIN_FLIGHT_TICKS,
                Math.min(PvzceConstants.LOB_MAX_FLIGHT_TICKS,
                        distance / Math.max(0.0001F, speedPerTick())));
        horizontalStep = Math.abs(delta) / time;
        // Semi-implicit integration subtracts gravity before moving: include that first step.
        this.vy = (targetHeight - startHeight) / time + 0.5F * gravityPerTick() * (time + 1F);
        this.launched = true;
    }

    /** Which way the shot travels: -1 or +1. See {@link #direction}. */
    public float direction() {
        return direction;
    }

    public boolean launched() {
        return launched;
    }

    @Override
    public boolean move(ProjectileEntity projectile, LevelAccess level) {
        projectile.setCellX(projectile.cellX() + direction * (launched ? horizontalStep : speedPerTick()));
        vy -= gravityPerTick();
        projectile.setHeight(Math.max(0F, projectile.height() + vy));
        return true;
    }

    @Override
    public void save(CompoundTag tag) {
        tag.putFloat("vy", vy);
        tag.putInt("launched", launched ? 1 : 0);
        tag.putFloat("direction", direction);
        tag.putFloat("horizontalStep", horizontalStep);
    }

    @Override
    public void load(CompoundTag tag) {
        vy = tag.getFloat("vy");
        launched = tag.getInt("launched") != 0;
        // A save written before the direction existed was written by a build where every arc went
        // right, so +1 is not a guess about the old value - it *is* the old value.
        float saved = tag.getFloat("direction");
        direction = saved < 0F ? -1F : 1F;
        horizontalStep = tag.contains("horizontalStep") ? tag.getFloat("horizontalStep") : speedPerTick();
    }
}
