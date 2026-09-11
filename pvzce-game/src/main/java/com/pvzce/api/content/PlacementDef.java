package com.pvzce.api.content;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/** Placement rule: which stack layer this plant occupies and how many fit per cell. */
public record PlacementDef(String feet, int count) {
    public static final Codec<PlacementDef> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.optionalFieldOf("feet", "plantable").forGetter(PlacementDef::feet),
            Codec.INT.optionalFieldOf("count", 1).forGetter(PlacementDef::count)
    ).apply(i, PlacementDef::new));

    public static final PlacementDef PLANTABLE = new PlacementDef("plantable", 1);
}
