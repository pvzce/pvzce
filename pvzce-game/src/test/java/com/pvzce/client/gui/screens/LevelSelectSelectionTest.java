package com.pvzce.client.gui.screens;

import com.pvzce.common.network.packet.LevelListS2C;
import com.pvzce.common.network.packet.LevelPayload;
import com.pvzce.common.network.packet.LevelTabsS2C;
import com.pvzce.common.network.packet.SceneSyncS2C;
import com.pvzce.common.network.packet.SeedOption;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The level select screen's page and selection arithmetic.
 *
 * <p>Two rules are pinned here, both of which the screen got wrong in a previous shape:
 * the selection is a level <em>id</em> (its row is derived, so a refresh or a page change
 * cannot make the buttons act on a different level), and a page shows exactly the levels
 * whose group it is - never "the previous page's levels" when the page it opened has none.
 */
class LevelSelectSelectionTest {
    private static LevelListS2C.LevelInfo level(String id, String theme, String category) {
        LevelPayload payload = new LevelPayload(9, 5,
                List.of(new SeedOption("pvzce:pea_shooter", "plant", "pvzce:pea_shooter",
                        "pvzce:textures/entities/pea_shooter", 100)),
                6, List.of("pvzce:basic_zombie"),
                List.of(new SceneSyncS2C.Cell(0, 0, "pvzce:grass")), List.of(), List.of());
        return LevelListS2C.LevelInfo.of(id, id, "", "pvzce:plant_team", List.of(), "", "day",
                theme, category, false, payload, LevelListS2C.UnlockInfo.OPEN);
    }

    private static LevelListS2C.LevelInfo unclassified(String id) {
        return level(id, "pvzce:uncategorized", "pvzce:uncategorized");
    }

    @Test
    void theSelectedLevelIsFoundAfterTheListIsReordered() {
        LevelPage.Rows rows = LevelPage.rowsFor(
                List.of(level("pvzce:yard/adventure/a", "pvzce:yard", "pvzce:adventure"),
                        level("pvzce:yard/adventure/b", "pvzce:yard", "pvzce:adventure"),
                        level("pvzce:yard/adventure/c", "pvzce:yard", "pvzce:adventure")),
                tab("pvzce:yard", "pvzce:adventure"));
        assertEquals(2, LevelSelectScreen.indexOfLevel(rows, "pvzce:yard/adventure/c"));
    }

    @Test
    void aLevelThatLeftThePageHasNoSelection() {
        LevelPage.Rows rows = LevelPage.rowsFor(
                List.of(level("pvzce:yard/adventure/a", "pvzce:yard", "pvzce:adventure")),
                tab("pvzce:yard", "pvzce:adventure"));
        assertEquals(-1, LevelSelectScreen.indexOfLevel(rows, "pvzce:yard/adventure/gone"));
    }

    @Test
    void noSelectionStaysNoSelection() {
        LevelPage.Rows rows = LevelPage.rowsFor(
                List.of(level("pvzce:yard/adventure/a", "pvzce:yard", "pvzce:adventure")),
                tab("pvzce:yard", "pvzce:adventure"));
        assertEquals(-1, LevelSelectScreen.indexOfLevel(rows, ""));
        assertEquals(-1, LevelSelectScreen.indexOfLevel(rows, null));
    }

    @Test
    void aPageShowsOnlyItsOwnLevels() {
        List<LevelListS2C.LevelInfo> levels = List.of(
                level("pvzce:yard/adventure/1", "pvzce:yard", "pvzce:adventure"),
                level("pvzce:redstone/minigame/on_sea", "pvzce:redstone", "pvzce:minigame"),
                level("pvzce:yard/adventure/2", "pvzce:yard", "pvzce:adventure"),
                unclassified("pvzce:loose_level"));

        LevelPage.Rows adventure = LevelPage.rowsFor(levels, tab("pvzce:yard", "pvzce:adventure"));
        assertEquals(List.of("pvzce:yard/adventure/1", "pvzce:yard/adventure/2"),
                adventure.levels().stream().map(LevelListS2C.LevelInfo::id).toList());
        // The source indexes are what "the row the server's list has" means; they keep the
        // page's rows mapped onto the list a refresh will replace.
        assertEquals(List.of(0, 2), adventure.sourceIndexes());

        LevelPage.Rows minigame = LevelPage.rowsFor(levels, tab("pvzce:redstone", "pvzce:minigame"));
        assertEquals(List.of("pvzce:redstone/minigame/on_sea"),
                minigame.levels().stream().map(LevelListS2C.LevelInfo::id).toList());
    }

    /** A page nothing belongs to is empty, not "the levels from before". */
    @Test
    void anEmptyPageStaysEmpty() {
        List<LevelListS2C.LevelInfo> levels = List.of(
                level("pvzce:yard/adventure/1", "pvzce:yard", "pvzce:adventure"));
        LevelPage.Rows empty = LevelPage.rowsFor(levels, tab("pvzce:redstone", "pvzce:minigame"));
        assertTrue(empty.isEmpty());
        assertEquals(0, empty.size());
    }

    @Test
    void unclassifiedLevelsLiveOnTheUnclassifiedPageOnly() {
        List<LevelListS2C.LevelInfo> levels = List.of(
                level("pvzce:yard/adventure/1", "pvzce:yard", "pvzce:adventure"),
                unclassified("pvzce:loose_level"));
        LevelPage.Rows bucket = LevelPage.rowsFor(levels, LevelPage.uncategorizedTab());
        assertEquals(List.of("pvzce:loose_level"),
                bucket.levels().stream().map(LevelListS2C.LevelInfo::id).toList());
        assertFalse(LevelPage.belongsTo(levels.get(0), LevelPage.uncategorizedTab()));
    }

    /**
     * The tab table comes from the server, and the unclassified bucket is always in it.
     *
     * <p>Always: it is a fixed page rather than a page that exists only while a level is in
     * it. Deriving it from the levels is how it became unreachable - with every level
     * classified there was no bucket button at all, and no way to create a level in it.
     */
    @Test
    void theUnclassifiedBucketIsAlwaysInTheTableAndAlwaysLast() {
        List<LevelPage.Tab> tabs = LevelPage.tabs(List.of(
                new LevelTabsS2C.Tab("pvzce:yard", "pvzce:adventure"),
                new LevelTabsS2C.Tab("pvzce:yard", "pvzce:puzzle"),
                new LevelTabsS2C.Tab("pvzce:redstone", "pvzce:adventure")));
        assertEquals(4, tabs.size(), "three pages plus the unclassified bucket");
        assertTrue(tabs.get(3).uncategorized());
        assertEquals("pvzce:uncategorized", tabs.get(3).themeId());
        assertEquals(1, tabs.stream().filter(LevelPage.Tab::uncategorized).count());
    }

    /** A bucket the server already sent is not appended a second time. */
    @Test
    void aReceivedBucketIsNotDuplicated() {
        List<LevelPage.Tab> tabs = LevelPage.tabs(List.of(
                new LevelTabsS2C.Tab("pvzce:yard", "pvzce:adventure"),
                new LevelTabsS2C.Tab("pvzce:uncategorized", "pvzce:uncategorized")));
        assertEquals(2, tabs.size());
        assertTrue(tabs.get(1).uncategorized());
    }

    /** With no tabs at all the screen still has the bucket page to open. */
    @Test
    void anEmptyTabTableFallsBackToTheUnclassifiedPage() {
        List<LevelPage.Tab> tabs = LevelPage.tabs(List.of());
        assertEquals(1, tabs.size());
        assertTrue(tabs.get(0).uncategorized());
        assertEquals(List.of("pvzce:loose_level"),
                LevelPage.rowsFor(List.of(unclassified("pvzce:loose_level")), tabs.get(0))
                        .levels().stream().map(LevelListS2C.LevelInfo::id).toList());
    }

    /**
     * The left column lists themes, once each and in table order, bucket last.
     *
     * <p>One entry per theme, not per page: a theme with three categories is still one
     * button, and the categories are the row above the list.
     */
    @Test
    void theThemeColumnHasOneEntryPerThemeWithTheBucketLast() {
        List<LevelPage.Tab> tabs = LevelPage.tabs(List.of(
                new LevelTabsS2C.Tab("pvzce:yard", "pvzce:adventure"),
                new LevelTabsS2C.Tab("pvzce:yard", "pvzce:puzzle"),
                new LevelTabsS2C.Tab("pvzce:redstone", "pvzce:adventure")));
        assertEquals(List.of("pvzce:yard", "pvzce:redstone", "pvzce:uncategorized"),
                LevelPage.themeIds(tabs));
        assertEquals(2, LevelPage.pagesOfTheme(tabs, "pvzce:yard").size());
        assertEquals("pvzce:adventure", LevelPage.firstPageOfTheme(tabs, "pvzce:yard").categoryId());
        assertTrue(LevelPage.pagesOfTheme(tabs, "pvzce:nowhere").isEmpty());
        assertNull(LevelPage.firstPageOfTheme(tabs, "pvzce:nowhere"));
    }

    @Test
    void theSelectedLevelPicksThePageItIsOn() {
        List<LevelPage.Tab> tabs = LevelPage.tabs(List.of(
                new LevelTabsS2C.Tab("pvzce:yard", "pvzce:adventure"),
                new LevelTabsS2C.Tab("pvzce:redstone", "pvzce:minigame")));
        assertEquals(1, LevelPage.tabIndexOf(tabs,
                level("pvzce:redstone/minigame/on_sea", "pvzce:redstone", "pvzce:minigame")));
        assertEquals(-1, LevelPage.tabIndexOf(tabs, null));
    }

    private static LevelPage.Tab tab(String theme, String category) {
        return LevelPage.tabs(List.of(new LevelTabsS2C.Tab(theme, category))).get(0);
    }
}
