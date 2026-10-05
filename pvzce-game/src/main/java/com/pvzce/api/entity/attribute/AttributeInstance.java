package com.pvzce.api.entity.attribute;

import com.pvzce.api.util.Identifier;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Mutable server-side value for one registered attribute on one entity. */
public final class AttributeInstance {
    private final EntityAttribute attribute;
    private final Runnable changed;
    private double baseValue;
    // Identifier order makes floating point evaluation independent of insertion and save order.
    private final java.util.NavigableMap<Identifier, AttributeModifier> modifiers = new TreeMap<>(
            java.util.Comparator.comparing(Identifier::toString));

    AttributeInstance(EntityAttribute attribute, double baseValue, Runnable changed) {
        this.attribute = attribute;
        this.changed = changed;
        requireFinite(baseValue);
        this.baseValue = baseValue;
    }

    public EntityAttribute attribute() { return attribute; }
    public double baseValue() { return baseValue; }
    public List<AttributeModifier> modifiers() { return List.copyOf(modifiers.values()); }

    public double value() { return calculate(baseValue, modifiers); }

    public void setBaseValue(double baseValue) {
        requireFinite(baseValue);
        if (this.baseValue == baseValue) return;
        calculate(baseValue, modifiers);
        this.baseValue = baseValue;
        changed.run();
    }

    /** Replaces the modifier with this id. Reapplying one effect does not stack it twice. */
    public void setModifier(AttributeModifier modifier) {
        if (modifier.equals(modifiers.get(modifier.id()))) return;
        Map<Identifier, AttributeModifier> next = new TreeMap<>(modifiers);
        next.put(modifier.id(), modifier);
        calculate(baseValue, next);
        modifiers.put(modifier.id(), modifier);
        changed.run();
    }

    public boolean removeModifier(Identifier id) {
        if (modifiers.remove(id) == null) return false;
        changed.run();
        return true;
    }

    void replace(AttributeOverrides.Value value) {
        double base = value.base().orElse(attribute.defaultValue());
        requireFinite(base);
        Map<Identifier, AttributeModifier> next = new TreeMap<>(modifiers.comparator());
        for (AttributeModifier modifier : value.modifiers()) next.put(modifier.id(), modifier);
        calculate(base, next);
        baseValue = base;
        modifiers.clear();
        modifiers.putAll(next);
        changed.run();
    }

    private double calculate(double base, Map<Identifier, AttributeModifier> modifiers) {
        double added = base;
        for (AttributeModifier modifier : modifiers.values()) {
            if (modifier.operation() == AttributeModifier.Operation.ADD_VALUE) added += modifier.amount();
        }
        double result = added;
        for (AttributeModifier modifier : modifiers.values()) {
            if (modifier.operation() == AttributeModifier.Operation.ADD_MULTIPLIED_BASE)
                result += added * modifier.amount();
        }
        for (AttributeModifier modifier : modifiers.values()) {
            if (modifier.operation() == AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL)
                result *= 1D + modifier.amount();
        }
        return attribute.clamp(result);
    }

    private static void requireFinite(double value) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Attribute base must be finite");
    }
}
