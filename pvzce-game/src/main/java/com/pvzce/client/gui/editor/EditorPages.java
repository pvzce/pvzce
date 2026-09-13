package com.pvzce.client.gui.editor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Pages registered from outside the editor: a mod's own pages, and the page a level mechanic
 * brings with it.
 *
 * <p>Separate from the game's own page list because a registry is static and pages are not:
 * a page holds widgets and per-level state, so what is registered here is a <em>factory</em>
 * that the editor calls once per open. The editor's built-in pages are listed in
 * {@link BuiltInEditorPages} instead, deliberately, so that adding one to the game is a code
 * change in one place rather than a static registration whose order depends on class loading.
 */
public final class EditorPages {
    private static final Map<String, Supplier<EditorPage>> REGISTRY = new LinkedHashMap<>();

    /** Registers a page factory; the id is what the smoke hook and the language key use. */
    public static void register(String id, Supplier<EditorPage> factory) {
        REGISTRY.put(id, factory);
    }

    /** One instance of every registered page, in registration order. */
    public static List<EditorPage> external() {
        List<EditorPage> pages = new ArrayList<>();
        for (Supplier<EditorPage> factory : REGISTRY.values()) {
            pages.add(factory.get());
        }
        return pages;
    }

    private EditorPages() {
    }
}
