package com.pvzce.api.tag;

import com.pvzce.api.registry.Registry;
import com.pvzce.api.registry.ResourceKey;
import com.pvzce.api.util.Identifier;

/**
 * Typed key for a data-driven tag ({@code #pvzce:sun_producer}).
 * Mirrors {@code net.minecraft.tags.TagKey}.
 */
public record TagKey<T>(ResourceKey<? extends Registry<T>> registry, Identifier id) {
    public static <T> TagKey<T> create(ResourceKey<? extends Registry<T>> registry, Identifier id) {
        return new TagKey<>(registry, id);
    }

    @Override
    public String toString() {
        return "#" + id;
    }
}
