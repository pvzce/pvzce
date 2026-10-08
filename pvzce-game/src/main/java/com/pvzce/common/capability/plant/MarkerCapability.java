package com.pvzce.common.capability.plant;

import com.mojang.serialization.MapCodec;
import com.pvzce.api.content.capability.PlantCapability;

/** A descriptive ability only: defence and carrying still follow health and placement data. */
public record MarkerCapability() implements PlantCapability {
    public static final MarkerCapability INSTANCE = new MarkerCapability();
    public static final MapCodec<MarkerCapability> CODEC = MapCodec.unit(INSTANCE);
}
