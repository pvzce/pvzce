package com.pvzce.common.capability.plant;

import com.mojang.serialization.MapCodec;
import com.pvzce.api.content.capability.PlantCapability;
import com.pvzce.api.entity.LevelAccess;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.server.Team;
import com.pvzce.server.entity.PlantEntity;

/** Protects the nine cells around a living umbrella, including under a pumpkin. */
public final class UmbrellaLeafCapability implements PlantCapability {
    public static final MapCodec<UmbrellaLeafCapability> CODEC = MapCodec.unit(UmbrellaLeafCapability::new);
    private int blockTicks;

    @Override public PlantCapability instantiate() { return new UmbrellaLeafCapability(); }
    @Override public void tick(PlantEntity plant, LevelAccess level) {
        plant.setState(blockTicks > 0 ? "umbrella_block" : "idle");
        if (blockTicks > 0) blockTicks--;
    }

    public static boolean block(LevelAccess level, int x, int y, Team defendedTeam) {
        for (int row = Math.max(0, y - 1); row <= Math.min(level.height() - 1, y + 1); row++) {
            for (int col = Math.max(0, x - 1); col <= Math.min(level.width() - 1, x + 1); col++) {
                for (PlantEntity plant : level.plantsAt(col, row)) {
                    UmbrellaLeafCapability umbrella = plant.capability(UmbrellaLeafCapability.class);
                    if (!plant.isRemoved() && (defendedTeam == null || plant.team() == defendedTeam) && umbrella != null) {
                        umbrella.blockTicks = com.pvzce.common.PvzceConstants.UMBRELLA_BLOCK_TICKS;
                        plant.setState("umbrella_block");
                        return true;
                    }
                }
            }
        }
        return false;
    }
    @Override public void save(CompoundTag tag) { tag.putInt("block", blockTicks); }
    @Override public void load(CompoundTag tag) { blockTicks = Math.max(0, tag.getInt("block")); }
}
