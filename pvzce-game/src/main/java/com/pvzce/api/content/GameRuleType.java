package com.pvzce.api.content;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;

/**
 * A registered game-rule type, mirroring Minecraft's
 * {@code GameRules.Type} + Fabric GameRuleBuilder in a single sealed shape.
 * Values are level-scoped and may be overridden by level JSON or /gamerule.
 *
 * <p>Concrete rules are classes rather than records because a primitive
 * record accessor cannot implement a generic {@code T defaultValue()} method
 * (the generic method erases to {@code Object}).
 */
public sealed interface GameRuleType<T> permits GameRuleType.BooleanRule, GameRuleType.IntRule,
        GameRuleType.FloatRule, GameRuleType.DoubleRule, GameRuleType.EnumRule {
    T defaultValue();

    Codec<T> codec();

    T clamp(T value);

    final class BooleanRule implements GameRuleType<Boolean> {
        private final Boolean value;

        public BooleanRule(boolean value) {
            this.value = value;
        }

        @Override
        public Boolean defaultValue() {
            return value;
        }

        @Override
        public Codec<Boolean> codec() {
            return Codec.BOOL;
        }

        @Override
        public Boolean clamp(Boolean value) {
            return value == null ? this.value : value;
        }
    }

    final class IntRule implements GameRuleType<Integer> {
        private final Integer value;
        private final int min;
        private final int max;

        public IntRule(int value, int min, int max) {
            this.value = value;
            this.min = min;
            this.max = max;
        }

        @Override
        public Integer defaultValue() {
            return value;
        }

        @Override
        public Codec<Integer> codec() {
            return Codec.INT;
        }

        @Override
        public Integer clamp(Integer value) {
            return value == null ? this.value : Math.max(min, Math.min(max, value));
        }
    }

    final class FloatRule implements GameRuleType<Float> {
        private final Float value;
        private final float min;
        private final float max;

        public FloatRule(float value, float min, float max) {
            this.value = value;
            this.min = min;
            this.max = max;
        }

        @Override
        public Float defaultValue() {
            return value;
        }

        @Override
        public Codec<Float> codec() {
            return Codec.FLOAT;
        }

        @Override
        public Float clamp(Float value) {
            return value == null ? this.value : Math.max(min, Math.min(max, value));
        }
    }

    final class DoubleRule implements GameRuleType<Double> {
        private final Double value;
        private final double min;
        private final double max;

        public DoubleRule(double value, double min, double max) {
            this.value = value;
            this.min = min;
            this.max = max;
        }

        @Override
        public Double defaultValue() {
            return value;
        }

        @Override
        public Codec<Double> codec() {
            return Codec.DOUBLE;
        }

        @Override
        public Double clamp(Double value) {
            return value == null ? this.value : Math.max(min, Math.min(max, value));
        }
    }

    final class EnumRule<E extends Enum<E>> implements GameRuleType<E> {
        private final Class<E> enumClass;
        private final E value;

        public EnumRule(Class<E> enumClass, E value) {
            this.enumClass = enumClass;
            this.value = value;
        }

        @Override
        public E defaultValue() {
            return value;
        }

        @Override
        public Codec<E> codec() {
            return Codec.STRING.flatXmap(name -> {
                try {
                    return DataResult.success(Enum.valueOf(enumClass, name));
                } catch (IllegalArgumentException e) {
                    return DataResult.error(() -> "Unknown enum constant " + name + " for " + enumClass.getSimpleName());
                }
            }, v -> DataResult.success(v.name()));
        }

        @Override
        public E clamp(E value) {
            return value == null ? this.value : value;
        }
    }
}
