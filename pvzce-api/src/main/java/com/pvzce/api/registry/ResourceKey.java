package com.pvzce.api.registry;

import com.pvzce.api.util.Identifier;

/**
 * Typed key pairing a registry location and an element location inside it.
 * Mirrors {@code net.minecraft.resources.ResourceKey}.
 */
public record ResourceKey<T>(Identifier registry, Identifier location) {
    public static <T> ResourceKey<T> create(Identifier registry, Identifier location) {
        return new ResourceKey<>(registry, location);
    }

    @Override
    public String toString() {
        return "ResourceKey[" + registry + " / " + location + "]";
    }
}
