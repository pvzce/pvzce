package com.pvzce.server.level;

import com.pvzce.api.content.LevelCategoryDef;
import com.pvzce.api.content.LevelThemeDef;
import com.pvzce.api.registry.Registry;
import com.pvzce.api.util.Identifier;
import com.pvzce.api.util.LevelGrouping;
import com.pvzce.common.network.packet.LevelTabsS2C;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the level select screen's page list from the registries.
 *
 * <p>Pages are not stored anywhere: they are the registered themes crossed with the
 * categories that the levels under them actually use. That is why adding a series of
 * levels needs no tab registration, and why a theme cannot end up with a tab that opens
 * on nothing.
 */
public final class LevelTabs {
    private LevelTabs() {
    }

    /** The page list for the shipped content, one entry per (theme, category) pair. */
    public static List<LevelTabsS2C.Tab> build() {
        return build(com.pvzce.common.core.BuiltInRegistries.LEVELS,
                com.pvzce.common.core.BuiltInRegistries.LEVEL_THEMES,
                com.pvzce.common.core.BuiltInRegistries.LEVEL_CATEGORIES);
    }

    static List<LevelTabsS2C.Tab> build(Registry<com.pvzce.api.content.LevelDef> levels,
                                        Registry<LevelThemeDef> themes,
                                        Registry<LevelCategoryDef> categories) {
        List<Identifier> levelIds = new ArrayList<>(levels.keySet());
        // Same order the level list itself is sent in (see LevelGrouping.compareIds): the
        // pages are derived from the levels, and a table built in a different order than
        // the list it describes is a difference nobody can see until it matters.
        levelIds.sort((a, b) -> com.pvzce.api.util.LevelGrouping.compareIds(a.toString(), b.toString()));
        List<Identifier> themeIds = new ArrayList<>(themes.keySet());

        List<Identifier> categoryIds = new ArrayList<>(categories.keySet());
        Map<Identifier, Integer> themeOrder = new LinkedHashMap<>();
        Map<Identifier, Integer> categoryOrder = new LinkedHashMap<>();
        for (Identifier id : themeIds) {
            LevelThemeDef def = themes.get(id);
            themeOrder.put(id, def == null ? 0 : def.order());
        }
        for (Identifier id : categoryIds) {
            LevelCategoryDef def = categories.get(id);
            categoryOrder.put(id, def == null ? 0 : def.order());
        }

        List<LevelGrouping.Tab> tabs = LevelGrouping.tabs(levelIds, themeIds, categoryIds,
                themeOrder, categoryOrder);
        List<LevelTabsS2C.Tab> result = new ArrayList<>(tabs.size());
        for (LevelGrouping.Tab tab : tabs) {
            result.add(new LevelTabsS2C.Tab(tab.theme().toString(), tab.category().toString()));
        }
        return List.copyOf(result);
    }
}
