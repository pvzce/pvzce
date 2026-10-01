package com.pvzce.common.capability.plant;

import com.mojang.serialization.MapCodec;
import com.pvzce.api.content.ProjectileRef;
import com.pvzce.api.content.capability.PlantCapability;
import com.pvzce.api.entity.EntityAnimations;
import com.pvzce.api.entity.LevelAccess;
import com.pvzce.common.PvzceConstants;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.server.entity.PlantEntity;
import java.util.List;
import java.util.Optional;

/** Raises its arms for balloons, and lowers them before resuming ground fire. */
public final class CactusCapability implements PlantCapability {
    public static final MapCodec<CactusCapability> CODEC = MapCodec.unit(new CactusCapability());
    private final ShooterCapability low = shooter(false);
    private final ShooterCapability high = shooter(true);
    private boolean raised;
    private int transition;

    private static ShooterCapability shooter(boolean air) {
        ProjectileRef shot = new ProjectileRef(PvzceIds.id(air ? "cactus_spike_air" : "cactus_spike"),
                PvzceConstants.CACTUS_DAMAGE, 1, 0, false, 0, 0F, 0, 0, false, 1F, 0F, air ? 1F : 0F);
        return new ShooterCapability(PvzceConstants.CACTUS_SHOT_INTERVAL_TICKS, List.of(shot), Optional.empty(), 0, 0F, false,
                air ? "shoot_high" : EntityAnimations.SHOOT);
    }

    @Override public PlantCapability instantiate() { return new CactusCapability(); }
    @Override public boolean holdsFire(PlantEntity plant) { return true; }
    @Override public boolean hasPendingWork(PlantEntity plant) {
        return low.hasPendingWork(plant) || high.hasPendingWork(plant);
    }
    @Override public void tickPending(PlantEntity plant, LevelAccess level) {
        low.tickPending(plant, level);
        high.tickPending(plant, level);
    }
    @Override public boolean strike(PlantEntity plant, LevelAccess level) {
        return transition == 0 && (raised ? high : low).strike(plant, level);
    }
    @Override public void tick(PlantEntity plant, LevelAccess level) {
        if (transition > 0) {
            tickPending(plant, level);
            transition--;
            plant.setState(raised ? "rise" : "lower");
            return;
        }
        boolean balloon = high.hasTarget(plant, level);
        if (balloon != raised) {
            tickPending(plant, level);
            raised = balloon;
            transition = raised ? PvzceConstants.CACTUS_RISE_TICKS : PvzceConstants.CACTUS_LOWER_TICKS;
            plant.setState(raised ? "rise" : "lower");
            return;
        }
        (raised ? low : high).tickPending(plant, level);
        (raised ? high : low).tick(plant, level);
        if (raised && EntityAnimations.IDLE.equals(plant.animation())) plant.setState("idle_high");
    }
    @Override public void save(CompoundTag tag) {
        tag.putInt("raised", raised ? 1 : 0);
        tag.putInt("transition", transition);
        CompoundTag a = new CompoundTag(), b = new CompoundTag();
        low.save(a); high.save(b); tag.put("low", a); tag.put("high", b);
    }
    @Override public void load(CompoundTag tag) {
        raised = tag.getInt("raised") != 0; transition = tag.getInt("transition");
        low.load(tag.getCompound("low")); high.load(tag.getCompound("high"));
    }
}
