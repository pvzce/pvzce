package com.pvzce.api.tag;

import com.pvzce.api.registry.Registry;
import com.pvzce.api.util.Identifier;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/** Registry-backed tag: stores ids, resolves values lazily through the registry. */
public final class TagSet<T> implements Tag<T> {
    private final Registry<T> registry;
    private final Identifier id;
    private final Set<Identifier> ids;

    public TagSet(Registry<T> registry, Identifier id, Set<Identifier> ids) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.id = Objects.requireNonNull(id, "id");
        this.ids = Collections.unmodifiableSet(new LinkedHashSet<>(ids));
    }

    @Override
    public Identifier id() {
        return id;
    }

    @Override
    public Set<Identifier> ids() {
        return ids;
    }

    @Override
    public boolean containsId(Identifier id) {
        return ids.contains(id);
    }

    @Override
    public boolean contains(T value) {
        Identifier key = registry.getKey(value);
        return key != null && ids.contains(key);
    }

    @Override
    public Set<T> entries() {
        Set<T> entries = new LinkedHashSet<>(ids.size());
        for (Identifier id : ids) {
            T value = registry.get(id);
            if (value != null) {
                entries.add(value);
            }
        }
        return Collections.unmodifiableSet(entries);
    }

    @Override
    public int size() {
        return ids.size();
    }

    @Override
    public String toString() {
        return "#" + id + ids;
    }
}
