package com.pvzce.api.entity.attribute;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.util.Identifier;

/** Registered numeric property. Its definition is shared; values belong to individual entities. */
public record EntityAttribute(Identifier id, double defaultValue, double minValue,
                              double maxValue, boolean synced) {
    public static final Codec<Double> FINITE_DOUBLE = Codec.DOUBLE.validate(value ->
            Double.isFinite(value) ? DataResult.success(value)
                    : DataResult.error(() -> "Attribute numbers must be finite"));

    public static final Codec<EntityAttribute> CODEC = RecordCodecBuilder.<EntityAttribute>create(i -> i.group(
            Identifier.CODEC.fieldOf("id").forGetter(EntityAttribute::id),
            FINITE_DOUBLE.fieldOf("default").forGetter(EntityAttribute::defaultValue),
            FINITE_DOUBLE.fieldOf("min").forGetter(EntityAttribute::minValue),
            FINITE_DOUBLE.fieldOf("max").forGetter(EntityAttribute::maxValue),
            Codec.BOOL.optionalFieldOf("synced", true).forGetter(EntityAttribute::synced)
    ).apply(i, EntityAttribute::new)).validate(attribute -> attribute.validBounds()
            ? DataResult.success(attribute)
            : DataResult.error(() -> "Attribute requires min <= default <= max: " + attribute.id()));

    public EntityAttribute {
        java.util.Objects.requireNonNull(id);
    }

    public boolean validBounds() {
        return Double.isFinite(defaultValue) && Double.isFinite(minValue) && Double.isFinite(maxValue)
                && minValue <= defaultValue && defaultValue <= maxValue;
    }

    public double clamp(double value) {
        if (Double.isNaN(value)) throw new IllegalArgumentException("Undefined attribute value: " + id);
        return Math.max(minValue, Math.min(maxValue, value));
    }
}
