package com.pvzce.api.content;

import com.pvzce.api.content.mechanic.MechanicData;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/** Authored seed and optional per-run randomization, independent of the simulation's dice. */
public record RandomPlantsData(long seed, boolean upgradesWithoutBases, boolean graveBustersAnywhere,
                               boolean carriersOnWater, boolean abilityPlacement,
                               boolean randomSeedOnStart) implements MechanicData {
    public static final MapCodec<RandomPlantsData> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.LONG.fieldOf("seed").forGetter(RandomPlantsData::seed),
            Codec.BOOL.optionalFieldOf("upgrades_without_bases", false).forGetter(RandomPlantsData::upgradesWithoutBases),
            Codec.BOOL.optionalFieldOf("grave_busters_anywhere", false).forGetter(RandomPlantsData::graveBustersAnywhere),
            Codec.BOOL.optionalFieldOf("carriers_on_water", false).forGetter(RandomPlantsData::carriersOnWater),
            Codec.BOOL.optionalFieldOf("ability_placement", false).forGetter(RandomPlantsData::abilityPlacement),
            Codec.BOOL.optionalFieldOf("random_seed_on_start", false).forGetter(RandomPlantsData::randomSeedOnStart)
    ).apply(i, RandomPlantsData::new));

    public RandomPlantsData(long seed) {
        this(seed, false, false, false, false);
    }

    public RandomPlantsData(long seed, boolean upgradesWithoutBases, boolean graveBustersAnywhere,
                            boolean carriersOnWater, boolean abilityPlacement) {
        this(seed, upgradesWithoutBases, graveBustersAnywhere, carriersOnWater, abilityPlacement, false);
    }

    public RandomPlantsData(long seed, boolean upgradesWithoutBases, boolean graveBustersAnywhere,
                            boolean carriersOnWater) {
        this(seed, upgradesWithoutBases, graveBustersAnywhere, carriersOnWater, false);
    }

    public RandomPlantsData withSeed(long value) {
        return new RandomPlantsData(value, upgradesWithoutBases, graveBustersAnywhere,
                carriersOnWater, abilityPlacement, randomSeedOnStart);
    }
}
