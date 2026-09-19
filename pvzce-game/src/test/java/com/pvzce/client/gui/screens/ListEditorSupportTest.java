package com.pvzce.client.gui.screens;

import com.pvzce.client.gui.components.AbstractSelectionList;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The list-editing algebra the five data editors share.
 *
 * <p>Worth pinning because every rule here is a divergence that was resolved once and would be
 * easy to resolve differently next time: the copies this replaced reordered with a swap in one
 * editor and with remove-and-insert in another (a swap moves an item more than one row when the
 * list is filtered), and they re-selected by row number rather than by item, so the same button
 * left the highlight somewhere else depending on the screen. No window or client is needed -
 * these are list operations, which is the point of having them in one place.
 */
class ListEditorSupportTest {
    private static AbstractSelectionList<String> list(String... entries) {
        AbstractSelectionList<String> list =
                new AbstractSelectionList<>(0, 0, 100, 100, 10, (client, entry, x, y) -> {
                });
        list.setEntries(List.of(entries));
        return list;
    }

    @Test
    void moveReordersWithRemoveAndInsert() {
        List<String> items = new ArrayList<>(List.of("a", "b", "c"));
        assertTrue(ListEditorSupport.move(items, "a", 1));
        assertEquals(List.of("b", "a", "c"), items);
        assertTrue(ListEditorSupport.move(items, "a", -1));
        assertEquals(List.of("a", "b", "c"), items);
    }

    @Test
    void movePastTheEndsChangesNothing() {
        List<String> items = new ArrayList<>(List.of("a", "b"));
        assertFalse(ListEditorSupport.move(items, "a", -1), "already first");
        assertFalse(ListEditorSupport.move(items, "b", 1), "already last");
        assertFalse(ListEditorSupport.move(items, null, 1), "nothing selected");
        assertFalse(ListEditorSupport.move(items, "a", 0));
        assertFalse(ListEditorSupport.move(items, "missing", 1));
        assertEquals(List.of("a", "b"), items, "a refused move must not touch the list");
    }

    @Test
    void removeSelectsTheItemThatTookTheGap() {
        List<String> items = new ArrayList<>(List.of("a", "b", "c"));
        assertEquals("b", ListEditorSupport.remove(items, "a"), "the gap is filled by b");
        assertEquals(List.of("b", "c"), items);

        // Removing the last entry leaves the one before it selected.
        assertEquals("b", ListEditorSupport.remove(items, "c"));
        assertEquals(List.of("b"), items);
        assertNull(ListEditorSupport.remove(items, "b"), "an empty list selects nothing");
        assertTrue(items.isEmpty());
    }

    @Test
    void removeIgnoresNothingSelected() {
        List<String> items = new ArrayList<>(List.of("a"));
        assertNull(ListEditorSupport.remove(items, null));
        assertEquals(List.of("a"), items, "a click on no row must not delete the last entry");
    }

    @Test
    void refreshKeepsTheSameItemEvenWhenItsRowChanged() {
        AbstractSelectionList<String> list = list("a", "b", "c");
        list.select(1);
        assertEquals("b", list.selected());
        // "b" is now the third row: a row-based refresh would leave "c" highlighted instead.
        String selected = ListEditorSupport.refresh(list, List.of("a", "c", "b"), list.selected());
        assertEquals("b", selected);
        assertEquals("b", list.selected());
    }

    @Test
    void refreshFallsBackToTheFirstRowWhenTheItemIsGone() {
        AbstractSelectionList<String> list = list("a", "b");
        list.select(1);
        assertEquals("a", ListEditorSupport.refresh(list, List.of("a"), list.selected()));
    }

    @Test
    void refreshOnAnEmptyListSelectsNothing() {
        AbstractSelectionList<String> list = list("a");
        assertNull(ListEditorSupport.refresh(list, List.of(), list.selected()));
    }

    /** Right after inserting, the new item is not in the old selection - it has to be named. */
    @Test
    void refreshKeepingSelectsTheNamedItem() {
        AbstractSelectionList<String> list = list("a");
        ListEditorSupport.refreshKeeping(list, List.of("a", "b"), "b");
        assertEquals("b", list.selected());
    }
}
