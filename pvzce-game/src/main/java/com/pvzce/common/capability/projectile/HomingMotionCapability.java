package com.pvzce.common.capability.projectile;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.content.capability.ProjectileCapability;
import com.pvzce.api.entity.EntityLayers;
import com.pvzce.api.entity.LevelAccess;
import com.pvzce.common.PvzceConstants;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.server.Team;
import com.pvzce.server.entity.ProjectileEntity;
import com.pvzce.server.entity.ZombieEntity;

/** A spike leaves its plant, turns towards a live target and can acquire another after a kill. */
public final class HomingMotionCapability implements ProjectileCapability {
    private final float speed;
    private float heading;
    private boolean launched;

    public HomingMotionCapability(float speed) { this.speed = speed; }

    public static final MapCodec<HomingMotionCapability> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.FLOAT.optionalFieldOf("speed", 6F).forGetter(c -> c.speed)
    ).apply(i, HomingMotionCapability::new));

    @Override public ProjectileCapability instantiate() { return new HomingMotionCapability(speed); }

    /** Shared by firing and flight: balloons first, then the nearest reachable body, including behind. */
    public static ZombieEntity target(LevelAccess level, Team team, float x, float y) {
        return level.enemiesOf(team).stream().filter(HomingMotionCapability::reachable)
                .min(java.util.Comparator.comparingInt((ZombieEntity z) -> z.layer() == EntityLayers.AIR ? 0 : 1)
                        .thenComparingDouble(z -> Math.hypot(z.cellX() - x, z.cellY() - y))
                        .thenComparingInt(ZombieEntity::id)).orElse(null);
    }

    private static boolean reachable(ZombieEntity zombie) {
        return zombie.isAlive() && zombie.canBeHitByArc()
                && (zombie.canBeHitByGround() || zombie.layer() == EntityLayers.AIR);
    }

    @Override public boolean move(ProjectileEntity projectile, LevelAccess level) {
        ZombieEntity target = level.enemiesOf(projectile.team()).stream()
                .filter(z -> z.id() == projectile.targetId() && reachable(z)).findFirst().orElse(null);
        if (target == null) target = target(level, projectile.team(), projectile.cellX(), projectile.cellY());
        if (!launched) {
            // Launch above the muzzle before turning; spikes never appear in their target's row.
            heading = (float) Math.PI / 3F;
            launched = true;
        }
        if (target != null) {
            projectile.retarget(target);
            float wanted = (float) Math.atan2(target.cellY() - projectile.cellY(), target.cellX() - projectile.cellX());
            float turn = (float) Math.atan2(Math.sin(wanted - heading), Math.cos(wanted - heading));
            heading += Math.max(-PvzceConstants.CATTAIL_TURN_PER_TICK,
                    Math.min(PvzceConstants.CATTAIL_TURN_PER_TICK, turn));
            float wantedHeight = target.height() + PvzceConstants.CATTAIL_HIT_HEIGHT;
            projectile.setHeight(projectile.height() + Math.max(-speed / PvzceConstants.TICKS_PER_SECOND,
                    Math.min(speed / PvzceConstants.TICKS_PER_SECOND, wantedHeight - projectile.height())));
        }
        float step = speed / PvzceConstants.TICKS_PER_SECOND;
        projectile.setCellX(projectile.cellX() + (float) Math.cos(heading) * step);
        projectile.setCellY(projectile.cellY() + (float) Math.sin(heading) * step);
        return true;
    }

    @Override public void save(CompoundTag tag) {
        tag.putFloat("heading", heading);
        tag.putInt("launched", launched ? 1 : 0);
    }
    @Override public void load(CompoundTag tag) {
        heading = tag.getFloat("heading");
        launched = tag.getInt("launched") != 0;
    }
}
