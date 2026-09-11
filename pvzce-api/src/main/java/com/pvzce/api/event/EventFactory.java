package com.pvzce.api.event;

import java.util.function.Function;

/** Factory for array-backed events (Fabric API design). */
public final class EventFactory {
    private EventFactory() {
    }

    public static <T> Event<T> createArrayBacked(Class<T> type, T emptyInvoker, Function<T[], T> factory) {
        return new Event<>(type, emptyInvoker, factory);
    }

    public static <T> Event<T> createArrayBacked(Class<T> type, Function<T[], T> factory) {
        return createArrayBacked(type, factory.apply(empty(type)), factory);
    }

    @SuppressWarnings("unchecked")
    private static <T> T[] empty(Class<T> type) {
        return (T[]) java.lang.reflect.Array.newInstance(type, 0);
    }
}
