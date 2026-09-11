package com.pvzce.common.resource;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.pvzce.api.content.EnvVarType;
import com.pvzce.api.content.GameRuleType;
import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.LiquidDef;
import com.pvzce.api.content.PlantDef;
import com.pvzce.api.content.ProjectileDef;
import com.pvzce.api.content.ResourceDef;
import com.pvzce.api.content.SceneElementDef;
import com.pvzce.api.content.SlotDef;
import com.pvzce.api.content.SoundEventDef;
import com.pvzce.api.content.ToolDef;
import com.pvzce.api.content.ZombieDef;
import com.pvzce.api.registry.MappedRegistry;
import com.pvzce.api.registry.Registry;
import com.pvzce.api.registry.RegistryAccess;
import com.pvzce.api.registry.ResourceKey;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.core.PvzceRegistries;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Data-driven registry loader modeled after
 * {@code net.minecraft.resources.RegistryDataLoader}.
 *
 * <p>Directory convention: {@code data/<namespace>/pvzce/<registry_path>/<id>.json},
 * where {@code <registry_path>} may be nested ({@code plants/upgrades/pea.json}
 * registers {@code <namespace>:upgrades/pea} rather than colliding with a sibling
 * {@code plants/other/pea.json}).
 *
 * <p>{@link #CONTENT_REGISTRIES} is the single table that maps a registry to its
 * directory. It used to be written twice - once here with plural directory names
 * and once in the tag loader with singular registry names - so a tag file under
 * {@code tags/pvzce/plants/} was rejected as an unknown registry even though the
 * matching content directory was {@code plants/}. Both loaders now read this table,
 * and each entry declares both spellings.
 */
public final class PvzceDataLoader {
    /**
     * {@code data/<ns>/pvzce/<registry path>/<file>.json} - the id is the whole
     * relative path. The listing prefix has no separator (packs join it
     * themselves) while path parsing needs the separator to be part of the check.
     */
    private static final String LIST_PREFIX = "data";
    private static final String PATH_PREFIX = "data/";
    private static final String CONTENT_SEGMENT = "pvzce";
    private static final String CONTENT_SUFFIX = ".json";

    private static final List<RegistryData<?>> REGISTRIES = List.of(
            new RegistryData<>(PvzceRegistries.LEVELS, LevelDef.CODEC, "levels"),
            new RegistryData<>(PvzceRegistries.PLANTS, PlantDef.CODEC, "plants"),
            new RegistryData<>(PvzceRegistries.ZOMBIES, ZombieDef.CODEC, "zombies"),
            new RegistryData<>(PvzceRegistries.PROJECTILES, ProjectileDef.CODEC, "projectiles"),
            new RegistryData<>(PvzceRegistries.RESOURCES, ResourceDef.CODEC, "resources"),
            new RegistryData<>(PvzceRegistries.SLOT_TYPES, SlotDef.CODEC, "slots"),
            new RegistryData<>(PvzceRegistries.TOOLS, ToolDef.CODEC, "tools"),
            new RegistryData<>(PvzceRegistries.SCENE_ELEMENTS, SceneElementDef.CODEC, "scene_elements"),
            new RegistryData<>(PvzceRegistries.LIQUIDS, LiquidDef.CODEC, "liquids"),
            new RegistryData<>(PvzceRegistries.SOUND_EVENTS, SoundEventDef.CODEC, "sound_events"),
            new RegistryData<>(PvzceRegistries.GAME_RULES, null, "game_rules"),
            new RegistryData<>(PvzceRegistries.ENV_VAR_TYPES, null, "env_var_types"));

    /** Data-backed registries, in load order (codec-less entries are static-only). */
    public static final List<RegistryData<?>> CONTENT_REGISTRIES = REGISTRIES.stream()
            .filter(data -> data.codec() != null)
            .toList();

    /**
     * One registry's directory contract.
     *
     * @param key         the registry key; its singular id path is also the tag directory
     * @param codec       the entry codec, or {@code null} for registries that are code-only
     * @param contentPath the content directory name ({@code data/<ns>/pvzce/<contentPath>/})
     */
    public record RegistryData<T>(ResourceKey<Registry<T>> key, Codec<T> codec, String contentPath) {
        /** The singular registry path, used by tags and by the {@code /pvzce registry} command. */
        public String idPath() {
            return key.location().path();
        }

        /** True when {@code path} names this registry's content directory. */
        public boolean matchesContentPath(String path) {
            return contentPath.equals(path);
        }

        /** Tag directories accept both spellings, so {@code plants/} and {@code plant/} both work. */
        public boolean matchesTagPath(String path) {
            return idPath().equals(path) || contentPath.equals(path);
        }
    }

    public record LoadResult(List<Identifier> loaded, List<String> errors) {
        public boolean success() {
            return errors.isEmpty();
        }

        /** Merges two results, keeping the error order stable. */
        public LoadResult plus(LoadResult other) {
            List<Identifier> allLoaded = new ArrayList<>(loaded);
            allLoaded.addAll(other.loaded());
            List<String> allErrors = new ArrayList<>(errors);
            allErrors.addAll(other.errors());
            return new LoadResult(List.copyOf(allLoaded), List.copyOf(allErrors));
        }
    }

    /** The registry whose content directory is {@code path}, or {@code null}. */
    public static RegistryData<?> registryForContentPath(String path) {
        for (RegistryData<?> data : REGISTRIES) {
            if (data.matchesContentPath(path)) {
                return data;
            }
        }
        return null;
    }

    /** The registry whose tag directory is {@code path} (either spelling), or {@code null}. */
    public static RegistryData<?> registryForTagPath(String path) {
        for (RegistryData<?> data : REGISTRIES) {
            if (data.matchesTagPath(path)) {
                return data;
            }
        }
        return null;
    }

    /** Every registry directory name, for diagnostics. */
    public static List<String> contentPaths() {
        return REGISTRIES.stream().map(RegistryData::contentPath).toList();
    }

    public LoadResult load(PvzceResourceManager resourceManager, RegistryAccess access) throws IOException {
        List<Identifier> loaded = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        // Group the pack's files by registry once, so each registry scans only its own
        // directory instead of re-walking every data file.
        Map<String, Map<String, PackResource>> byRegistry = new LinkedHashMap<>();
        for (Map.Entry<String, PackResource> entry : resourceManager.listResources(LIST_PREFIX).entrySet()) {
            ParsedPath parsed = parseContentPath(entry.getKey());
            if (parsed == null) {
                continue;
            }
            byRegistry.computeIfAbsent(parsed.registryPath(), ignored -> new LinkedHashMap<>())
                    .put(parsed.relativePath(), entry.getValue());
        }

        for (RegistryData<?> data : CONTENT_REGISTRIES) {
            @SuppressWarnings({"unchecked", "rawtypes"})
            MappedRegistry<Object> registry = (MappedRegistry) access.get(data.key());
            if (registry == null) {
                errors.add("No registry available for " + data.key());
                continue;
            }
            registry.unfreeze();
            registry.clearDynamic();

            Map<String, PackResource> files = byRegistry.get(data.contentPath());
            if (files == null) {
                continue;
            }
            Map<Identifier, String> sources = new LinkedHashMap<>();
            for (Map.Entry<String, PackResource> entry : files.entrySet()) {
                loadOne(data, entry.getKey(), entry.getValue(), registry, sources, loaded, errors);
            }
        }

        for (RegistryData<?> data : REGISTRIES) {
            Registry<?> registry = access.get(data.key());
            if (registry != null) {
                registry.freeze();
            }
        }

        return new LoadResult(List.copyOf(loaded), List.copyOf(errors));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void loadOne(RegistryData<?> data, String relativePath, PackResource file,
                                MappedRegistry<Object> registry, Map<Identifier, String> sources,
                                List<Identifier> loaded, List<String> errors) {
        Identifier inferredId = Identifier.tryParse(relativePath);
        if (inferredId == null) {
            errors.add("Invalid content id '" + relativePath + "' in " + file.path());
            return;
        }
        try {
            JsonElement json = JsonParser.parseString(file.readString());
            Identifier registeredId = inferredId;
            if (json.isJsonObject()) {
                JsonObject object = json.getAsJsonObject();
                JsonElement explicitId = object.get("id");
                if (explicitId == null) {
                    object.addProperty("id", inferredId.toString());
                } else if (explicitId.isJsonPrimitive()) {
                    Identifier parsed = Identifier.tryParse(explicitId.getAsString());
                    if (parsed == null) {
                        errors.add("Invalid explicit id '" + explicitId.getAsString() + "' in " + file.path());
                        return;
                    }
                    registeredId = parsed;
                }
            }
            // Collisions are checked on the id the entry will actually be registered
            // under, so an explicit "id" cannot smuggle a second definition past the
            // path-derived one.
            String previous = sources.put(registeredId, file.path());
            if (previous != null) {
                errors.add("Duplicate content id " + registeredId + " in " + file.path()
                        + " (already defined by " + previous + ")");
                return;
            }
            Object value = ((Codec<Object>) data.codec()).parse(JsonOps.INSTANCE, json).getOrThrow();
            registry.registerDynamic(registeredId, value);
            loaded.add(registeredId);
        } catch (RuntimeException e) {
            errors.add("Failed to load " + file.path() + ": " + e.getMessage());
        }
    }

    /** {@code data/<ns>/pvzce/<registry>/<relative>.json} split into its parts. */
    private record ParsedPath(String namespace, String registryPath, String relativePath) {
    }

    private static ParsedPath parseContentPath(String path) {
        if (!path.startsWith(PATH_PREFIX) || !path.endsWith(CONTENT_SUFFIX)) {
            return null;
        }
        String body = path.substring(PATH_PREFIX.length(), path.length() - CONTENT_SUFFIX.length());
        String[] parts = body.split("/");
        // <namespace>/pvzce/<registry>/<relative...>
        if (parts.length < 4 || !CONTENT_SEGMENT.equals(parts[1])) {
            return null;
        }
        String namespace = parts[0];
        String registryPath = parts[2];
        StringBuilder relative = new StringBuilder();
        // Everything after the registry segment is part of the id, not just the
        // basename: two nested files used to collapse onto the same id and one
        // silently replaced the other (the shipped sound_events pack only works
        // because every nested file repeats its own "id").
        for (int i = 3; i < parts.length; i++) {
            if (i > 3) {
                relative.append('/');
            }
            relative.append(parts[i]);
        }
        return new ParsedPath(namespace, registryPath, namespace + ':' + relative);
    }
}
