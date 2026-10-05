package com.pvzce.api.content;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.util.Identifier;

/** A pre-placed entity inside a level file (used by the in-game editor). */
public record InitialEntityDef(String kind, Identifier id, int x, int y, java.util.Optional<Identifier> surface,
                               com.pvzce.api.entity.attribute.AttributeOverrides attributes) {
    public InitialEntityDef(String kind, Identifier id, int x, int y, java.util.Optional<Identifier> surface) {
        this(kind, id, x, y, surface, com.pvzce.api.entity.attribute.AttributeOverrides.EMPTY);
    }
    public InitialEntityDef(String kind, Identifier id, int x, int y) {
        this(kind, id, x, y, java.util.Optional.empty());
    }
    public static final Codec<InitialEntityDef> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("kind").forGetter(InitialEntityDef::kind),
            Identifier.CODEC.fieldOf("id").forGetter(InitialEntityDef::id),
            Codec.INT.fieldOf("x").forGetter(InitialEntityDef::x),
            Codec.INT.fieldOf("y").forGetter(InitialEntityDef::y),
            Identifier.CODEC.optionalFieldOf("surface").forGetter(InitialEntityDef::surface),
            com.pvzce.api.entity.attribute.AttributeOverrides.CODEC.optionalFieldOf("attributes",
                    com.pvzce.api.entity.attribute.AttributeOverrides.EMPTY).forGetter(InitialEntityDef::attributes)
    ).apply(i, InitialEntityDef::new));

    public InitialEntityDef {
        attributes = attributes == null ? com.pvzce.api.entity.attribute.AttributeOverrides.EMPTY : attributes;
    }
}
