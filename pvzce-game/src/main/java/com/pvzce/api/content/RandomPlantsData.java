package com.pvzce.api.content;

import com.pvzce.api.content.mechanic.MechanicData;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/** The seed belongs to the authored level, independently of the simulation's random stream. */
public record RandomPlantsData(long seed, boolean upgradesWithoutBases, boolean graveBustersAnywhere,
                               boolean carriersOnWater, boolean abilityPlacement) implements MechanicData {
    public static final MapCodec<RandomPlantsData> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.LONG.fieldOf("seed").forGetter(RandomPlantsData::seed),
            Codec.BOOL.optionalFieldOf("upgrades_without_bases", false).forGetter(RandomPlantsData::upgradesWithoutBases),
            Codec.BOOL.optionalFieldOf("grave_busters_anywhere", false).forGetter(RandomPlantsData::graveBustersAnywhere),
            Codec.BOOL.optionalFieldOf("carriers_on_water", false).forGetter(RandomPlantsData::carriersOnWater),
            Codec.BOOL.optionalFieldOf("ability_placement", false).forGetter(RandomPlantsData::abilityPlacement)
    ).apply(i, RandomPlantsData::new));

    public RandomPlantsData(long seed) {
        this(seed, false, false, false, false);
    }

    public RandomPlantsData(long seed, boolean upgradesWithoutBases, boolean graveBustersAnywhere,
                            boolean carriersOnWater) {
        this(seed, upgradesWithoutBases, graveBustersAnywhere, carriersOnWater, false);
    }
}
