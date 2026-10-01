package com.pvzce.common.capability.plant;

import com.mojang.serialization.MapCodec;
import com.pvzce.api.content.capability.PlantCapability;
import com.pvzce.api.entity.LevelAccess;
import com.pvzce.server.entity.PlantEntity;

/** A living carrier: conducts resonance, but never fires or adds another lily's strength. */
public final class EchoConduitCapability implements PlantCapability {
    public static final MapCodec<EchoConduitCapability> CODEC = MapCodec.unit(new EchoConduitCapability());

    @Override
    public PlantCapability instantiate() {
        return new EchoConduitCapability();
    }

    @Override
    public void tick(PlantEntity plant, LevelAccess level) {
        var status = EchoNetwork.status(plant, level);
        plant.setState(status.rate() > 1F ? "charged"
                : status.lilies() > 0 ? "linked" : "idle");
    }
}
