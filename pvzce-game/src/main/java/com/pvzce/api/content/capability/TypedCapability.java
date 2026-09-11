package com.pvzce.api.content.capability;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.pvzce.api.registry.Registry;
import com.pvzce.api.util.Identifier;

/**
 * A capability plus the registry id it was decoded from. The id is carried
 * alongside the value (rather than inside it) so capability records stay flat
 * and per-entity state can be saved under a stable key.
 */
public record TypedCapability<T>(Identifier type, T value) {
    public static <T> Codec<TypedCapability<T>> codec(Registry<CapabilityType<T>> registry, String label) {
        return Identifier.CODEC.partialDispatch("type",
                capability -> DataResult.success(capability.type()),
                id -> {
                    CapabilityType<T> type = registry.get(id);
                    if (type == null) {
                        return DataResult.error(() -> "Unknown " + label + " capability type: " + id);
                    }
                    return DataResult.success(type.codec()
                            .xmap(value -> new TypedCapability<>(id, value), TypedCapability::value));
                });
    }
}
