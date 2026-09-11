package com.pvzce.api.registry;

import com.pvzce.api.tag.Tag;
import com.pvzce.api.tag.TagKey;
import com.pvzce.api.util.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.Set;

/**
 * Minimal MC-style registry: a frozen-after-load bidirectional
 * Identifier-to-value table usable by both static (code) and dynamic
 * (data pack) registration.
 */
public interface Registry<T> extends Iterable<T> {
    ResourceKey<? extends Registry<T>> key();

    @Nullable T get(@Nullable Identifier id);

    @Nullable T getById(int rawId);

    @Nullable Identifier getKey(T value);

    int getId(@Nullable T value);

    boolean containsKey(Identifier id);

    Set<Identifier> keySet();

    Registry<T> freeze();

    void unfreeze();

    int size();

    /** Tags bound to this registry by the tag loader ({@code data/.../tags/pvzce/...}). */
    Optional<Tag<T>> getTag(TagKey<T> tag);

    Set<TagKey<T>> tagKeys();

    /** Binds (or replaces) a resolved tag; called by the tag loader during reload. */
    Registry<T> bindTag(TagKey<T> tag, Set<Identifier> ids);

    /** Clears every loaded tag; called at the start of a tag reload. */
    void clearTags();

    default Optional<T> getOptional(@Nullable Identifier id) {
        return Optional.ofNullable(get(id));
    }

    static <V, T extends V> T register(Registry<V> registry, String id, T value) {
        return register(registry, Identifier.parse(id), value);
    }

    static <V, T extends V> T register(Registry<V> registry, Identifier id, T value) {
        ((MappedRegistry<V>) registry).register(id, value);
        return value;
    }
}
