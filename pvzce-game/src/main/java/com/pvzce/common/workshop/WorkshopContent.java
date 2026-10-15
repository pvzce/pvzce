package com.pvzce.common.workshop;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.resource.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Uses the runtime codecs without registering a draft in the live registries. */
public final class WorkshopContent {
    private WorkshopContent() {}
    public static List<String> types() {
        List<String> types = new ArrayList<>();
        for (var data : PvzceDataLoader.CONTENT_REGISTRIES) types.add(data.contentPath());
        types.addAll(List.of("tags", "textures", "sounds", "animations", "lang"));
        return List.copyOf(types);
    }
    /** Private registries make validation and editor palettes safe for unpublished definitions. */
    public static com.pvzce.api.registry.RegistryAccess catalog(Workspace workspace) { return catalog(workspace, true); }

    public static com.pvzce.api.registry.RegistryAccess catalog(Workspace workspace, boolean strict) {
        var access = BuiltInRegistries.ACCESS.copyForReload();
        try (var resources = new PvzceResourceManager(WorkshopContent.class.getClassLoader())) {
            resources.workspaceOverlay(workspace.root());
            resources.init(workspace.root().getParent().getParent());
            List<String> errors = new ArrayList<>(new PvzceDataLoader().load(resources, access).errors());
            errors.addAll(new com.pvzce.common.tag.TagManager().reload(resources, access).errors());
            if (strict && !errors.isEmpty()) throw new IllegalArgumentException(String.join("\n", errors));
        } catch (IOException e) { throw new IllegalArgumentException(e); }
        return access;
    }

    public static void validate(Workspace workspace) {
        List<String> errors = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        try {
            for (String path : workspace.files()) {
                if (!path.endsWith(".json")) continue;
                try {
                    JsonElement value = JsonParser.parseString(Files.readString(workspace.file(path)));
                    String[] parts = path.split("/", 4);
                    if (parts.length < 4 || !parts[0].equals("data")) continue;
                    if (parts[2].equals("tags")) {
                        JsonArray values = value.getAsJsonObject().getAsJsonArray("values");
                        if (values == null) throw new IllegalArgumentException("Tag requires a values array");
                        for (JsonElement entry : values) {
                            String id = entry.isJsonObject() ? entry.getAsJsonObject().get("id").getAsString() : entry.getAsString();
                            Identifier.parse(id.startsWith("#") ? id.substring(1) : id);
                        }
                        continue;
                    }
                    var data = PvzceDataLoader.CONTENT_REGISTRIES.stream()
                            .filter(d -> d.contentPath().equals(parts[2])).findFirst()
                            .orElseThrow(() -> new IllegalArgumentException("Unknown content directory: " + parts[2]));
                    JsonObject json = value.getAsJsonObject().deepCopy();
                    String inferred = parts[1] + ":" + parts[3].substring(0, parts[3].length() - 5);
                    if (!json.has("id")) json.addProperty("id", inferred);
                    String id = Identifier.parse(json.get("id").getAsString()).toString();
                    if (!ids.add(parts[2] + "/" + id)) throw new IllegalArgumentException("Duplicate content ID: " + id);
                    var result = data.codec().parse(JsonOps.INSTANCE, json);
                    if (result.error().isPresent()) throw new IllegalArgumentException(result.error().get().message());
                } catch (Exception e) { errors.add(path + ": " + e.getMessage()); }
            }
        } catch (IOException e) { errors.add(e.getMessage()); }
        if (!errors.isEmpty()) throw new IllegalArgumentException(String.join("\n", errors));
        catalog(workspace);
    }

    public static String path(Workspace workspace, String type, String id) {
        Identifier key = Identifier.parse(id.contains(":") ? id : workspace.namespace() + ":" + id);
        return (List.of("textures", "sounds", "animations", "lang").contains(type) ? "assets/" : "data/")
                + key.namespace() + "/" + type + "/" + key.path()
                + (type.equals("textures") ? ".png" : type.equals("sounds") ? ".ogg" : ".json");
    }

    /** Available templates are the definitions the game actually loads, including installed mods. */
    public static Map<String, JsonObject> templates(PvzceResourceManager resources, String type) throws IOException {
        Map<String, JsonObject> result = new TreeMap<>();
        for (var entry : resources.listResources("data").entrySet()) {
            String[] parts = entry.getKey().split("/", 4);
            if (parts.length != 4 || !parts[2].equals(type) || !parts[3].endsWith(".json")) continue;
            JsonObject json = JsonParser.parseString(entry.getValue().readString()).getAsJsonObject();
            String id = json.has("id") ? json.get("id").getAsString()
                    : parts[1] + ":" + parts[3].substring(0, parts[3].length() - 5);
            result.put(id, json);
        }
        return result;
    }

    public static JsonObject blank(String type, Identifier id) {
        JsonObject json = new JsonObject();
        if (type.equals("tags")) { json.addProperty("replace", false); json.add("values", new JsonArray()); }
        else if (type.equals("animations")) {
            json.addProperty("type", "flipbook");
            JsonObject clips = new JsonObject(), idle = new JsonObject();
            idle.add("frames", new JsonArray()); idle.addProperty("loop", true);
            clips.add("idle", idle); json.add("animations", clips);
        } else if (!type.equals("lang")) json.addProperty("id", id.toString());
        return json;
    }
}
