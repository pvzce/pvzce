package com.pvzce.api.registry;

import com.pvzce.api.util.Identifier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MappedRegistryTest {
    @Test
    void dynamicReloadKeepsStaticEntries() {
        ResourceKey<Registry<String>> key = ResourceKey.create(Identifier.withDefaultNamespace("root"), Identifier.withDefaultNamespace("test"));
        Registry<String> registry = new MappedRegistry<>(key);
        Registry.register(registry, "pvzce:static", "static-value");
        ((MappedRegistry<String>) registry).registerDynamic(Identifier.withDefaultNamespace("dyn"), "dynamic-1");
        registry.freeze();

        assertEquals("dynamic-1", registry.get(Identifier.withDefaultNamespace("dyn")));

        registry.unfreeze();
        ((MappedRegistry<String>) registry).clearDynamic();
        ((MappedRegistry<String>) registry).registerDynamic(Identifier.withDefaultNamespace("dyn"), "dynamic-2");
        registry.freeze();

        assertEquals("static-value", registry.get(Identifier.withDefaultNamespace("static")));
        assertEquals("dynamic-2", registry.get(Identifier.withDefaultNamespace("dyn")));
        assertNull(registry.get(Identifier.withDefaultNamespace("missing")));
    }

    @Test
    void frozenRegistryRejectsWrites() {
        ResourceKey<Registry<Integer>> key = ResourceKey.create(Identifier.withDefaultNamespace("root"), Identifier.withDefaultNamespace("ints"));
        Registry<Integer> registry = new MappedRegistry<>(key);
        Registry.register(registry, "one", 1);
        registry.freeze();
        assertThrows(IllegalStateException.class, () -> Registry.register(registry, "two", 2));
    }
}
