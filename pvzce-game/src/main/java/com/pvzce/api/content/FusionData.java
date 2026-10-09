package com.pvzce.api.content;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.content.mechanic.MechanicData;
import com.pvzce.common.PvzceConstants;

/** The economy of an ability fusion level. */
public record FusionData(boolean tutorial, float lossChance, float dropMultiplier, int price) implements MechanicData {
    public static final MapCodec<FusionData> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.BOOL.optionalFieldOf("tutorial", false).forGetter(FusionData::tutorial),
            Codec.floatRange(0F, 1F).optionalFieldOf("loss_chance", PvzceConstants.FUSION_LOSS_CHANCE).forGetter(FusionData::lossChance),
            Codec.floatRange(0F, Float.MAX_VALUE).optionalFieldOf("drop_multiplier", PvzceConstants.FUSION_DROP_MULTIPLIER).forGetter(FusionData::dropMultiplier),
            Codec.intRange(1, 10000).optionalFieldOf("price", PvzceConstants.FUSION_ABILITY_PRICE).forGetter(FusionData::price)
    ).apply(i, FusionData::new));
}
