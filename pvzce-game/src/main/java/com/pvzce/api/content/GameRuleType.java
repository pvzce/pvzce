package com.pvzce.api.content;

import com.mojang.serialization.Codec;

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
        GameRuleType.FloatRule {
    T defaultValue();

    Codec<T> codec();

    T clamp(T value);

    /**
     * The inclusive bounds {@link #clamp} enforces, or {@code null} for a type with none.
     *
     * <p>Exposed because the level editor has to offer a range the server will not silently
     * clamp: it kept a hand-written copy of these numbers, and the copy had already drifted
     * ({@code sun_value} was editable up to 500 while the server accepted 10000, so a level
     * could not express a value the game allows). One registration, one range.
     */
    default float[] bounds() {
        return null;
    }

    /** True when only whole numbers are meaningful - a tick count, a player count. */
    default boolean integral() {
        return false;
    }

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

        @Override
        public float[] bounds() {
            return new float[]{min, max};
        }

        @Override
        public boolean integral() {
            return true;
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

        @Override
        public float[] bounds() {
            return new float[]{min, max};
        }
    }

}
