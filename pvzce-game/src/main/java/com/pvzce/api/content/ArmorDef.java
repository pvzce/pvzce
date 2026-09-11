package com.pvzce.api.content;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.util.Identifier;

/**
 * One piece of zombie armor. {@code position} is {@code front} or {@code top};
 * front armor intercepts bullets before the body, top armor intercepts
 * lobbed/arc projectiles first.
 */
public record ArmorDef(Identifier id, int durability, String position) {
    public static final String FRONT = "front";
    public static final String TOP = "top";

    public static final Codec<ArmorDef> CODEC = RecordCodecBuilder.create(i -> i.group(
            Identifier.CODEC.optionalFieldOf("id", Identifier.withDefaultNamespace("armor")).forGetter(ArmorDef::id),
            Codec.INT.fieldOf("durability").forGetter(ArmorDef::durability),
            Codec.STRING.optionalFieldOf("position", FRONT).forGetter(ArmorDef::position)
    ).apply(i, ArmorDef::new));
}
