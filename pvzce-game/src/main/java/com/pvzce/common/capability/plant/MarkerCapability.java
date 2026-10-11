package com.pvzce.common.capability.plant;

import com.pvzce.api.content.capability.PlantCapability;
import com.mojang.serialization.MapCodec;

/** A descriptive ability: level mechanics can project carrying into placement data. */
public record MarkerCapability() implements PlantCapability {
    public static final MarkerCapability INSTANCE = new MarkerCapability();
    public static final MapCodec<MarkerCapability> CODEC = MapCodec.unit(INSTANCE);
}
