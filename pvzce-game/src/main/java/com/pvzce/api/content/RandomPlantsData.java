package com.pvzce.api.content;

import com.pvzce.api.content.mechanic.MechanicData;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/** The seed belongs to the authored level, independently of the simulation's random stream. */
public record RandomPlantsData(long seed) implements MechanicData {
    public static final MapCodec<RandomPlantsData> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.LONG.fieldOf("seed").forGetter(RandomPlantsData::seed)
    ).apply(i, RandomPlantsData::new));
}
