package com.pvzce.server.gamerule;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import com.pvzce.api.content.GameRuleType;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.nbt.CompoundTag;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Level-scoped game-rule value table. The registered {@link GameRuleType}
 * supplies Codec + clamping; values are seeded from LevelDef JSON and may be
 * changed at runtime (M4 wires the /gamerule command and broadcast).
 */
public final class GameRules implements com.pvzce.api.entity.LevelAccess.GameRuleAccess {
    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger("PVZCE/GameRules");
    private final Map<Identifier, Object> values = new LinkedHashMap<>();

    public GameRules(Map<Identifier, JsonElement> overrides) {
        for (Identifier id : BuiltInRegistries.GAME_RULES.keySet()) {
            GameRuleType<?> type = BuiltInRegistries.GAME_RULES.get(id);
            if (type != null) {
                values.put(id, type.defaultValue());
            }
        }
        if (overrides != null) {
            overrides.forEach(this::setFromJson);
        }
    }

    /**
     * Applies one JSON override.
     *
     * <p>A malformed value used to throw straight out of the {@code LevelServer}
     * constructor, so one typo in a level's {@code rules} block made the level
     * unloadable with no indication of which rule was wrong - while an unknown rule
     * id was dropped silently and a bad {@code env_vars} value silently returned the
     * caller's fallback. Everything is now reported through
     * {@link #validate} instead of throwing, and unknown ids are reported too.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public boolean setFromJson(Identifier id, JsonElement json) {
        GameRuleType type = BuiltInRegistries.GAME_RULES.get(id);
        if (type == null) {
            return false;
        }
        var result = type.codec().parse(JsonOps.INSTANCE, json);
        if (result.error().isPresent()) {
            return false;
        }
        values.put(id, type.clamp(result.getOrThrow()));
        return true;
    }

    /** Validates a level's rule overrides, returning one message per problem. */
    public static java.util.List<String> validate(Map<Identifier, JsonElement> overrides) {
        java.util.List<String> errors = new java.util.ArrayList<>();
        if (overrides == null) {
            return errors;
        }
        for (Map.Entry<Identifier, JsonElement> entry : overrides.entrySet()) {
            GameRuleType<?> type = BuiltInRegistries.GAME_RULES.get(entry.getKey());
            if (type == null) {
                errors.add("Unknown game rule '" + entry.getKey() + "'");
                continue;
            }
            var result = type.codec().parse(JsonOps.INSTANCE, entry.getValue());
            if (result.error().isPresent()) {
                errors.add("Invalid value for rule '" + entry.getKey() + "': "
                        + result.error().get().message());
            }
        }
        return errors;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public boolean set(Identifier id, Object value) {
        GameRuleType type = BuiltInRegistries.GAME_RULES.get(id);
        if (type == null) {
            return false;
        }
        values.put(id, type.clamp(value));
        return true;
    }

    public int getInt(Identifier id) {
        Object value = values.get(id);
        if (value instanceof Number number) {
            return number.intValue();
        }
        GameRuleType<?> type = BuiltInRegistries.GAME_RULES.get(id);
        return type != null && type.defaultValue() instanceof Number number ? number.intValue() : 0;
    }

    public float getFloat(Identifier id) {
        Object value = values.get(id);
        if (value instanceof Number number) {
            return number.floatValue();
        }
        GameRuleType<?> type = BuiltInRegistries.GAME_RULES.get(id);
        return type != null && type.defaultValue() instanceof Number number ? number.floatValue() : 0F;
    }

    public boolean getBoolean(Identifier id) {
        Object value = values.get(id);
        if (value instanceof Boolean bool) {
            return bool;
        }
        GameRuleType<?> type = BuiltInRegistries.GAME_RULES.get(id);
        return type != null && type.defaultValue() instanceof Boolean bool ? bool : false;
    }

    /**
     * A rule whose vocabulary is a fixed set of names, decoded leniently.
     *
     * <p>Deliberately not {@code values.get(id)} cast: the only enum-valued rule is
     * {@code mutation_difficulty}, and the number of ways it can arrive as something other than
     * its own type is not zero - a level JSON writes a string, {@code /gamerule} comes through
     * the command parser, and a hand-edited save can hold anything at all. Anything that is not
     * the expected type falls back to the type's default, which is the same policy
     * {@link #getInt} and {@link #getBoolean} already follow.
     */
    @SuppressWarnings("unchecked")
    public <T> T getEnum(Identifier id, java.util.function.Function<String, T> parse) {
        GameRuleType<?> type = BuiltInRegistries.GAME_RULES.get(id);
        T fallback = type == null ? null : (T) type.defaultValue();
        Object value = values.get(id);
        if (value == null) {
            return fallback;
        }
        if (type != null && type.defaultValue() != null
                && type.defaultValue().getClass().isInstance(value)) {
            return (T) value;
        }
        T parsed = parse.apply(String.valueOf(value));
        return parsed == null ? fallback : parsed;
    }

    public Map<Identifier, JsonElement> toJson() {
        Map<Identifier, JsonElement> result = new LinkedHashMap<>();
        for (Map.Entry<Identifier, Object> entry : values.entrySet()) {
            GameRuleType<?> type = BuiltInRegistries.GAME_RULES.get(entry.getKey());
            if (type == null) {
                continue;
            }
            result.put(entry.getKey(), encode(type, entry.getValue()));
        }
        return result;
    }

    /**
     * The live values as an NBT block, for a save.
     *
     * <p>Every value is written as the JSON text its own codec produces, so the write and the read
     * are one declaration (the rule's codec) rather than a chain of {@code instanceof} checks that
     * has to grow with every rule type. The key is the rule id in full, so a pack's own rule
     * round-trips without this class having heard of it.
     *
     * @param key the tag's own name, for the block's header
     */
    public CompoundTag toNbt(String key) {
        return toNbt(key, Map.of());
    }

    /**
     * The same, with some rules written at a caller-supplied value instead.
     *
     * <p>For the save path: a mutation's factor has to be kept out of the file (see
     * {@code MutationManager.rulesForSave}), and the alternative - unwinding and rewinding the
     * live rules around the write - is a window in which the running level sees the wrong value.
     * A rule the map does not mention is written as it is.
     *
     * @param overrides the values to write in place of the live ones
     */
    public CompoundTag toNbt(String key, Map<Identifier, Float> overrides) {
        CompoundTag tag = new CompoundTag();
        for (Map.Entry<Identifier, Object> entry : values.entrySet()) {
            GameRuleType<?> type = BuiltInRegistries.GAME_RULES.get(entry.getKey());
            if (type == null) {
                continue;
            }
            // The JSON *text*, not `getAsString()`: writing the string form turned a number into
            // "360000", and parsing that back as JSON gives a string where the rule's codec wants
            // an int - so every numeric rule silently reverted to its default on load. Numbers,
            // booleans and names all round-trip through their own JSON spelling.
            Object value = entry.getValue();
            Float override = overrides.get(entry.getKey());
            if (override != null && value instanceof Number) {
                // Only the types that can hold the number take it: an override is a float, and
                // handing one to an int rule is a ClassCastException out of the rule's own clamp.
                // A rule the caller cannot express this way keeps the value it is playing with.
                Object clamped = clampOrNull(type, override);
                if (clamped != null) {
                    value = clamped;
                }
            }
            tag.putString(entry.getKey().toString(), encode(type, value).toString());
        }
        return tag;
    }

    /**
     * Reads back what {@link #toNbt} wrote.
     *
     * <p>Runs over the <em>registered</em> rules rather than over the tag, so a save that names a
     * rule this build does not have (a pack was removed) leaves that rule at its default instead
     * of throwing, and a rule the save predates keeps the value the definition gave it.
     */
    public void applySaved(CompoundTag tag) {
        if (tag == null) {
            return;
        }
        for (Map.Entry<String, com.pvzce.common.nbt.Tag> entry : tag.entries().entrySet()) {
            Identifier id = Identifier.tryParse(entry.getKey());
            if (id == null || !(entry.getValue() instanceof com.pvzce.common.nbt.StringTag value)) {
                continue;
            }
            try {
                setFromJson(id, com.google.gson.JsonParser.parseString(value.value()));
            } catch (RuntimeException e) {
                // A hand-edited or truncated value is a data problem, not a reason to refuse the
                // save: the rule keeps what the level definition gave it and the rest of the file
                // is still read. Reported once per load rather than per rule.
                LOGGER.warn("Saved value for rule '{}' is not valid JSON; keeping the default",
                        id);
            }
        }
    }

    /**
     * One clamp through the type's own domain, or {@code null} when the type cannot hold a float.
     *
     * <p>The types are heterogeneous by design (an int rule, a float rule, a boolean), and the
     * only caller here has a number from a mutation's roll. Asking the type is cheaper than
     * enumerating which kinds accept one - and the answer has to come from the type, since its
     * domain is its own business.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object clampOrNull(GameRuleType type, float value) {
        try {
            return type.clamp(value);
        } catch (ClassCastException notANumberRule) {
            return null;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static JsonElement encode(GameRuleType type, Object value) {
        return (JsonElement) (Object) type.codec().encodeStart(JsonOps.INSTANCE, value).getOrThrow();
    }

    /**
     * Every rule whose value is a number, as an unmodifiable view.
     *
     * <p>What the save path needs to hand to the things that own a share of a rule: a mutation
     * scales a float, and the file has to carry the value without that scaling.
     */
    public Map<Identifier, Float> floatView() {
        Map<Identifier, Float> floats = new java.util.LinkedHashMap<>();
        for (Map.Entry<Identifier, Object> entry : values.entrySet()) {
            if (entry.getValue() instanceof Number number) {
                floats.put(entry.getKey(), number.floatValue());
            }
        }
        return java.util.Map.copyOf(floats);
    }

    public Map<Identifier, Object> values() {
        return Map.copyOf(values);
    }
}
