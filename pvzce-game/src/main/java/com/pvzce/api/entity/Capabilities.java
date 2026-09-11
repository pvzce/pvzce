package com.pvzce.api.entity;

import java.util.List;

/**
 * Shared helpers for reasoning about a capability list. Both the entity
 * constructors and the JSON presets go through here so "find the capability of
 * type X" is written exactly once.
 */
public final class Capabilities {
    private Capabilities() {
    }

    /** The first capability whose type id's path equals {@code path}, or {@code null}. */
    public static <T> T find(List<T> capabilities, String path, java.util.function.Function<T, ?> typeIdOf) {
        for (T capability : capabilities) {
            Object id = typeIdOf.apply(capability);
            if (id instanceof com.pvzce.api.util.Identifier identifier && identifier.path().equals(path)) {
                return capability;
            }
        }
        return null;
    }

    public static <T> boolean has(List<T> capabilities, String path, java.util.function.Function<T, ?> typeIdOf) {
        return find(capabilities, path, typeIdOf) != null;
    }
}
