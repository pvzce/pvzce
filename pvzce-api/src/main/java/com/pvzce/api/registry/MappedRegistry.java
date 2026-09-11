package com.pvzce.api.registry;

import com.pvzce.api.tag.Tag;
import com.pvzce.api.tag.TagKey;
import com.pvzce.api.tag.TagSet;
import com.pvzce.api.util.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Bidirectional id-to-value registry. Static registrations are kept across
 * reloads; dynamic registrations are tracked separately so that
 * {@link #clearDynamic()} followed by a data reload is idempotent.
 */
public class MappedRegistry<T> implements Registry<T> {
    private final ResourceKey<? extends Registry<T>> key;
    private final List<T> byId = new ArrayList<>();
    private final Map<Identifier, T> byKey = new HashMap<>();
    private final Map<T, Identifier> keyByValue = new IdentityHashMap<>();
    private final Map<T, Integer> idByValue = new IdentityHashMap<>();
    private final Set<Identifier> dynamicKeys = new LinkedHashSet<>();
    private final Map<TagKey<T>, Tag<T>> tags = new LinkedHashMap<>();
    private boolean frozen;
    private boolean everFrozen;

    public MappedRegistry(ResourceKey<? extends Registry<T>> key) {
        this.key = key;
    }

    @Override
    public ResourceKey<? extends Registry<T>> key() {
        return key;
    }

    /**
     * Registers a code-defined entry and notifies
     * {@link RegistryEntryAddedCallback} listeners.
     *
     * <p>The callback is fired <em>here</em> rather than in a caller, because the
     * two entry points used to disagree: {@code BuiltInRegistries.registerStatic}
     * fired it while the documented {@code Registry.register(...)} did not, so a mod
     * following the mod guide registered its listener and never heard anything.
     * Data-pack entries fire it too, for the same reason.
     */
    public void register(Identifier id, T value) {
        validateStaticWrite(id);
        if (byKey.containsKey(id)) {
            throw new IllegalStateException("Adding duplicate key '" + id + "' to registry " + key);
        }
        if (keyByValue.containsKey(value)) {
            throw new IllegalStateException("Adding duplicate value '" + value + "' to registry " + key);
        }
        putInternal(id, value, false);
        RegistryEntryAddedCallback.fire(this, id, value);
    }

    /** Registers or overrides an entry coming from a data pack. */
    public void registerDynamic(Identifier id, T value) {
        validateWrite(id);
        removeInternal(id);
        putInternal(id, value, true);
        RegistryEntryAddedCallback.fire(this, id, value);
    }

    public void clearDynamic() {
        if (frozen) {
            throw new IllegalStateException("Registry is frozen");
        }
        List<Identifier> ids = new ArrayList<>(dynamicKeys);
        for (Identifier id : ids) {
            removeInternal(id);
        }
        dynamicKeys.clear();
    }

    private void putInternal(Identifier id, T value, boolean dynamic) {
        byKey.put(id, value);
        keyByValue.put(value, id);
        int newId = byId.size();
        byId.add(value);
        idByValue.put(value, newId);
        if (dynamic) {
            dynamicKeys.add(id);
        }
    }

    private void removeInternal(Identifier id) {
        T old = byKey.remove(id);
        if (old == null) return;
        keyByValue.remove(old);
        Integer rawId = idByValue.remove(old);
        if (rawId != null && rawId < byId.size() && byId.get(rawId) == old) {
            byId.set(rawId, null);
        }
        dynamicKeys.remove(id);
    }

    private void compactIfNeeded() {
        if (byId.stream().noneMatch(java.util.Objects::isNull)) return;
        List<T> compact = new ArrayList<>(byId.size());
        idByValue.clear();
        for (T value : byId) {
            if (value != null) {
                idByValue.put(value, compact.size());
                compact.add(value);
            }
        }
        byId.clear();
        byId.addAll(compact);
    }

    private void validateWrite(Identifier id) {
        if (frozen) {
            throw new IllegalStateException("Registry " + key + " is already frozen (trying to add key " + id + ")");
        }
    }

    private void validateStaticWrite(Identifier id) {
        validateWrite(id);
        if (everFrozen) {
            throw new IllegalStateException("Static registration for " + id + " is only allowed before the registry is first frozen");
        }
    }

    @Override
    public @Nullable T get(@Nullable Identifier id) {
        if (id == null) return null;
        return byKey.get(id);
    }

    @Override
    public @Nullable T getById(int rawId) {
        compactIfNeeded();
        return rawId >= 0 && rawId < byId.size() ? byId.get(rawId) : null;
    }

    @Override
    public @Nullable Identifier getKey(T value) {
        return value == null ? null : keyByValue.get(value);
    }

    @Override
    public int getId(@Nullable T value) {
        compactIfNeeded();
        Integer id = value == null ? null : idByValue.get(value);
        return id == null ? -1 : id;
    }

    @Override
    public boolean containsKey(Identifier id) {
        return byKey.containsKey(id);
    }

    @Override
    public Set<Identifier> keySet() {
        return Collections.unmodifiableSet(new HashSet<>(byKey.keySet()));
    }

    @Override
    public Optional<Tag<T>> getTag(TagKey<T> tag) {
        return Optional.ofNullable(tags.get(tag));
    }

    @Override
    public Set<TagKey<T>> tagKeys() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(tags.keySet()));
    }

    @Override
    public Registry<T> bindTag(TagKey<T> tag, Set<Identifier> ids) {
        tags.put(tag, new TagSet<>(this, tag.id(), ids));
        return this;
    }

    @Override
    public void clearTags() {
        tags.clear();
    }

    @Override
    public Registry<T> freeze() {
        compactIfNeeded();
        frozen = true;
        everFrozen = true;
        return this;
    }

    @Override
    public void unfreeze() {
        frozen = false;
    }

    @Override
    public int size() {
        return byKey.size();
    }

    @Override
    public Iterator<T> iterator() {
        compactIfNeeded();
        return new ArrayList<>(byId).iterator();
    }

    @Override
    public String toString() {
        return "Registry[" + key + " (" + size() + " entries)]";
    }
}
