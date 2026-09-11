package com.pvzce.api.registry;

import com.pvzce.api.event.Event;
import com.pvzce.api.event.EventFactory;
import com.pvzce.api.util.Identifier;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Fabric-style content-registry callback for static registrations. */
public final class RegistryEntryAddedCallback<T> {
    @FunctionalInterface
    public interface Callback<T> {
        void onEntryAdded(Identifier id, T value);
    }

    private static final Map<Registry<?>, RegistryEntryAddedCallback<?>> CALLBACKS = new ConcurrentHashMap<>();

    private final Registry<T> registry;
    private final Event<Callback<T>> event;

    private RegistryEntryAddedCallback(Registry<T> registry) {
        this.registry = registry;
        this.event = EventFactory.createArrayBacked(castCallbackClass(), callbacks -> (id, value) -> {
            for (Callback<T> callback : callbacks) {
                callback.onEntryAdded(id, value);
            }
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> Class<Callback<T>> castCallbackClass() {
        return (Class<Callback<T>>) (Class<?>) Callback.class;
    }

    public void register(Callback<T> callback) {
        event.register(callback);
    }

    @SuppressWarnings("unchecked")
    public static <T> RegistryEntryAddedCallback<T> event(Registry<T> registry) {
        return (RegistryEntryAddedCallback<T>) CALLBACKS.computeIfAbsent(registry, RegistryEntryAddedCallback::new);
    }

    @SuppressWarnings("unchecked")
    public static <T> void fire(Registry<T> registry, Identifier id, T value) {
        RegistryEntryAddedCallback<T> callback = (RegistryEntryAddedCallback<T>) CALLBACKS.get(registry);
        if (callback != null) {
            callback.event.invoker().onEntryAdded(id, value);
        }
    }
}
