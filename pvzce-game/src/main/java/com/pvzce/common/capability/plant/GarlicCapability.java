package com.pvzce.common.capability.plant;

import com.mojang.serialization.MapCodec;
import com.pvzce.api.content.capability.PlantCapability;
import com.pvzce.api.entity.LevelAccess;
import com.pvzce.server.entity.PlantEntity;
import com.pvzce.server.entity.ZombieEntity;

/** A bite still hurts the garlic, but sends the walker into an adjacent lane. */
public final class GarlicCapability implements PlantCapability {
    public static final MapCodec<GarlicCapability> CODEC = MapCodec.unit(GarlicCapability::new);

    @Override
    public boolean onBittenBy(PlantEntity plant, ZombieEntity zombie, LevelAccess level) {
        int row = zombie.gridY();
        if (level.height() < 2) return false;
        int next = row == 0 ? 1 : row == level.height() - 1 ? row - 1
                : row + (level.random().nextBoolean() ? 1 : -1);
        zombie.setCellY(next + 0.5F);
        return false;
    }
}
