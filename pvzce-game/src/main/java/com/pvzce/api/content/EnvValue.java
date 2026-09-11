package com.pvzce.api.content;

import com.google.gson.JsonElement;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.util.Identifier;

/** One injected environment variable: a registered type plus a JSON value. */
public record EnvValue(Identifier type, JsonElement value) {
    public static final Codec<EnvValue> CODEC = RecordCodecBuilder.create(i -> i.group(
            Identifier.CODEC.fieldOf("type").forGetter(EnvValue::type),
            JsonCodecs.RAW_JSON.fieldOf("value").forGetter(EnvValue::value)
    ).apply(i, EnvValue::new));

    public static EnvValue of(String type, JsonElement value) {
        return new EnvValue(Identifier.parse(type), value);
    }
}
