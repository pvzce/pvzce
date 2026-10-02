package com.pvzce.common.capability.zombie;

import com.mojang.serialization.MapCodec;
import com.pvzce.api.content.capability.ZombieCapability;
import com.pvzce.api.entity.LevelAccess;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.server.entity.PlantEntity;
import com.pvzce.server.entity.ZombieEntity;

/** The magnetic ladder is also its shield; placing it permanently opens that barricade. */
public final class LadderCapability implements ZombieCapability {
    public static final MapCodec<LadderCapability> CODEC = MapCodec.unit(LadderCapability::new);
    private int placingTicks;
    @Override public ZombieCapability instantiate() { return new LadderCapability(); }
    @Override public String walkState(ZombieEntity zombie) { return carrying(zombie) ? "ladder_walk" : "walk"; }
    @Override public String eatState(ZombieEntity zombie) { return carrying(zombie) ? "ladder_eat" : "eat"; }
    private boolean carrying(ZombieEntity zombie) {
        ArmorCapability armor = zombie.capability(ArmorCapability.class);
        return armor != null && armor.wearing(PvzceIds.id("ladder"));
    }
    @Override public boolean tickMovement(ZombieEntity zombie, LevelAccess level) {
        if (!carrying(zombie) || zombie.isImmobilized()) return false;
        PlantEntity plant = level.plantAt(zombie.gridX(), zombie.gridY());
        if (plant == null || plant.laddered() || !java.util.Set.of("wall_nut", "tall_nut", "pumpkin")
                .contains(plant.definitionId().path())) { placingTicks = 0; return false; }
        zombie.setAnimation("place_ladder");
        if (++placingTicks >= com.pvzce.common.PvzceConstants.LADDER_PLACE_TICKS) {
            plant.setLaddered(true);
            zombie.removeMagneticItem(level);
            placingTicks = 0;
        }
        return true;
    }
    @Override public void save(CompoundTag tag) { tag.putInt("placing", placingTicks); }
    @Override public void load(CompoundTag tag) { placingTicks = Math.max(0, tag.getInt("placing")); }
}
