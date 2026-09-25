package com.pvzce.client.gui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.resource.PvzceResourceManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Display names for content ids, read from {@code assets/<ns>/lang/<locale>.json}.
 *
 * <p>The language files have shipped since the first phase but nothing ever read
 * them, so every UI that needed a name fell back to the raw id: the editor's
 * palette listed {@code pvzce:pea_shooter}, the wave table listed
 * {@code pvzce:buckethead_zombie}, and the seed chooser did the same on hover.
 *
 * <p><b>Key shape.</b> A registered piece of content is named
 * {@code <registry>.<namespace>.<path>} - {@code plant.pvzce.pea_shooter},
 * {@code scene_element.pvzce.grass} - with optional trailing segments for extra text
 * ({@code plant.pvzce.pea_shooter.desc}, {@code .flavor}). The rule and the reason the category
 * exists live in {@link com.pvzce.common.core.RegistryCategories}. The old flat
 * {@code <namespace>.<path>} form is still accepted as a fallback so a resource pack written
 * before the change keeps working; the built-in files no longer use it for content.
 *
 * <p>A string that belongs to the interface rather than to a registry
 * ({@code gui.pvzce.almanac.title}, {@code pvzce.editor.page.rule}) is a hand-written key with
 * no id behind it and is read through {@link #raw}.
 *
 * <p>Lookup is a flat map with no fallback chain to the base language: the
 * built-in {@code zh_cn} file is the base, and a resource pack that ships only a
 * partial {@code en_us} would otherwise blank out every name it omits. Missing
 * keys degrade to the path, and callers that want both show {@link #name} beside
 * {@link #idLabel}.
 *
 * <p>Static rather than injected because it is read-only data with one game-wide
 * value; it is loaded once at client start and again on {@code /reload}.
 */
public final class GuiLang {
    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger("PVZCE/Lang");
    /** The locale the built-in files are authored in; also the fallback. */
    public static final String DEFAULT_LOCALE = "zh_cn";

    private static volatile Map<String, String> strings = Map.of();
    private static volatile String locale = DEFAULT_LOCALE;

    private GuiLang() {
    }

    /** Loads the configured locale, falling back to {@link #DEFAULT_LOCALE}. */
    public static void reload(PvzceResourceManager resources, String wantedLocale) {
        String target = wantedLocale == null || wantedLocale.isBlank() ? DEFAULT_LOCALE : wantedLocale;
        Map<String, String> loaded = read(resources, target);
        if (loaded.isEmpty() && !DEFAULT_LOCALE.equals(target)) {
            // A pack asked for a locale nothing provides; the built-in one is still
            // better than showing ids everywhere.
            loaded = read(resources, DEFAULT_LOCALE);
            target = DEFAULT_LOCALE;
        }
        locale = target;
        strings = Collections.unmodifiableMap(loaded);
    }

    public static void reload(PvzceResourceManager resources) {
        reload(resources, DEFAULT_LOCALE);
    }

    public static String locale() {
        return locale;
    }

    private static Map<String, String> read(PvzceResourceManager resources, String wantedLocale) {
        if (resources == null) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        // Every namespace that has a language file, the built-in one last so a
        // pack's entry wins while the built-in file still provides the fallback for
        // everything the pack omits.
        java.util.List<String> namespaces = new java.util.ArrayList<>();
        try {
            for (String path : resources.listResources("assets").keySet()) {
                String[] parts = path.split("/");
                if (parts.length > 3 && "lang".equals(parts[2])
                        && (wantedLocale + ".json").equals(parts[3]) && !namespaces.contains(parts[1])) {
                    namespaces.add(parts[1]);
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Could not list language files", e);
        }
        java.util.Collections.sort(namespaces);
        // The built-in namespace is read last so a pack's translation wins while the
        // built-in file still covers what the pack omits.
        namespaces.remove("pvzce");
        namespaces.add("pvzce");
        for (String namespace : namespaces) {
            try {
                var resource = resources.getAsset(Identifier.of(namespace, "lang/" + wantedLocale + ".json"));
                if (resource.isEmpty()) {
                    continue;
                }
                JsonElement json = JsonParser.parseString(resource.get().readString());
                if (!json.isJsonObject()) {
                    continue;
                }
                JsonObject object = json.getAsJsonObject();
                for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                    if (entry.getValue().isJsonPrimitive()) {
                        result.put(entry.getKey(), entry.getValue().getAsString());
                    }
                }
            } catch (Exception e) {
                LOGGER.warn("Failed to read lang/" + wantedLocale + ".json from " + namespace, e);
            }
        }
        return result;
    }

    /** True when a language file was found and parsed. */
    public static boolean isLoaded() {
        return !strings.isEmpty();
    }

    /**
     * The display name of a content id, or {@code null} when the language file has
     * no entry. Callers that must show something use {@link #name} instead.
     *
     * <p>Flat: tries the category-qualified key first when {@code category} is given, then the
     * pre-category {@code <namespace>.<path>} key. Both are tried because the two forms are
     * indistinguishable for a non-content id, and a pack that translates
     * {@code pvzce.level_category.adventure} must not stop resolving the moment the built-in
     * file moves to {@code level_category.pvzce.adventure}.
     */
    public static String lookup(String category, Identifier id) {
        if (id == null) {
            return null;
        }
        String key = com.pvzce.common.core.RegistryCategories.key(category, id);
        if (key != null) {
            String found = strings.get(key);
            if (found != null) {
                return found;
            }
        }
        return strings.get(id.namespace() + "." + id.path());
    }

    public static String lookup(Identifier id) {
        return lookup(null, id);
    }

    public static String lookup(String id) {
        return lookup(null, Identifier.tryParse(id));
    }

    /**
     * The display name of a content id, falling back to the id's path.
     *
     * <p>Deliberately never returns the raw {@code namespace:path}: a missing
     * translation should look like a name the author can fix, not like a debugging
     * dump in a list row.
     */
    public static String name(String category, Identifier id) {
        String found = lookup(category, id);
        return found != null ? found : GuiText.shortId(id);
    }

    public static String name(Identifier id) {
        return name(null, id);
    }

    public static String name(String id) {
        return name(null, Identifier.tryParse(id));
    }

    /**
     * A trailing segment of a content's entry: {@code plant.pvzce.pea_shooter.desc}.
     *
     * <p>One accessor rather than every screen spelling the key out, because the shape of that
     * key is the thing a resource pack has to match and it should be written down once. Answers
     * {@code null} when the segment is absent so callers can fall back rather than drawing an
     * empty box.
     */
    public static String content(String category, Identifier id, String segment) {
        if (id == null || segment == null || segment.isBlank()) {
            return null;
        }
        String base = com.pvzce.common.core.RegistryCategories.key(category, id);
        if (base == null) {
            return null;
        }
        return strings.get(base + "." + segment);
    }

    /** The same, falling back to {@code fallback} when the entry has no such segment. */
    public static String contentOr(String category, Identifier id, String segment, String fallback) {
        String found = content(category, id, segment);
        return found != null ? found : fallback;
    }

    /**
     * The {@code namespace:path} form, for the places that must stay unambiguous
     * (a tooltip, the level id field). Kept separate from {@link #name} so callers
     * choose explicitly instead of one function guessing.
     */
    public static String idLabel(Identifier id) {
        return id == null ? "" : id.toString();
    }

    /** A translation by raw key, for the UI's own strings. */
    public static String raw(String key, String fallback) {
        return strings.getOrDefault(key, fallback);
    }
}
