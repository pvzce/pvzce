package com.pvzce.api.content;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.content.mechanic.MechanicData;
import com.pvzce.common.PvzceConstants;

/** The economy of an ability fusion level. */
public record FusionData(boolean tutorial, float lossChance, float dropChance, int price) implements MechanicData {
    public static final MapCodec<FusionData> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.BOOL.optionalFieldOf("tutorial", false).forGetter(FusionData::tutorial),
            Codec.floatRange(0F, 1F).optionalFieldOf("loss_chance", PvzceConstants.FUSION_LOSS_CHANCE).forGetter(FusionData::lossChance),
            Codec.floatRange(0F, 1F).optionalFieldOf("drop_chance", PvzceConstants.FUSION_DROP_CHANCE).forGetter(FusionData::dropChance),
            Codec.intRange(1, 10000).optionalFieldOf("price", PvzceConstants.FUSION_ABILITY_PRICE).forGetter(FusionData::price)
    ).apply(i, FusionData::new));
}
