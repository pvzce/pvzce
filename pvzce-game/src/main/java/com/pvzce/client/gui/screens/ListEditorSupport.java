package com.pvzce.client.gui.screens;

import com.pvzce.client.gui.components.AbstractSelectionList;

import java.util.List;
import java.util.function.Consumer;

/**
 * Shared list-editing algorithms for the data editors.
 *
 * <p>The wave editor, the music editor and the card-pool editor each grew their own
 * copy of "replace the entries, keep the selection if the item is still there,
 * otherwise fall back to the first item", "replace the entries while keeping one
 * specific item selected", and "move an item up or down". The copies had already
 * diverged - one reordered with a swap, another with remove-and-insert - so the same
 * button behaved differently depending on which editor you were in. One
 * implementation now, with the divergence resolved in favour of remove-and-insert
 * (a swap is wrong when the list is filtered, because the item can jump more than
 * one row).
 */
public final class ListEditorSupport {
    /**
     * Replaces a list's entries and re-establishes a selection.
     *
     * @param current the item that should stay selected, or {@code null}
     * @return the item now selected, or {@code null} when the list is empty
     */
    public static <T> T refresh(AbstractSelectionList<T> list, List<T> items, T current) {
        list.setEntries(items);
        if (current != null) {
            int index = items.indexOf(current);
            if (index >= 0) {
                list.select(index);
            }
        }
        T selected = list.selected();
        if (selected == null && !items.isEmpty()) {
            list.select(0);
            selected = list.selected();
        }
        return selected;
    }

    /**
     * Replaces the entries but keeps {@code keep} selected even if it is not yet in
     * the list (used right after inserting a new item).
     */
    public static <T> void refreshKeeping(AbstractSelectionList<T> list, List<T> items, T keep) {
        list.setEntries(items);
        int index = items.indexOf(keep);
        if (index >= 0) {
            list.select(index);
        }
    }

    /**
     * Moves {@code item} by {@code delta} places inside {@code items}.
     *
     * @return true when the list changed and the caller should refresh
     */
    public static <T> boolean move(List<T> items, T item, int delta) {
        if (item == null || delta == 0) {
            return false;
        }
        int from = items.indexOf(item);
        int to = from + delta;
        if (from < 0 || to < 0 || to >= items.size()) {
            return false;
        }
        items.remove(from);
        items.add(to, item);
        return true;
    }

    /** Removes {@code item}; returns the item that should be selected afterwards. */
    public static <T> T remove(List<T> items, T item) {
        if (item == null) {
            return null;
        }
        int index = items.indexOf(item);
        if (index < 0) {
            return null;
        }
        items.remove(index);
        if (items.isEmpty()) {
            return null;
        }
        return items.get(Math.min(index, items.size() - 1));
    }

    /** Runs {@code action} with the current selection, ignoring a null selection. */
    public static <T> void withSelection(T selected, Consumer<T> action) {
        if (selected != null) {
            action.accept(selected);
        }
    }

    private ListEditorSupport() {
    }
}
