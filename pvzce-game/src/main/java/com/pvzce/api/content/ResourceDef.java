package com.pvzce.api.content;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.util.Identifier;

/** A resource type (sun, redstone, energy bean, ...). */
public record ResourceDef(
        Identifier id,
        int defaultValue,
        boolean collectible,
        Identifier icon,
        Identifier dropAnim,
        int maxStack,
        boolean collectibleWithoutCard
) {
    public static final Codec<ResourceDef> CODEC = RecordCodecBuilder.create(i -> i.group(
            Identifier.CODEC.fieldOf("id").forGetter(ResourceDef::id),
            Codec.INT.optionalFieldOf("default_value", 25).forGetter(ResourceDef::defaultValue),
            Codec.BOOL.optionalFieldOf("collectible", true).forGetter(ResourceDef::collectible),
            Identifier.CODEC.optionalFieldOf("icon", Identifier.withDefaultNamespace("textures/resource/generic")).forGetter(ResourceDef::icon),
            Identifier.CODEC.optionalFieldOf("drop_anim", Identifier.withDefaultNamespace("sun_fall")).forGetter(ResourceDef::dropAnim),
            Codec.INT.optionalFieldOf("max_stack", 9990).forGetter(ResourceDef::maxStack),
            Codec.BOOL.optionalFieldOf("collectible_without_card", false).forGetter(ResourceDef::collectibleWithoutCard)
    ).apply(i, ResourceDef::new));
}
