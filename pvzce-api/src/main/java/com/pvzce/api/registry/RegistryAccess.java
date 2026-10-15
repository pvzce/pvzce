package com.pvzce.api.registry;

import com.pvzce.api.util.Identifier;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** A collection of named registries (static layer + dynamic layer). */
public final class RegistryAccess {
    private final Map<ResourceKey<?>, Registry<?>> registries = new LinkedHashMap<>();

    @SuppressWarnings("unchecked")
    public <T> Registry<T> newRegistry(ResourceKey<Registry<T>> key) {
        MappedRegistry<T> registry = new MappedRegistry<>(key);
        registries.put(key, registry);
        return registry;
    }

    public void registerRegistry(ResourceKey<?> key, Registry<?> registry) {
        registries.put(key, registry);
    }

    @SuppressWarnings("unchecked")
    public <T> Registry<T> get(ResourceKey<Registry<T>> key) {
        return (Registry<T>) registries.get(key);
    }

    /** Independent registries for editors/validators; normal reload fills their dynamic entries. */
    public RegistryAccess copyForReload() {
        RegistryAccess copy = new RegistryAccess();
        for (var entry : registries.entrySet()) {
            if (!(entry.getValue() instanceof MappedRegistry<?> registry))
                throw new IllegalStateException("Unsupported registry implementation: " + entry.getKey());
            copy.registerRegistry(entry.getKey(), registry.copyForReload());
        }
        return copy;
    }

    public Map<ResourceKey<?>, Registry<?>> registries() {
        return Collections.unmodifiableMap(registries);
    }
}
