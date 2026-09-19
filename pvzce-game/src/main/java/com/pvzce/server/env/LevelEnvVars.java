package com.pvzce.server.env;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import com.pvzce.api.content.EnvValue;
import com.pvzce.api.content.EnvVarType;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.core.BuiltInRegistries;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Level-scoped typed key/value channel. Values are validated by the
 * registered EnvVarType; components may subscribe to changes.
 */
public final class LevelEnvVars {
    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger("PVZCE/EnvVars");
    public interface EnvChangeListener {
        void onChange(Identifier name, EnvValue oldValue, EnvValue newValue);
    }

    private final Map<Identifier, EnvValue> values = new LinkedHashMap<>();
    private final Map<Identifier, List<EnvChangeListener>> listeners = new LinkedHashMap<>();

    public LevelEnvVars(Map<Identifier, EnvValue> injected) {
        if (injected != null) {
            values.putAll(injected);
        }
    }

    public boolean has(Identifier name) {
        return values.containsKey(name);
    }

    public JsonElement getJson(Identifier name) {
        EnvValue value = values.get(name);
        return value == null ? null : value.value();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public <T> T get(Identifier typeId, Identifier name, T fallback) {
        EnvValue value = values.get(name);
        if (value == null) {
            return fallback;
        }
        EnvVarType type = BuiltInRegistries.ENV_VAR_TYPES.get(typeId);
        if (type == null) {
            return fallback;
        }
        try {
            return (T) type.codec().parse(JsonOps.INSTANCE, value.value()).getOrThrow();
        } catch (RuntimeException e) {
            // A malformed value is a data problem, but this is read on hot paths (plant AI
            // decisions, per-content lookups), so it is a debug line and not a warning.
            LOGGER.debug("Env var {} has a value its type cannot decode; using the fallback",
                    name, e);
            return fallback;
        }
    }

    public int getInt(Identifier name, int fallback) {
        return get(Identifier.withDefaultNamespace("int"), name, fallback);
    }

    public float getFloat(Identifier name, float fallback) {
        return get(Identifier.withDefaultNamespace("float"), name, fallback);
    }

    public boolean getBoolean(Identifier name, boolean fallback) {
        return get(Identifier.withDefaultNamespace("boolean"), name, fallback);
    }

    public String getString(Identifier name, String fallback) {
        return get(Identifier.withDefaultNamespace("string"), name, fallback);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public boolean set(Identifier typeId, Identifier name, Object value) {
        EnvVarType type = BuiltInRegistries.ENV_VAR_TYPES.get(typeId);
        if (type == null) {
            return false;
        }
        JsonElement json = (JsonElement) (Object) type.codec().encodeStart(JsonOps.INSTANCE, value).getOrThrow();
        return setJson(typeId, name, json);
    }

    public boolean setJson(Identifier typeId, Identifier name, JsonElement json) {
        EnvValue old = values.put(name, new EnvValue(typeId, json));
        List<EnvChangeListener> list = listeners.get(name);
        if (list != null) {
            for (EnvChangeListener listener : list) {
                listener.onChange(name, old, values.get(name));
            }
        }
        return true;
    }

    public void listen(Identifier name, EnvChangeListener listener) {
        listeners.computeIfAbsent(name, ignored -> new ArrayList<>()).add(listener);
    }

    public Map<Identifier, EnvValue> values() {
        return Map.copyOf(values);
    }
}
