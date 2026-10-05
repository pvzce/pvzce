package com.pvzce.api.entity.attribute;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.common.nbt.ListTag;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Initial values/modifiers declared by content or a spawn entry, and the same shape in saves. */
public record AttributeOverrides(Map<Identifier, Value> values) {
    public static final AttributeOverrides EMPTY = new AttributeOverrides(Map.of());

    public record Value(Optional<Double> base, List<AttributeModifier> modifiers) {
        public Value {
            base = base == null ? Optional.empty() : base;
            modifiers = List.copyOf(modifiers);
        }
        private static final Codec<Value> OBJECT_CODEC = RecordCodecBuilder.<Value>create(i -> i.group(
                EntityAttribute.FINITE_DOUBLE.optionalFieldOf("base").forGetter(Value::base),
                AttributeModifier.CODEC.listOf().optionalFieldOf("modifiers", List.of()).forGetter(Value::modifiers)
        ).apply(i, Value::new)).validate(value -> value.modifiers.stream().map(AttributeModifier::id).distinct().count()
                == value.modifiers.size() ? DataResult.success(value)
                : DataResult.error(() -> "Duplicate attribute modifier id"));
        public static final Codec<Value> CODEC = Codec.either(EntityAttribute.FINITE_DOUBLE, OBJECT_CODEC)
                .xmap(either -> either.map(number -> new Value(Optional.of(number), List.of()), value -> value),
                        value -> value.base.isPresent() && value.modifiers.isEmpty()
                                ? Either.left(value.base.get()) : Either.right(value));
    }

    public static final Codec<AttributeOverrides> CODEC = Codec.unboundedMap(Identifier.CODEC, Value.CODEC)
            .xmap(AttributeOverrides::new, AttributeOverrides::values);

    public AttributeOverrides { values = Map.copyOf(values); }

    public List<String> validate(java.util.function.Function<Identifier, EntityAttribute> registry) {
        java.util.ArrayList<String> errors = new java.util.ArrayList<>();
        for (Identifier id : values.keySet()) {
            EntityAttribute attribute = registry.apply(id);
            if (attribute == null) errors.add("Unknown attribute '" + id + "'");
            else if (!attribute.validBounds()) errors.add("Invalid attribute bounds '" + id + "'");
        }
        return errors;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        values.entrySet().stream().sorted(Map.Entry.comparingByKey(
                java.util.Comparator.comparing(Identifier::toString))).forEach(entry -> {
            CompoundTag value = new CompoundTag();
            entry.getValue().base().ifPresent(base -> value.putDouble("Base", base));
            ListTag modifiers = new ListTag();
            for (AttributeModifier modifier : entry.getValue().modifiers()) {
                CompoundTag saved = new CompoundTag();
                saved.putString("Id", modifier.id().toString());
                saved.putDouble("Amount", modifier.amount());
                saved.putString("Operation", modifier.operation().id());
                saved.putByte("Persistent", (byte) (modifier.persistent() ? 1 : 0));
                modifiers.add(saved);
            }
            value.put("Modifiers", modifiers);
            tag.put(entry.getKey().toString(), value);
        });
        return tag;
    }

    public static AttributeOverrides restore(CompoundTag tag) {
        Map<Identifier, Value> values = new LinkedHashMap<>();
        for (var entry : tag.entries().entrySet()) {
            Identifier id = Identifier.tryParse(entry.getKey());
            if (id == null || !(entry.getValue() instanceof CompoundTag value)) continue;
            Optional<Double> base = value.contains("Base") ? Optional.of(value.getDouble("Base")) : Optional.empty();
            if (base.isPresent() && !Double.isFinite(base.get())) continue;
            java.util.ArrayList<AttributeModifier> modifiers = new java.util.ArrayList<>();
            for (var element : value.getList("Modifiers").values()) {
                if (!(element instanceof CompoundTag saved)) continue;
                Identifier modifierId = Identifier.tryParse(saved.getString("Id"));
                double amount = saved.getDouble("Amount");
                if (modifierId == null || !Double.isFinite(amount)) continue;
                for (AttributeModifier.Operation operation : AttributeModifier.Operation.values()) {
                    if (operation.id().equals(saved.getString("Operation"))) {
                        modifiers.add(new AttributeModifier(modifierId, amount, operation,
                                !saved.contains("Persistent") || saved.getInt("Persistent") != 0));
                        break;
                    }
                }
            }
            values.put(id, new Value(base, modifiers));
        }
        return new AttributeOverrides(values);
    }
}
