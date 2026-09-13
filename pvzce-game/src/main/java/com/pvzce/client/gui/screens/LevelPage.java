package com.pvzce.client.gui.screens;

import com.pvzce.api.util.Identifier;
import com.pvzce.api.util.LevelGrouping;
import com.pvzce.client.gui.GuiLang;
import com.pvzce.common.network.packet.LevelListS2C;
import com.pvzce.common.network.packet.LevelTabsS2C;

import java.util.ArrayList;
import java.util.List;

/**
 * The level select screen's page arithmetic: which tabs exist, which tab is open, and
 * which of the received levels are on it.
 *
 * <p>Split out of the screen for the same reason {@code ListPaging} is - this is the half
 * that can be tested without a window, and it is where the mistakes live. Two of them are
 * worth naming because the old screen could produce both:
 *
 * <ul>
 *   <li><b>Selection by slot.</b> The selection is a level <em>id</em>; the slot it lands
 *       in is derived after every refresh. Switching pages keeps it, and a level that is
 *       not on the open page simply has no row.
 *   <li><b>Pages that disagree with the list.</b> The tabs come from the server, the
 *       levels come from the server, and a tab that matches nothing renders as the empty
 *       page it is instead of showing the previous tab's levels.
 * </ul>
 */
final class LevelPage {
    /** One page: a theme and a category, plus whether it is the unclassified bucket. */
    record Tab(LevelTabsS2C.Tab source, int index, boolean uncategorized) {
        String themeId() {
            return source.theme();
        }

        String categoryId() {
            return source.category();
        }
    }

    /** The levels on one tab, in list order, plus their positions in the full list. */
    record Rows(List<LevelListS2C.LevelInfo> levels, List<Integer> sourceIndexes) {
        static Rows empty() {
            return new Rows(List.of(), List.of());
        }

        int size() {
            return levels.size();
        }

        boolean isEmpty() {
            return levels.isEmpty();
        }

        LevelListS2C.LevelInfo get(int index) {
            return levels.get(index);
        }
    }

    private LevelPage() {
    }

    /**
     * The page list, with the unclassified bucket appended once.
     *
     * <p>The bucket is always part of the table even when nothing is in it. Deriving it from
     * the levels was the bug: a level that is unclassified <em>today</em> is the only reason
     * the page would exist, so the page could not be opened to put a classified level back
     * into it - and with no unclassified levels at all there was no way to reach the bucket
     * or to create a level in it. It is a fixed page, so it is always offered.
     *
     * <p>A tab table that already contains the bucket is not given a second one, and a table
     * that is empty entirely still leaves the bucket page to open.
     */
    static List<Tab> tabs(List<LevelTabsS2C.Tab> received) {
        List<Tab> result = new ArrayList<>();
        boolean hasUncategorized = false;
        if (received != null) {
            for (LevelTabsS2C.Tab tab : received) {
                if (tab == null || tab.theme() == null || tab.category() == null) {
                    continue;
                }
                boolean uncategorized = isUncategorizedId(tab.theme()) || isUncategorizedId(tab.category());
                if (uncategorized) {
                    if (hasUncategorized) {
                        continue;
                    }
                    hasUncategorized = true;
                }
                result.add(new Tab(tab, result.size(), uncategorized));
            }
        }
        if (!hasUncategorized) {
            result.add(new Tab(uncategorizedSource(), result.size(), true));
        }
        return result;
    }

    /** The unclassified page's sentinel ids. */
    static LevelTabsS2C.Tab uncategorizedSource() {
        return new LevelTabsS2C.Tab(LevelGrouping.UNCATEGORIZED.toString(),
                LevelGrouping.UNCATEGORIZED.toString());
    }

    /**
     * The themes to show in the left column, in order, with the bucket last.
     *
     * <p>One entry per theme rather than one per page: a theme with three categories is one
     * button, and the categories are the row above the list.
     */
    static List<String> themeIds(List<Tab> tabs) {
        List<String> result = new ArrayList<>();
        for (Tab tab : tabs) {
            if (!result.contains(tab.themeId())) {
                result.add(tab.themeId());
            }
        }
        return result;
    }

    /** The pages of one theme, in order - the category row. */
    static List<Tab> pagesOfTheme(List<Tab> tabs, String themeId) {
        List<Tab> result = new ArrayList<>();
        for (Tab tab : tabs) {
            if (equal(tab.themeId(), themeId)) {
                result.add(tab);
            }
        }
        return result;
    }

    /** The first page of a theme, or {@code null}; used when the open page disappears. */
    static Tab firstPageOfTheme(List<Tab> tabs, String themeId) {
        List<Tab> pages = pagesOfTheme(tabs, themeId);
        return pages.isEmpty() ? null : pages.get(0);
    }

    /** The unclassified page; every tab table ends with exactly one. */
    static Tab uncategorizedTab() {
        return new Tab(uncategorizedSource(), 0, true);
    }

    /**
     * Where the level list lands when nothing else decides: the first page that lists levels.
     *
     * <p>Not simply {@code tabs.get(0)}. The screen's first frame runs against its own fallback
     * table - a single unclassified page - because the server's table arrives a round trip
     * later. Picking index 0 there selected the unclassified page, and when the real table
     * arrived that choice was still "valid" (unclassified is always in the list), so it was
     * kept: the player opened the level list and read "这个分类下还没有关卡" while every real
     * page sat one click away.
     *
     * <p>The bucket is the fallback only when there is nothing else, which is the same rule the
     * server applies when it builds the table: real themes first, the bucket last.
     */
    static Tab firstRealPage(List<Tab> tabs) {
        for (Tab tab : tabs) {
            if (!tab.uncategorized()) {
                return tab;
            }
        }
        return tabs.isEmpty() ? null : tabs.get(0);
    }

    /**
     * The levels belonging to one tab.
     *
     * <p>A level is on the tab whose ids equal its own resolved group, so a level that is
     * unclassified is on the unclassified tab and nowhere else.
     */
    static Rows rowsFor(List<LevelListS2C.LevelInfo> levels, Tab tab) {
        if (tab == null) {
            return Rows.empty();
        }
        List<LevelListS2C.LevelInfo> rows = new ArrayList<>();
        List<Integer> indexes = new ArrayList<>();
        for (int i = 0; i < levels.size(); i++) {
            LevelListS2C.LevelInfo info = levels.get(i);
            if (belongsTo(info, tab)) {
                rows.add(info);
                indexes.add(i);
            }
        }
        return new Rows(List.copyOf(rows), List.copyOf(indexes));
    }

    static boolean belongsTo(LevelListS2C.LevelInfo info, Tab tab) {
        if (info == null || tab == null) {
            return false;
        }
        if (tab.uncategorized()) {
            return info.isUncategorized();
        }
        return tab.themeId().equals(info.theme()) && tab.categoryId().equals(info.category());
    }

    /**
     * The tab a selection is on, or {@code -1}.
     *
     * <p>Used when the open tab disappears (a theme emptied by deleting its last level):
     * the screen can move to the page the selection actually lives on rather than silently
     * losing it.
     */
    static int tabIndexOf(List<Tab> tabs, LevelListS2C.LevelInfo selected) {
        if (selected == null) {
            return -1;
        }
        for (int i = 0; i < tabs.size(); i++) {
            if (belongsTo(selected, tabs.get(i))) {
                return i;
            }
        }
        return -1;
    }

    /** The index of the tab with these ids, or {@code -1}. */
    static int indexOf(List<Tab> tabs, String themeId, String categoryId) {
        for (int i = 0; i < tabs.size(); i++) {
            Tab tab = tabs.get(i);
            if (equal(tab.themeId(), themeId) && equal(tab.categoryId(), categoryId)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean equal(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    /**
     * A group's display name.
     *
     * <p>Goes through {@link GuiLang} first, so a pack can translate a theme; the key is
     * {@code <ns>.<path>} like every other content id. When there is no entry the id's last
     * segment is used, and only the unclassified bucket has a built-in fallback - it is a
     * sentinel rather than content, so it has no id to translate.
     */
    static String label(String groupId, String fallbackKey, String fallbackText) {
        if (groupId == null || groupId.isBlank() || isUncategorizedId(groupId)) {
            return GuiLang.raw(fallbackKey, fallbackText);
        }
        return GuiLang.name(groupId);
    }

    private static boolean isUncategorizedId(String id) {
        Identifier parsed = Identifier.tryParse(id);
        return LevelGrouping.isUncategorized(parsed);
    }
}
