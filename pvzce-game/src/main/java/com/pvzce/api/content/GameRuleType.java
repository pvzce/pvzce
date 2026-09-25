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
        GameRuleType.FloatRule, GameRuleType.EnumRule {
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

    /**
     * True when the value is a name from a fixed list rather than a number.
     *
     * <p>Asked by the editor, which draws a slider for the numeric rules and a choice row for
     * these; asking "is it numeric" by elimination would put a range slider on a word.
     */
    default boolean named() {
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

    /**
     * A rule whose value is one of a fixed list of names.
     *
     * <p>Not "numeric": the editor draws a choice row for it rather than a slider, and the value
     * travels as a string in JSON and over the wire. {@link #options()} is what the editor reads
     * to build that row - a rule type cannot enumerate an arbitrary {@code T}, so the caller
     * supplies the names alongside the codec that parses them.
     */
    final class EnumRule<T> implements GameRuleType<T> {
        private final T value;
        private final Codec<T> codec;
        private final java.util.List<String> options;

        /**
         * A rule whose values are a fixed vocabulary written by name.
         *
         * @param value the value a level that does not say gets, and what an unrecognised one
         *              falls back to
         * @param codec the vocabulary's codec; a rule type cannot enumerate an arbitrary
         *              {@code T}, so the caller passes the codec that already knows how to parse
         *              it - {@code MutationDifficulty.CODEC}, for instance
         */
        public EnumRule(T value, Codec<T> codec) {
            this(value, codec, java.util.List.of());
        }

        /**
         * @param options the names the editor offers, in the order they should be listed; empty
         *                means the rule is edited as free text
         */
        public EnumRule(T value, Codec<T> codec, java.util.List<String> options) {
            this.value = value;
            this.codec = codec;
            this.options = options == null ? java.util.List.of() : java.util.List.copyOf(options);
        }

        /** The names this rule accepts, for the editor's choice row. */
        public java.util.List<String> options() {
            return options;
        }

        @Override
        public boolean named() {
            return true;
        }

        @Override
        public T defaultValue() {
            return value;
        }

        @Override
        public Codec<T> codec() {
            return codec;
        }

        @Override
        public T clamp(T value) {
            return value == null ? this.value : value;
        }
    }

}
