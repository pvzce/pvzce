package com.pvzce.api.tag;

import com.pvzce.api.util.Identifier;

import java.util.Set;

/**
 * A resolved tag bound to a registry. Mirrors the query surface of
 * {@code net.minecraft.tags.TagKey} + HolderSet that PVZCE content needs:
 * id lookup, membership tests and entry iteration.
 */
public interface Tag<T> {
    Identifier id();

    Set<Identifier> ids();

    boolean containsId(Identifier id);

    boolean contains(T value);

    Set<T> entries();

    int size();
}
