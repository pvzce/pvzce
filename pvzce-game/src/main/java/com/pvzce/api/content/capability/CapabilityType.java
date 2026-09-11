package com.pvzce.api.content.capability;

import com.mojang.serialization.MapCodec;
import com.pvzce.api.util.Identifier;

/**
 * A registered capability type: its id plus the codec that decodes its JSON
 * configuration. Capability instances themselves do not store their type id, so
 * JSON stays flat ({@code {"type": "pvzce:shooter", "interval": 90}}) and the
 * registry remains the single source of truth.
 */
public record CapabilityType<T>(Identifier id, MapCodec<T> codec) {
}
