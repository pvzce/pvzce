package com.pvzce.common.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.pvzce.api.util.JsonPath;

import java.util.List;
import java.util.Optional;

/**
 * The level editor's working copy of a level file.
 *
 * <p>One JSON tree, loaded once and edited by path: a page writes the fields it owns and
 * nothing else, so a field the editor does not model - a newer version's block, another
 * tool's metadata, the level's teams - survives an edit by construction rather than by
 * remembering to copy it across. That property used to depend on a seventeen-parameter
 * {@code buildLevelJson} that every page had to be threaded through; this makes it a
 * property of the data structure.
 *
 * <p>Pure and screen-free, so the merge rules are testable without a client: the editor's
 * JSON behaviour is the thing most likely to silently destroy someone's level file.
 */
public final class JsonDraft {
    private final JsonObject root;

    private JsonDraft(JsonObject root) {
        this.root = root;
    }

    /** A draft over a copy of {@code loaded}; a null or non-object input starts an empty file. */
    public static JsonDraft of(JsonElement loaded) {
        JsonObject copy = loaded != null && loaded.isJsonObject()
                ? loaded.getAsJsonObject().deepCopy() : new JsonObject();
        return new JsonDraft(copy);
    }

    public static JsonDraft empty() {
        return new JsonDraft(new JsonObject());
    }

    public JsonObject json() {
        return root;
    }

    public boolean has(String path) {
        return JsonPath.get(root, path).isPresent();
    }

    public Optional<JsonElement> get(String path) {
        return JsonPath.get(root, path);
    }

    /** The string at {@code path}, or {@code fallback} when it is missing or not a string. */
    public String getString(String path, String fallback) {
        Optional<JsonElement> value = get(path);
        return value.isPresent() && value.get().isJsonPrimitive()
                ? value.get().getAsString() : fallback;
    }

    public int getInt(String path, int fallback) {
        Optional<JsonElement> value = get(path);
        return value.isPresent() && value.get().isJsonPrimitive()
                && value.get().getAsJsonPrimitive().isNumber() ? value.get().getAsInt() : fallback;
    }

    /** The array at {@code path}, or an empty one when the node is missing or not an array. */
    public JsonArray getArray(String path) {
        Optional<JsonElement> value = get(path);
        return value.isPresent() && value.get().isJsonArray()
                ? value.get().getAsJsonArray() : new JsonArray();
    }

    public void set(String path, JsonElement value) {
        JsonPath.set(root, path, value == null ? com.google.gson.JsonNull.INSTANCE : value);
    }

    public void setString(String path, String value) {
        set(path, new JsonPrimitive(value == null ? "" : value));
    }

    /**
     * Sets a string, or removes the key when the value is blank.
     *
     * <p>"Not set" and "set to an empty string" must not both be representable for the
     * fields that mean nothing when empty - a level with an empty description is a level
     * without a description, and the file should say so once.
     */
    public void setStringOrRemove(String path, String value) {
        if (value == null || value.isBlank()) {
            remove(path);
        } else {
            setString(path, value);
        }
    }

    public void setInt(String path, int value) {
        set(path, new JsonPrimitive(value));
    }

    public void setFloat(String path, float value) {
        set(path, new JsonPrimitive(value));
    }

    public void setBool(String path, boolean value) {
        set(path, new JsonPrimitive(value));
    }

    public void setArray(String path, List<JsonElement> values) {
        JsonArray array = new JsonArray();
        for (JsonElement value : values) {
            array.add(value);
        }
        set(path, array);
    }

    public boolean remove(String path) {
        return JsonPath.remove(root, path);
    }

    /** Removes {@code path} when the array there is empty, so "none" has one spelling. */
    public void removeIfEmpty(String path) {
        Optional<JsonElement> value = get(path);
        boolean empty = value.isEmpty() || value.get().isJsonNull()
                || (value.get().isJsonArray() && value.get().getAsJsonArray().isEmpty());
        if (empty) {
            remove(path);
        }
    }

    /** A string id list as JSON, in order, with duplicates dropped. */
    public static JsonArray idArray(List<String> ids) {
        JsonArray array = new JsonArray();
        for (String id : new java.util.LinkedHashSet<>(ids)) {
            array.add(id);
        }
        return array;
    }
}
