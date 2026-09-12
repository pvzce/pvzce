package com.pvzce.common.tag;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pvzce.api.registry.Registry;
import com.pvzce.api.registry.RegistryAccess;
import com.pvzce.api.tag.TagKey;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.resource.PackResource;
import com.pvzce.common.resource.PvzceDataLoader;
import com.pvzce.common.resource.PvzceResourceManager;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MC {@code TagLoader}-shaped data pack tag loader.
 *
 * <p>Directory convention: {@code data/<ns>/tags/<registry>/<name>.json}, where
 * {@code <registry>} is either the singular registry id path ({@code plant}) or
 * the content directory name ({@code plants}). Both spellings are accepted
 * because the registry-to-directory mapping lives in
 * {@link PvzceDataLoader#CONTENT_REGISTRIES} - this class used to keep a second,
 * hand-typed table that only knew the singular form and rejected the plural one.
 *
 * <p>The tag id is {@code <ns>:<name>}, exactly like Minecraft, so
 * {@code data/c/tags/plant/plantable.json} is {@code #c:plantable}. Mods are
 * expected to extend the convention tags shipped under {@code data/c}.
 *
 * <p>The older three-segment spelling {@code data/<ns>/tags/pvzce/<registry>/<name>.json}
 * is gone: it was the tag-side twin of the doubled namespace the data pack used to
 * carry, and with the pack root flat there is nothing left for it to mean.
 *
 * <p>JSON format is {@code {"replace": false, "values": [...]}}; values may
 * reference other tags with a leading {@code #}.
 *
 * <p>Tags are per registry: {@code #c:plantable} on scene elements and
 * {@code #c:plantable} on plants are two unrelated tags, which is how the
 * placement rules give the same name a terrain meaning and a plant meaning.
 */
public final class TagManager {
    private static final String TAG_SEGMENT = "tags";
    private static final String SUFFIX = ".json";

    private final Map<RawKey, Set<Identifier>> resolvedTags = new LinkedHashMap<>();

    public record LoadResult(List<Identifier> loaded, List<String> errors) {
        public boolean success() {
            return errors.isEmpty();
        }
    }

    /** Resolves every tag file in the pack stack and binds the results to registries. */
    public synchronized LoadResult reload(PvzceResourceManager resources, RegistryAccess access) throws IOException {
        List<Identifier> loaded = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        Map<RawKey, RawTag> rawTags = new LinkedHashMap<>();
        Map<String, List<PackResource>> dataFiles = resources.listResourceStacks("data");

        for (Registry<?> registry : access.registries().values()) {
            registry.clearTags();
        }

        for (Map.Entry<String, List<PackResource>> entry : dataFiles.entrySet()) {
            ParsedTagPath parsed = parseTagPath(entry.getKey());
            if (parsed == null) {
                continue;
            }
            PvzceDataLoader.RegistryData<?> data = PvzceDataLoader.registryForTagPath(parsed.registryPath());
            if (data == null) {
                errors.add("Unknown tag registry path '" + parsed.registryPath() + "' in " + entry.getKey()
                        + " (known directories: " + PvzceDataLoader.contentPaths() + ")");
                continue;
            }
            Identifier tagId = Identifier.tryParse(parsed.tagId());
            if (tagId == null) {
                errors.add("Invalid tag id in " + entry.getKey());
                continue;
            }

            // Every layer must parse. A broken file used to leave the tag present but
            // empty, overriding a lower pack's valid values with nothing.
            List<Identifier> direct = new ArrayList<>();
            List<Identifier> references = new ArrayList<>();
            boolean anyParsed = false;
            boolean broken = false;
            for (PackResource file : entry.getValue()) {
                RawTag tag = parseTag(file, errors);
                if (tag == null) {
                    broken = true;
                    continue;
                }
                anyParsed = true;
                if (tag.replace()) {
                    direct.clear();
                    references.clear();
                }
                direct.addAll(tag.direct());
                references.addAll(tag.references());
            }
            if (broken && !anyParsed) {
                // Nothing usable in any layer: the tag simply does not exist.
                continue;
            }
            RawKey key = new RawKey(data.contentPath(), tagId);
            rawTags.put(key, new RawTag(false, direct, references));
            loaded.add(tagId);
        }

        Map<RawKey, Set<Identifier>> resolved = new LinkedHashMap<>();
        for (RawKey key : rawTags.keySet()) {
            resolve(key, rawTags, resolved, new LinkedHashSet<>(), errors);
        }

        for (Map.Entry<RawKey, Set<Identifier>> entry : resolved.entrySet()) {
            PvzceDataLoader.RegistryData<?> data =
                    PvzceDataLoader.registryForContentPath(entry.getKey().registryPath());
            Registry<?> registry = data == null ? null : access.get(data.key());
            if (registry == null) {
                errors.add("No registry available for tag " + entry.getKey().tagId());
                continue;
            }
            warnAboutUnknownEntries(registry, entry.getKey(), entry.getValue(), errors);
            bind(registry, entry.getKey().tagId(), entry.getValue());
        }

        resolvedTags.clear();
        resolvedTags.putAll(resolved);
        return new LoadResult(List.copyOf(loaded), List.copyOf(errors));
    }

    /** An entry that names a non-existent registry object is almost always a typo. */
    private static void warnAboutUnknownEntries(Registry<?> registry, RawKey key, Set<Identifier> entries,
                                                List<String> errors) {
        for (Identifier id : entries) {
            if (!registry.containsKey(id)) {
                errors.add("Tag #" + key.tagId() + " references unknown " + key.registryPath()
                        + " entry " + id);
            }
        }
    }

    private static RawTag parseTag(PackResource file, List<String> errors) {
        try {
            JsonElement json = JsonParser.parseString(file.readString());
            if (!json.isJsonObject()) {
                errors.add("Tag file is not a JSON object: " + file.path());
                return null;
            }
            JsonObject object = json.getAsJsonObject();
            boolean replace = object.has("replace") && object.get("replace").getAsBoolean();
            List<Identifier> direct = new ArrayList<>();
            List<Identifier> references = new ArrayList<>();
            if (!object.has("values") || !object.get("values").isJsonArray()) {
                errors.add("Tag file has no values array: " + file.path());
                return null;
            }
            for (JsonElement value : object.getAsJsonArray("values")) {
                String text = value.getAsString();
                Identifier id = Identifier.tryParse(text.startsWith("#") ? text.substring(1) : text);
                if (id == null) {
                    errors.add("Invalid tag entry '" + text + "' in " + file.path());
                    continue;
                }
                (text.startsWith("#") ? references : direct).add(id);
            }
            return new RawTag(replace, direct, references);
        } catch (RuntimeException e) {
            errors.add("Failed to load tag " + file.path() + ": " + e.getMessage());
            return null;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void bind(Registry registry, Identifier id, Set<Identifier> entries) {
        registry.bindTag(TagKey.create(registry.key(), id), entries);
    }

    private Set<Identifier> resolve(RawKey key, Map<RawKey, RawTag> rawTags,
                                    Map<RawKey, Set<Identifier>> resolved,
                                    Set<RawKey> visiting, List<String> errors) {
        Set<Identifier> already = resolved.get(key);
        if (already != null) {
            return already;
        }
        if (!visiting.add(key)) {
            errors.add("Circular tag reference at #" + key.tagId());
            return Set.of();
        }
        RawTag raw = rawTags.get(key);
        if (raw == null) {
            errors.add("Unknown tag reference #" + key.tagId());
            visiting.remove(key);
            return Set.of();
        }

        Set<Identifier> entries = new LinkedHashSet<>(raw.direct());
        for (Identifier reference : raw.references()) {
            entries.addAll(resolve(new RawKey(key.registryPath(), reference), rawTags, resolved, visiting, errors));
        }
        visiting.remove(key);
        Set<Identifier> result = Collections.unmodifiableSet(entries);
        resolved.put(key, result);
        return result;
    }

    public Set<TagKey<?>> keys() {
        Set<TagKey<?>> keys = new LinkedHashSet<>();
        synchronized (this) {
            for (RawKey key : resolvedTags.keySet()) {
                keys.add(tagKey(key));
            }
        }
        return Collections.unmodifiableSet(keys);
    }

    public Set<Identifier> ids(TagKey<?> tag) {
        synchronized (this) {
            Set<Identifier> ids = resolvedTags.get(rawKey(tag));
            return ids == null ? Set.of() : ids;
        }
    }

    public boolean contains(TagKey<?> tag, Identifier id) {
        return ids(tag).contains(id);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static TagKey<?> tagKey(RawKey key) {
        PvzceDataLoader.RegistryData<?> data = PvzceDataLoader.registryForContentPath(key.registryPath());
        if (data == null) {
            return null;
        }
        return TagKey.create(data.key(), key.tagId());
    }

    /** Rebuilds the lookup key from a bound tag; the registry's id path is the canonical one. */
    private static RawKey rawKey(TagKey<?> tag) {
        String idPath = tag.registry().location().path();
        PvzceDataLoader.RegistryData<?> data = PvzceDataLoader.registryForTagPath(idPath);
        String contentPath = data == null ? idPath : data.contentPath();
        return new RawKey(contentPath, tag.id());
    }

    /** {@code data/<ns>/tags/<registry>/<name>.json} split into its parts. */
    private record ParsedTagPath(String registryPath, String tagId) {
    }

    /**
     * {@code data/<ns>/tags/<registry>/<name>.json} - the tag id is the namespace
     * plus the whole relative path under the registry directory, so nested names
     * ({@code plant/plantable/flowers}) stay nested.
     */
    private static ParsedTagPath parseTagPath(String path) {
        if (!path.startsWith("data/") || !path.endsWith(SUFFIX)) {
            return null;
        }
        String body = path.substring("data/".length(), path.length() - SUFFIX.length());
        String[] parts = body.split("/");
        // <namespace>/tags/<registry>/<relative...>
        if (parts.length < 4 || !TAG_SEGMENT.equals(parts[1])) {
            return null;
        }
        String namespace = parts[0];
        String registryPath = parts[2];
        StringBuilder relative = new StringBuilder();
        for (int i = 3; i < parts.length; i++) {
            if (i > 3) {
                relative.append('/');
            }
            relative.append(parts[i]);
        }
        return new ParsedTagPath(registryPath, namespace + ':' + relative);
    }

    private record RawKey(String registryPath, Identifier tagId) {
    }

    private record RawTag(boolean replace, List<Identifier> direct, List<Identifier> references) {
    }
}
