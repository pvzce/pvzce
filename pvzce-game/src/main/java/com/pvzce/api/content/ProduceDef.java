package com.pvzce.api.content;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.util.Identifier;

/** Periodic production (sunflower sun, marigold coins/resources, ...). */
public record ProduceDef(Identifier resource, int amount, int everyTicks) {
    public static final Codec<ProduceDef> CODEC = RecordCodecBuilder.create(i -> i.group(
            Identifier.CODEC.fieldOf("resource").forGetter(ProduceDef::resource),
            Codec.INT.optionalFieldOf("amount", 25).forGetter(ProduceDef::amount),
            Codec.INT.fieldOf("every").forGetter(ProduceDef::everyTicks)
    ).apply(i, ProduceDef::new));
}
