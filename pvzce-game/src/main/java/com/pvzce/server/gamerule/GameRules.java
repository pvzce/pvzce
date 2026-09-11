package com.pvzce.server.gamerule;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import com.pvzce.api.content.GameRuleType;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.core.BuiltInRegistries;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Level-scoped game-rule value table. The registered {@link GameRuleType}
 * supplies Codec + clamping; values are seeded from LevelDef JSON and may be
 * changed at runtime (M4 wires the /gamerule command and broadcast).
 */
public final class GameRules implements com.pvzce.api.entity.LevelAccess.GameRuleAccess {
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

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static JsonElement encode(GameRuleType type, Object value) {
        return (JsonElement) (Object) type.codec().encodeStart(JsonOps.INSTANCE, value).getOrThrow();
    }

    public Map<Identifier, Object> values() {
        return Map.copyOf(values);
    }
}
