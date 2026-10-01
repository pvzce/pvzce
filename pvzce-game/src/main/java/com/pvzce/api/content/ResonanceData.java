package com.pvzce.api.content;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.content.mechanic.MechanicData;
import com.pvzce.common.PvzceConstants;

/** A travelling row that speeds enemies up and accelerates connected lilies. */
public record ResonanceData(int intervalTicks, int openingRow,
                            float zombieSpeed, float plantRate) implements MechanicData {
    public static final MapCodec<ResonanceData> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.intRange(60, 7200).optionalFieldOf("interval_ticks", PvzceConstants.RESONANCE_INTERVAL_TICKS)
                    .forGetter(ResonanceData::intervalTicks),
            Codec.intRange(0, 63).optionalFieldOf("opening_row", 2).forGetter(ResonanceData::openingRow),
            Codec.floatRange(1F, 3F).optionalFieldOf("zombie_speed", PvzceConstants.RESONANCE_ZOMBIE_SPEED)
                    .forGetter(ResonanceData::zombieSpeed),
            Codec.floatRange(1F, 4F).optionalFieldOf("plant_rate", PvzceConstants.RESONANCE_PLANT_RATE)
                    .forGetter(ResonanceData::plantRate)
    ).apply(i, ResonanceData::new));
}
