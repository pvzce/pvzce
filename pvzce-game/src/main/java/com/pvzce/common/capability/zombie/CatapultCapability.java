package com.pvzce.common.capability.zombie;

import com.mojang.serialization.MapCodec;
import com.pvzce.api.content.ProjectileRef;
import com.pvzce.api.content.capability.ZombieCapability;
import com.pvzce.api.entity.LevelAccess;
import com.pvzce.common.PvzceConstants;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.server.entity.PlantEntity;
import com.pvzce.server.entity.ZombieEntity;

/** Stops at the right edge and lobs twenty balls at the leftmost plant, then drives on. */
public final class CatapultCapability implements ZombieCapability {
    public static final MapCodec<CatapultCapability> CODEC = MapCodec.unit(CatapultCapability::new);
    private int ammo = PvzceConstants.CATAPULT_AMMO;
    private int cooldown;
    private int shootTicks;
    @Override public ZombieCapability instantiate() { return new CatapultCapability(); }
    @Override public boolean tickMovement(ZombieEntity zombie, LevelAccess level) {
        if (zombie.isImmobilized()) return true;
        if (zombie.cellX() > PvzceConstants.CATAPULT_STOP_X || ammo == 0) {
            zombie.setAnimation("walk");
            for (PlantEntity plant : level.plantsAt(zombie.gridX(), zombie.gridY(), zombie.surfaceId())) plant.damageFrom(plant.health());
            return false;
        }
        PlantEntity target = null;
        for (int x = 0; x < Math.min(level.width(), zombie.gridX()); x++) {
            PlantEntity candidate = level.plantAt(x, zombie.gridY(), zombie.surfaceId());
            if (candidate != null && !candidate.isRemoved()) { target = candidate; break; }
        }
        if (target == null) return false;
        zombie.setAnimation(shootTicks > 0 ? "shoot" : "idle");
        if (shootTicks > 0) shootTicks--;
        if (--cooldown > 0) return true;
        level.spawnZombieArcProjectile(new ProjectileRef(PvzceIds.id("basketball"),
                PvzceConstants.CATAPULT_BALL_DAMAGE, 1), zombie, target);
        ammo--; cooldown = PvzceConstants.CATAPULT_INTERVAL_TICKS;
        shootTicks = PvzceConstants.CATAPULT_SHOOT_TICKS;
        zombie.setAnimation("shoot");
        return true;
    }
    @Override public void save(CompoundTag tag) { tag.putInt("ammo", ammo); tag.putInt("cooldown", cooldown); tag.putInt("shoot", shootTicks); }
    @Override public void load(CompoundTag tag) { ammo = Math.max(0, tag.getInt("ammo")); cooldown = Math.max(0, tag.getInt("cooldown")); shootTicks = tag.getInt("shoot"); }
}
