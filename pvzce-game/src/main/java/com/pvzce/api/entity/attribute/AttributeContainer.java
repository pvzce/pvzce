package com.pvzce.api.entity.attribute;

import com.pvzce.api.util.Identifier;
import com.pvzce.common.nbt.CompoundTag;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

/** One entity's attributes. Registry definitions and another entity's values are never mutated. */
public final class AttributeContainer {
    private final Function<Identifier, EntityAttribute> registry;
    private final Consumer<Identifier> changed;
    private final Map<Identifier, AttributeInstance> instances = new LinkedHashMap<>();
    private boolean dirty;

    public AttributeContainer(Function<Identifier, EntityAttribute> registry, Consumer<Identifier> changed) {
        this.registry = registry;
        this.changed = changed;
    }

    public boolean has(Identifier id) { return instances.containsKey(id); }
    public AttributeInstance get(Identifier id) {
        AttributeInstance instance = instances.get(id);
        if (instance == null) throw new IllegalArgumentException("Entity has no attribute: " + id);
        return instance;
    }
    public double value(Identifier id) { return get(id).value(); }

    /** Adds a registered attribute with its default base, or returns the existing instance. */
    public AttributeInstance add(Identifier id) {
        return has(id) ? get(id) : add(id, definition(id).defaultValue());
    }

    public AttributeInstance add(Identifier id, double base) {
        if (has(id)) throw new IllegalArgumentException("Entity already has attribute: " + id);
        AttributeInstance instance = new AttributeInstance(definition(id), base, () -> changed(id));
        instances.put(id, instance);
        changed(id);
        return instance;
    }

    public void apply(AttributeOverrides overrides) {
        // Reject unknown names before any part of a spawn override is applied.
        overrides.values().keySet().forEach(this::definition);
        overrides.values().entrySet().stream().sorted(Map.Entry.comparingByKey(
                java.util.Comparator.comparing(Identifier::toString))).forEach(entry -> {
            AttributeInstance instance = add(entry.getKey());
            entry.getValue().base().ifPresent(instance::setBaseValue);
            entry.getValue().modifiers().forEach(instance::setModifier);
        });
    }

    public Map<String, Double> syncedValues() {
        Map<String, Double> values = new java.util.TreeMap<>();
        instances.forEach((id, instance) -> {
            if (instance.attribute().synced()) values.put(id.toString(), instance.value());
        });
        return java.util.Collections.unmodifiableMap(values);
    }

    public CompoundTag save() {
        Map<Identifier, AttributeOverrides.Value> saved = new LinkedHashMap<>();
        instances.forEach((id, instance) -> saved.put(id, new AttributeOverrides.Value(
                Optional.of(instance.baseValue()), instance.modifiers().stream()
                        .filter(AttributeModifier::persistent).toList())));
        return new AttributeOverrides(saved).save();
    }

    /** Saved entries replace their modifiers, so constructor defaults are not applied twice. */
    public void restore(CompoundTag tag) {
        AttributeOverrides.restore(tag).values().forEach((id, value) -> {
            if (registry.apply(id) != null) add(id).replace(value);
        });
    }

    public boolean dirty() { return dirty; }
    public void clearDirty() { dirty = false; }

    private EntityAttribute definition(Identifier id) {
        EntityAttribute attribute = registry.apply(id);
        if (attribute == null) throw new IllegalArgumentException("Unknown attribute: " + id);
        if (!attribute.validBounds()) throw new IllegalArgumentException("Invalid attribute bounds: " + id);
        return attribute;
    }
    private void changed(Identifier id) {
        dirty = true;
        changed.accept(id);
    }
}
