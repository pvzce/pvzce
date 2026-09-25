package com.pvzce.common.core;

import com.pvzce.api.registry.Registry;
import com.pvzce.api.util.Identifier;

/**
 * The canonical language-file key for a piece of content: {@code <category>.<namespace>.<path>}.
 *
 * <p>Mirrors the one rule Minecraft uses and the one this project now writes down: a registry
 * entry that has a display name is named by <em>which registry it lives in</em>, then its id -
 * {@code plant.pvzce.pea_shooter}, {@code scene_element.pvzce.grass},
 * {@code level_category.pvzce.adventure}. The category is the registry's own id
 * ({@link Registry#key()}{@code .registry()}), not the folder its JSON sits in, because the
 * folder is plural and the registry is not: {@code data/pvzce/plants/} loads
 * {@code pvzce:plant}.
 *
 * <p>Before this there was no category at all - the key was just {@code pvzce.pea_shooter},
 * which is also the shape of a UI string ({@code pvzce.inventory.title}), so the two namespace
 * spaces overlapped and neither could be validated. Two consequences that are now impossible:
 * a pack could not translate {@code pvzce:water} the liquid and {@code pvzce:water} the scene
 * element differently, and a typo in a key looked exactly like a missing translation.
 *
 * <p>Content that is <em>not</em> a registry entry keeps a hand-written key
 * ({@code gui.pvzce.almanac.title}, {@code pvzce.editor.page.rule}): there is no id to derive
 * one from. The boundary is "is it registered", not "is it data-driven" - a level category is
 * read from {@code data/<ns>/level_categories/*.json} and still gets a key, because a resource
 * pack must be able to translate it.
 */
public final class RegistryCategories {
    /**
     * The category of a registry, from its own key.
     *
     * <p>{@code pvzce:plant} answers {@code "plant"}. The root namespace is dropped: it is the
     * same namespace for every registry today, and a key that repeated it would read
     * {@code pvzce:plant.pvzce.pea_shooter} for no gain.
     */
    public static String of(Registry<?> registry) {
        if (registry == null || registry.key() == null || registry.key().registry() == null) {
            return "";
        }
        Identifier key = registry.key().registry();
        return "pvzce".equals(key.namespace()) ? key.path() : key.toString();
    }

    /** The category of a registry named by its {@link PvzceRegistries} id, or {@code ""}. */
    public static String of(String category) {
        if (category == null || category.isBlank()) {
            return "";
        }
        var key = PvzceRegistries.byCategory().get(PvzceRegistries.canonicalCategory(category));
        if (key == null) {
            return category;
        }
        return of(BuiltInRegistries.ACCESS.get(
                (com.pvzce.api.registry.ResourceKey<Registry<Object>>) (Object) key));
    }

    /**
     * The language key for a content id in a category.
     *
     * <p>An empty category or a null id answers {@code null} rather than a half-built key, so
     * a caller that could not name the registry falls through to the id's path instead of
     * looking up {@code ".pvzce.pea_shooter"}.
     */
    public static String key(String category, Identifier id) {
        if (id == null || category == null || category.isBlank()) {
            return null;
        }
        return category + "." + id.namespace() + "." + id.path();
    }

    private RegistryCategories() {
    }
}
