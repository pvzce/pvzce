package com.pvzce.client;

import com.pvzce.common.tag.TestContent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A level can leave its own scenery unpainted, by element id or by tag.
 *
 * <p>Both spellings exist for a reason: a tag is how a pack says "these are all the same kind
 * of thing", and an id is what an author can type in the editor without shipping a data pack
 * first. What is pinned here is that neither hides anything it was not asked to, because the
 * failure mode of a typo is a board that looks right.
 */
class SceneVisibilityTest {
    @BeforeAll
    static void loadContent() throws Exception {
        TestContent.loadBuiltInContentAndTags();
    }

    @Test
    void nothingIsHiddenByDefault() {
        SceneVisibility visibility = SceneVisibility.of(List.of());

        assertFalse(visibility.hides("pvzce:grass"));
    }

    @Test
    void anElementIdHidesThatElement() {
        SceneVisibility visibility = SceneVisibility.of(List.of("pvzce:grass"));

        assertTrue(visibility.hides("pvzce:grass"));
        assertFalse(visibility.hides("pvzce:grave"), "a tombstone is not a lawn");
    }

    @Test
    void aTagHidesEveryElementInIt() {
        // The built-in gravestone tag: four designs, one line.
        SceneVisibility visibility = SceneVisibility.of(List.of("#c:grave"));

        assertTrue(visibility.hides("pvzce:grave"));
        assertTrue(visibility.hides("pvzce:grave_cross"));
        assertTrue(visibility.hides("pvzce:grave_slab"));
        assertTrue(visibility.hides("pvzce:grave_wide"));
        assertFalse(visibility.hides("pvzce:grass"), "and nothing outside it");
    }

    @Test
    void anIdAndATagCanBeListedTogether() {
        SceneVisibility visibility = SceneVisibility.of(List.of("pvzce:grass", "#c:grave"));

        assertTrue(visibility.hides("pvzce:grass"));
        assertTrue(visibility.hides("pvzce:grave_cross"));
        assertFalse(visibility.hides("pvzce:water"));
    }

    /** A typo hides nothing rather than everything: the board still looks like a board. */
    @Test
    void anEntryThatNamesNothingIsIgnored() {
        SceneVisibility visibility = SceneVisibility.of(List.of("pvzce:not_a_tile", "#pvzce:nowhere"));

        assertFalse(visibility.hides("pvzce:grass"));
        assertFalse(visibility.hides("pvzce:grave"), "and it does not hide the neighbours either");
    }

    @Test
    void blankEntriesAreSkipped() {
        SceneVisibility visibility = SceneVisibility.of(List.of("", "   ", "pvzce:grass"));

        assertTrue(visibility.hides("pvzce:grass"));
    }
}
