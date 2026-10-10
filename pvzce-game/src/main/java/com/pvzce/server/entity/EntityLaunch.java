package com.pvzce.server.entity;

import com.pvzce.common.PvzceConstants;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.server.level.LevelServer;
import java.util.List;

/** Extra launch velocity for a real entity, applied alongside its native behaviour. */
public final class EntityLaunch {
    private final float velocityX;
    private final float velocityY;
    private final int damage;
    private float distanceLeft;

    public EntityLaunch(float speed, float vectorX, float vectorY, int damage, float distance) {
        float length = (float) Math.hypot(vectorX, vectorY);
        this.velocityX = length == 0F ? speed : speed * vectorX / length;
        this.velocityY = length == 0F ? 0F : speed * vectorY / length;
        this.damage = Math.max(0, damage);
        this.distanceLeft = distance;
    }

    /** Returns false once the payload lands on its first enemy and resumes native behaviour. */
    public boolean tick(PvzceEntity entity, LevelServer level) {
        float beforeX = entity.cellX(), beforeY = entity.cellY();
        float dx = velocityX / PvzceConstants.TICKS_PER_SECOND;
        float dy = velocityY / PvzceConstants.TICKS_PER_SECOND;
        entity.setCellX(beforeX + dx);
        entity.setCellY(beforeY + dy);
        distanceLeft -= (float) Math.hypot(dx, dy);
        // Swept contact prevents a fast entity tunnelling past a zombie between two ticks.
        float segmentSquared = dx * dx + dy * dy;
        ZombieEntity hit = null;
        float first = Float.POSITIVE_INFINITY;
        for (ZombieEntity enemy : entity instanceof ProjectileEntity
                ? List.<ZombieEntity>of() : level.enemiesOf(entity.team())) {
            if (!enemy.surfaceId().equals(entity.surfaceId()) || Math.abs(enemy.height() - entity.height()) > 1F)
                continue;
            float t = segmentSquared == 0F ? 0F : Math.max(0F, Math.min(1F,
                    ((enemy.cellX() - beforeX) * dx + (enemy.cellY() - beforeY) * dy) / segmentSquared));
            if (Math.hypot(enemy.cellX() - beforeX - t * dx, enemy.cellY() - beforeY - t * dy)
                    <= PvzceConstants.RANDOM_LAUNCH_HIT_RADIUS && t < first) {
                hit = enemy;
                first = t;
            }
        }
        if (hit != null) {
            hit.damage(damage, ZombieEntity.damageType(PvzceIds.DAMAGE_PROJECTILE), level);
            return false;
        }
        if (distanceLeft <= 0F || entity.cellX() < -1F || entity.cellX() > level.width() + 1F
                || entity.cellY() < 0F || entity.cellY() > level.height()) {
            entity.remove();
            return false;
        }
        return true;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putFloat("VX", velocityX);
        tag.putFloat("VY", velocityY);
        tag.putFloat("Left", distanceLeft);
        tag.putInt("Damage", damage);
        return tag;
    }

    public static EntityLaunch load(CompoundTag tag) {
        return new EntityLaunch((float) Math.hypot(tag.getFloat("VX"), tag.getFloat("VY")),
                tag.getFloat("VX"), tag.getFloat("VY"), tag.getInt("Damage"), tag.getFloat("Left"));
    }
}
