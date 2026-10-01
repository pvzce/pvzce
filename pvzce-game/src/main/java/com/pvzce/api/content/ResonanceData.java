package com.pvzce.api.content;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.content.mechanic.MechanicData;
import com.pvzce.common.PvzceConstants;

/** A travelling row that speeds enemies up and lends connected lilies an extra beat. */
public record ResonanceData(int intervalTicks, int pulseTicks, int openingRow,
                            float zombieSpeed, int damage) implements MechanicData {
    public static final MapCodec<ResonanceData> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.intRange(60, 7200).optionalFieldOf("interval_ticks", PvzceConstants.RESONANCE_INTERVAL_TICKS)
                    .forGetter(ResonanceData::intervalTicks),
            Codec.intRange(60, 7200).optionalFieldOf("pulse_ticks", PvzceConstants.RESONANCE_PULSE_TICKS)
                    .forGetter(ResonanceData::pulseTicks),
            Codec.intRange(0, 63).optionalFieldOf("opening_row", 2).forGetter(ResonanceData::openingRow),
            Codec.floatRange(1F, 3F).optionalFieldOf("zombie_speed", PvzceConstants.RESONANCE_ZOMBIE_SPEED)
                    .forGetter(ResonanceData::zombieSpeed),
            Codec.intRange(1, 1000).optionalFieldOf("damage", PvzceConstants.RESONANCE_DAMAGE)
                    .forGetter(ResonanceData::damage)
    ).apply(i, ResonanceData::new));
}
