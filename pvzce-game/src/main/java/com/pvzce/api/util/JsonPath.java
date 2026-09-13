package com.pvzce.api.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Addresses one node inside a JSON document: {@code "rewards.first_clear[0].id"}.
 *
 * <p>Written for the level editor, which is a set of pages that each own part of one JSON
 * file. Before this existed the editor passed seventeen positional parameters into
 * {@code buildLevelJson} and grew a new overload for every block it learned to edit
 * (rewards, then unlock, then dialogue); each new page meant another parameter, another
 * overload, and another chance to pass two of them in the wrong order. A path names the
 * thing being written instead, so a page can own exactly the fields it edits.
 *
 * <p>Paths are dot-separated object keys with optional {@code [n]} array indices. Setting a
 * path creates the intermediate objects and arrays it needs; getting a path that is not
 * there returns empty rather than failing, because "this file predates that field" is a
 * normal state for level data.
 */
public final class JsonPath {
    /** One step of a path: an object key or an array index. */
    public record Segment(String key, int index, boolean indexed) {
        public static Segment key(String key) {
            return new Segment(key, -1, false);
        }

        public static Segment index(int index) {
            return new Segment("", index, true);
        }
    }

    /** Parses {@code "a.b[0].c"} into its segments. */
    public static List<Segment> parse(String path) {
        List<Segment> segments = new ArrayList<>();
        for (String part : path.split("\\.")) {
            if (part.isEmpty()) {
                continue;
            }
            int bracket = part.indexOf('[');
            if (bracket < 0) {
                segments.add(Segment.key(part));
                continue;
            }
            if (bracket > 0) {
                segments.add(Segment.key(part.substring(0, bracket)));
            }
            int cursor = bracket;
            while (cursor < part.length() && part.charAt(cursor) == '[') {
                int close = part.indexOf(']', cursor);
                if (close < 0) {
                    throw new IllegalArgumentException("Unclosed index in path '" + path + "'");
                }
                segments.add(Segment.index(Integer.parseInt(part.substring(cursor + 1, close).trim())));
                cursor = close + 1;
            }
        }
        return segments;
    }

    /** The node at {@code path}, or empty when any step is missing. */
    public static Optional<JsonElement> get(JsonElement root, String path) {
        JsonElement current = root;
        for (Segment segment : parse(path)) {
            if (current == null || current.isJsonNull()) {
                return Optional.empty();
            }
            if (segment.indexed()) {
                if (!current.isJsonArray()) {
                    return Optional.empty();
                }
                JsonArray array = current.getAsJsonArray();
                if (segment.index() < 0 || segment.index() >= array.size()) {
                    return Optional.empty();
                }
                current = array.get(segment.index());
            } else {
                if (!current.isJsonObject()) {
                    return Optional.empty();
                }
                current = current.getAsJsonObject().get(segment.key());
            }
        }
        return current == null ? Optional.empty() : Optional.of(current);
    }

    /**
     * Writes {@code value} at {@code path}, creating intermediate containers.
     *
     * <p>A container is created as an array when the next segment is an index and as an
     * object otherwise, so {@code "cards[0].id"} on an empty document produces
     * {@code {"cards":[{"id":...}]}}.
     */
    public static void set(JsonObject root, String path, JsonElement value) {
        List<Segment> segments = parse(path);
        if (segments.isEmpty()) {
            throw new IllegalArgumentException("Empty JSON path");
        }
        JsonElement current = root;
        for (int i = 0; i < segments.size() - 1; i++) {
            current = descend(current, segments.get(i), segments.get(i + 1));
        }
        Segment last = segments.get(segments.size() - 1);
        if (last.indexed()) {
            JsonArray array = (JsonArray) current;
            while (array.size() <= last.index()) {
                array.add(com.google.gson.JsonNull.INSTANCE);
            }
            array.set(last.index(), value);
        } else {
            ((JsonObject) current).add(last.key(), value);
        }
    }

    /**
     * Removes the node at {@code path}.
     *
     * @return true when something was there
     */
    public static boolean remove(JsonObject root, String path) {
        List<Segment> segments = parse(path);
        if (segments.isEmpty()) {
            return false;
        }
        JsonElement parent = root;
        for (int i = 0; i < segments.size() - 1; i++) {
            JsonElement next = child(parent, segments.get(i));
            if (next == null) {
                return false;
            }
            parent = next;
        }
        Segment last = segments.get(segments.size() - 1);
        if (last.indexed()) {
            if (!parent.isJsonArray()) {
                return false;
            }
            JsonArray array = parent.getAsJsonArray();
            if (last.index() < 0 || last.index() >= array.size()) {
                return false;
            }
            array.remove(last.index());
            return true;
        }
        if (!parent.isJsonObject()) {
            return false;
        }
        return parent.getAsJsonObject().remove(last.key()) != null;
    }

    /** Convenience for the common "a string field, or absent when blank" idiom. */
    public static JsonElement stringOrNull(String value) {
        return value == null || value.isBlank()
                ? com.google.gson.JsonNull.INSTANCE : new JsonPrimitive(value);
    }

    private static JsonElement child(JsonElement parent, Segment segment) {
        if (parent == null || parent.isJsonNull()) {
            return null;
        }
        if (segment.indexed()) {
            if (!parent.isJsonArray()) {
                return null;
            }
            JsonArray array = parent.getAsJsonArray();
            return segment.index() >= 0 && segment.index() < array.size() ? array.get(segment.index()) : null;
        }
        if (!parent.isJsonObject()) {
            return null;
        }
        return parent.getAsJsonObject().get(segment.key());
    }

    private static JsonElement descend(JsonElement current, Segment segment, Segment next) {
        JsonElement existing = child(current, segment);
        if (existing != null && !existing.isJsonNull()) {
            return existing;
        }
        JsonElement created = next.indexed() ? new JsonArray() : new JsonObject();
        if (segment.indexed()) {
            JsonArray array = (JsonArray) current;
            while (array.size() <= segment.index()) {
                array.add(com.google.gson.JsonNull.INSTANCE);
            }
            array.set(segment.index(), created);
        } else {
            ((JsonObject) current).add(segment.key(), created);
        }
        return created;
    }

    private JsonPath() {
    }
}
