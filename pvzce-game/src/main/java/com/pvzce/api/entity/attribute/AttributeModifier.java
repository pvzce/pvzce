package com.pvzce.api.entity.attribute;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.util.Identifier;

/** A named contribution, so an effect can replace or remove its own contribution. */
public record AttributeModifier(Identifier id, double amount, Operation operation, boolean persistent) {
    public AttributeModifier(Identifier id, double amount, Operation operation) {
        this(id, amount, operation, true);
    }

    public AttributeModifier {
        java.util.Objects.requireNonNull(id);
        java.util.Objects.requireNonNull(operation);
        if (!Double.isFinite(amount)) throw new IllegalArgumentException("Modifier amount must be finite");
    }

    public enum Operation {
        ADD_VALUE("add_value"),
        ADD_MULTIPLIED_BASE("add_multiplied_base"),
        ADD_MULTIPLIED_TOTAL("add_multiplied_total");

        private final String id;
        Operation(String id) { this.id = id; }
        public String id() { return id; }

        public static final Codec<Operation> CODEC = Codec.STRING.comapFlatMap(name -> {
            for (Operation operation : values()) {
                if (operation.id.equals(name)) return DataResult.success(operation);
            }
            return DataResult.error(() -> "Unknown attribute operation: " + name);
        }, Operation::id);
    }

    public static final Codec<AttributeModifier> CODEC = RecordCodecBuilder.create(i -> i.group(
            Identifier.CODEC.fieldOf("id").forGetter(AttributeModifier::id),
            EntityAttribute.FINITE_DOUBLE.fieldOf("amount").forGetter(AttributeModifier::amount),
            Operation.CODEC.fieldOf("operation").forGetter(AttributeModifier::operation),
            Codec.BOOL.optionalFieldOf("persistent", true).forGetter(AttributeModifier::persistent)
    ).apply(i, AttributeModifier::new));
}
