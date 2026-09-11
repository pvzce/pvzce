package com.pvzce.api.content;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * One status application: which status, for how long, and how strong.
 *
 * <p>{@code magnitude} is status-specific - 0.7 means "70% speed" for
 * {@link ZombieStatus#SLOW} and is ignored by {@link ZombieStatus#IMMOBILIZED}.
 * Keeping the strength in data is what lets a data pack ship a "slow 50% for 2s"
 * variant without a new hardcoded effect id.
 */
public record StatusEffectDef(ZombieStatus status, int ticks, float magnitude) {
    public static final Codec<StatusEffectDef> CODEC = RecordCodecBuilder.create(i -> i.group(
            ZombieStatus.CODEC.fieldOf("status").forGetter(StatusEffectDef::status),
            Codec.INT.optionalFieldOf("ticks", 240).forGetter(StatusEffectDef::ticks),
            Codec.FLOAT.optionalFieldOf("magnitude", 0.7F).forGetter(StatusEffectDef::magnitude)
    ).apply(i, StatusEffectDef::new));

    public StatusEffectDef {
        ticks = Math.max(0, ticks);
        magnitude = Math.max(0F, magnitude);
    }
}
