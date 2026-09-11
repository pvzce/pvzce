package com.pvzce.api.content;

import com.mojang.serialization.Codec;
import com.pvzce.api.util.Identifier;

/**
 * A typed environment-variable channel. Level env vars are injected by level
 * JSON and can be read/written by any component; the type definition carries
 * the Codec used to validate values.
 */
public record EnvVarType<T>(Identifier id, Codec<T> codec, T defaultValue) {
}
