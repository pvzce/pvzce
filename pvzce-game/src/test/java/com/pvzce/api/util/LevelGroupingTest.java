package com.pvzce.api.util;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rule that decides which theme/category page a level belongs to.
 *
 * <p>Every fact here is one the server, the level list and the editor all depend on, which
 * is why the rule is one pure function rather than three readings of a file path.
 */
class LevelGroupingTest {
    private static final Identifier YARD = Identifier.withDefaultNamespace("yard");
    private static final Identifier REDSTONE = Identifier.withDefaultNamespace("redstone");
    private static final Identifier ADVENTURE = Identifier.withDefaultNamespace("adventure");
    private static final Identifier MINIGAME = Identifier.withDefaultNamespace("minigame");

    private static final List<Identifier> THEMES = List.of(YARD, REDSTONE);
    private static final List<Identifier> CATEGORIES = List.of(ADVENTURE, MINIGAME);

    @Test
    void aThreeSegmentIdNamesItsPage() {
        LevelGrouping.Group group = LevelGrouping.resolve(
                Identifier.withDefaultNamespace("yard/adventure/1_1"), THEMES, CATEGORIES);
        assertTrue(group.classified());
        assertEquals(YARD, group.theme());
        assertEquals(ADVENTURE, group.category());
        assertEquals("1_1", group.name());
    }

    /** Extra segments belong to the level's name, not to the group. */
    @Test
    void deeperPathsKeepTheRestOfTheName() {
        LevelGrouping.Group group = LevelGrouping.resolve(
                Identifier.withDefaultNamespace("yard/adventure/series/1_1"), THEMES, CATEGORIES);
        assertTrue(group.classified());
        assertEquals("series/1_1", group.name());
    }

    @Test
    void shortPathsAreUnclassified() {
        for (String id : List.of("demo_level", "yard/one_off")) {
            LevelGrouping.Group group = LevelGrouping.resolve(
                    Identifier.withDefaultNamespace(id), THEMES, CATEGORIES);
            assertFalse(group.classified(), id + " must be unclassified");
            assertEquals(LevelGrouping.UNCATEGORIZED, group.theme());
            assertEquals(LevelGrouping.UNCATEGORIZED, group.category());
            assertEquals(id, group.name());
        }
    }

    /**
     * An unknown theme or category is unclassified rather than a tab of its own.
     *
     * <p>Inventing a page from a typo would put a level where no theme was ever declared,
     * and the select screen would grow a phantom column that no data file explains.
     */
    @Test
    void anUnknownGroupIsUnclassified() {
        assertFalse(LevelGrouping.resolve(Identifier.withDefaultNamespace("roof/adventure/1"),
                THEMES, CATEGORIES).classified());
        assertFalse(LevelGrouping.resolve(Identifier.withDefaultNamespace("yard/puzzle/1"),
                THEMES, CATEGORIES).classified());
        // A category that exists but not under this theme is still not this theme's page.
        assertFalse(LevelGrouping.resolve(Identifier.withDefaultNamespace("yard/minigame/1"),
                List.of(YARD), List.of(ADVENTURE)).classified());
    }

    @Test
    void theReasonForAnUnknownGroupNamesTheMissingDefinition() {
        assertEquals("no level theme 'pvzce:roof' is registered",
                LevelGrouping.unknownGroupReason(Identifier.withDefaultNamespace("roof/adventure/1"),
                        THEMES, CATEGORIES));
        assertEquals("no level category 'pvzce:puzzle' is registered",
                LevelGrouping.unknownGroupReason(Identifier.withDefaultNamespace("yard/puzzle/1"),
                        THEMES, CATEGORIES));
        assertNull(LevelGrouping.unknownGroupReason(Identifier.withDefaultNamespace("yard/adventure/1"),
                THEMES, CATEGORIES));
        // A short path is not a mistake, so there is nothing to report.
        assertNull(LevelGrouping.unknownGroupReason(Identifier.withDefaultNamespace("demo_level"),
                THEMES, CATEGORIES));
    }

    @Test
    void theIdIsRebuiltFromTheGroupAndTheName() {
        assertEquals("pvzce:yard/adventure/1_1",
                LevelGrouping.levelId("pvzce", YARD, ADVENTURE, "1_1").toString());
        assertEquals("pvzce:on_sea",
                LevelGrouping.levelId("pvzce", LevelGrouping.UNCATEGORIZED, LevelGrouping.UNCATEGORIZED,
                        "on_sea").toString());
    }

    @Test
    void tabsListOnlyTheCategoriesThatExistInDeclaredOrder() {
        List<LevelGrouping.Tab> tabs = LevelGrouping.tabs(
                List.of(Identifier.withDefaultNamespace("yard/minigame/1"),
                        Identifier.withDefaultNamespace("yard/adventure/1"),
                        Identifier.withDefaultNamespace("redstone/adventure/1"),
                        Identifier.withDefaultNamespace("loose")),
                THEMES, CATEGORIES,
                Map.of(YARD, 0, REDSTONE, 1),
                Map.of(ADVENTURE, 0, MINIGAME, 5));
        assertEquals(List.of("pvzce:yard/pvzce:adventure", "pvzce:yard/pvzce:minigame",
                        "pvzce:redstone/pvzce:adventure", "pvzce:uncategorized/pvzce:uncategorized"),
                tabs.stream().map(tab -> tab.theme() + "/" + tab.category()).toList());
    }

    @Test
    void aThemeWithNoLevelsGetsNoTab() {
        List<LevelGrouping.Tab> tabs = LevelGrouping.tabs(
                List.of(Identifier.withDefaultNamespace("yard/adventure/1")),
                THEMES, CATEGORIES, Map.of(), Map.of());
        assertEquals(1, tabs.size());
        assertEquals(YARD, tabs.get(0).theme());
    }

    @Test
    void leafNameIsTheLastSegment() {
        assertEquals("1_1", LevelGrouping.leafName(Identifier.withDefaultNamespace("yard/adventure/1_1")));
        assertEquals("demo_level", LevelGrouping.leafName(Identifier.withDefaultNamespace("demo_level")));
        assertEquals("", LevelGrouping.leafName(null));
    }

    @Test
    void nullIdsAreIgnored() {
        List<LevelGrouping.Tab> tabs = LevelGrouping.tabs(
                java.util.Arrays.asList(Identifier.withDefaultNamespace("yard/adventure/1"), null),
                THEMES, CATEGORIES, Map.of(), Map.of());
        assertEquals(1, tabs.size());
    }
}
