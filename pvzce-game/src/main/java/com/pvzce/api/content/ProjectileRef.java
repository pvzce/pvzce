package com.pvzce.api.content;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.util.Identifier;

/** A projectile emission from a plant definition. */
public record ProjectileRef(Identifier projectile, int damage, int count) {
    public static final Codec<ProjectileRef> CODEC = RecordCodecBuilder.create(i -> i.group(
            Identifier.CODEC.fieldOf("projectile").forGetter(ProjectileRef::projectile),
            Codec.INT.optionalFieldOf("damage", 20).forGetter(ProjectileRef::damage),
            Codec.INT.optionalFieldOf("count", 1).forGetter(ProjectileRef::count)
    ).apply(i, ProjectileRef::new));
}
