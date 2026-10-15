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
    void isolatedReloadDoesNotKeepRemovedPackContentOrUseLiveCallbacks() {
        RegistryAccess access = new RegistryAccess();
        ResourceKey<Registry<String>> key = ResourceKey.create(Identifier.withDefaultNamespace("root"), Identifier.withDefaultNamespace("preview"));
        Registry<String> live = access.newRegistry(key);
        Registry.register(live, "pvzce:static", "static");
        ((MappedRegistry<String>) live).registerDynamic(Identifier.parse("old:removed"), "removed");
        live.freeze();
        java.util.concurrent.atomic.AtomicInteger events = new java.util.concurrent.atomic.AtomicInteger();
        RegistryEntryAddedCallback.event(live).register((id, value) -> events.incrementAndGet());
        Registry<String> isolated = access.copyForReload().get(key);
        assertEquals("static", isolated.get(Identifier.parse("pvzce:static")));
        assertNull(isolated.get(Identifier.parse("old:removed")));
        isolated.unfreeze();
        ((MappedRegistry<String>) isolated).registerDynamic(Identifier.parse("draft:new"), "draft");
        assertEquals(0, events.get());
        assertNull(live.get(Identifier.parse("draft:new")));
        assertEquals("removed", live.get(Identifier.parse("old:removed")));
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
