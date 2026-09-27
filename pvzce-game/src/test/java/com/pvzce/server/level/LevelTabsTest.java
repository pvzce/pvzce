package com.pvzce.server.level;

import com.pvzce.api.content.LevelDef;
import com.pvzce.api.util.Identifier;
import com.pvzce.api.util.LevelGrouping;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.network.packet.LevelTabsS2C;
import com.pvzce.common.tag.TestContent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The level select's pages, derived from the registries rather than registered by hand.
 *
 * <p>Two things this pins that nothing used to. First, that every shipped level lands on a page
 * that actually exists: a level whose id's second segment is not a registered category falls into
 * the single "uncategorized" bucket silently, and the only thing that noticed was a log line
 * written during a data reload. Second, that the two endless modes share one page - they used to
 * live under two categories ({@code pvzce:endless} and {@code pvzce:survival}), which put "which
 * endless do I feel like" on two different tabs of the same theme.
 */
class LevelTabsTest {
    @BeforeAll
    static void load() throws Exception {
        TestContent.loadBuiltInContentAndTags();
    }

    @Test
    void everyShippedLevelLandsOnARegisteredPage() {
        List<Identifier> themes = new ArrayList<>(BuiltInRegistries.LEVEL_THEMES.keySet());
        List<Identifier> categories = new ArrayList<>(BuiltInRegistries.LEVEL_CATEGORIES.keySet());
        for (Identifier id : BuiltInRegistries.LEVELS.keySet()) {
            LevelGrouping.Group group = LevelGrouping.resolve(id, themes, categories);
            assertFalse(group.isUncategorized(),
                    id + " is not on any page: its theme or category segment is not registered"
                            + " (" + LevelGrouping.unknownGroupReason(id, themes, categories) + ")");
        }
    }

    @Test
    void theTwoEndlessModesShareOnePage() {
        Set<String> categoriesUnderYard = new LinkedHashSet<>();
        for (LevelTabsS2C.Tab tab : LevelTabs.build()) {
            if (tab.theme().equals("pvzce:yard")) {
                categoriesUnderYard.add(tab.category());
            }
        }
        assertTrue(categoriesUnderYard.contains("pvzce:survival"),
                "the endless levels live under survival, was " + categoriesUnderYard);
        assertFalse(categoriesUnderYard.contains("pvzce:endless"),
                "and nothing lives under a second endless category any more: " + categoriesUnderYard);

        List<Identifier> themes = new ArrayList<>(BuiltInRegistries.LEVEL_THEMES.keySet());
        List<Identifier> categories = new ArrayList<>(BuiltInRegistries.LEVEL_CATEGORIES.keySet());
        int endlessLevels = 0;
        for (LevelDef def : BuiltInRegistries.LEVELS) {
            // I, Zombie's endless is a *puzzle* mode and lives on the puzzle page: the rule this
            // loop enforces is "the survival family's endless modes are one page", not "every
            // level whose id says endless is survival". The puzzle one is asserted below.
            if (def.id().path().contains("/puzzle/")) {
                continue;
            }
            if (def.id().path().contains("endless") || def.id().path().contains("mutation_")) {
                assertEquals("survival",
                        LevelGrouping.resolve(def.id(), themes, categories).category().path(),
                        def.id() + " must be on the survival page");
                endlessLevels++;
            }
        }
        assertEquals("puzzle",
                LevelGrouping.resolve(PvzceIds.id("yard/puzzle/i_zombie_endless"), themes, categories)
                        .category().path(),
                "I, Zombie's endless mode is a puzzle level, and sits with the rest of them");
        assertTrue(endlessLevels >= 5,
                "the pool endless level and the four mutation tiers are all there, found "
                        + endlessLevels);
    }
}
