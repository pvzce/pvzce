package com.pvzce.api.content;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.util.Identifier;

/** A registered sound event; actual files come from assets/<ns>/sounds.json. */
public record SoundEventDef(Identifier id, String subtitle) {
    public static final Codec<SoundEventDef> CODEC = RecordCodecBuilder.create(i -> i.group(
            Identifier.CODEC.fieldOf("id").forGetter(SoundEventDef::id),
            Codec.STRING.optionalFieldOf("subtitle", "").forGetter(SoundEventDef::subtitle)
    ).apply(i, SoundEventDef::new));
}
