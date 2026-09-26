package com.pvzce.client.gui.components;

import com.pvzce.client.PvzceClient;
import com.pvzce.client.input.ScrollRegion;
import com.pvzce.client.renderer.SpriteRenderer;

import java.util.ArrayList;
import java.util.List;

/**
 * A simple scrollable selection list (world list / level list). Entries are
 * rendered through a callback; selected entries are highlighted.
 *
 * <p>Row geometry is defined once: {@link #rowTop(int)} is what render uses and
 * {@link #indexAt(double)} is its exact inverse, so clicking a row selects the row
 * that was drawn there. The two used to be computed separately and disagreed at
 * each row's lower edge (the click selected the row above the highlight), and the
 * scroll offset clamp existed only in the render path. Rows are also clipped to the
 * list now - a long label used to overflow the list box.
 */
public class AbstractSelectionList<E> extends AbstractWidget {
    public interface EntryRenderer<E> {
        void render(PvzceClient client, E entry, int x, int y);
    }

    private static final int SCROLLBAR_WIDTH = 6;

    private final List<E> entries = new ArrayList<>();
    private final int entryHeight;
    private EntryRenderer<E> entryRenderer;
    private int selectedIndex = -1;
    private int scrollOffset;
    /** What a click on a row does after it moves the selection; null for nothing. */
    private java.util.function.Consumer<E> onRowClick;

    public AbstractSelectionList(int x, int y, int width, int height, int entryHeight, EntryRenderer<E> entryRenderer) {
        super(x, y, width, height);
        this.entryHeight = Math.max(1, entryHeight);
        this.entryRenderer = entryRenderer;
    }

    /**
     * Replaces the row renderer.
     *
     * <p>Public because callers that build a bespoke list in place ({@code EditorScreen}'s
     * card pool, {@code WaveEditorModel}'s wave table) need the renderer to close over
     * widget state that only exists after the list is constructed. A subclass could
     * not do this from outside its own constructor.
     */
    public void setEntryRenderer(EntryRenderer<E> entryRenderer) {
        this.entryRenderer = entryRenderer;
    }

    /**
     * Runs after a click moves the selection onto a row.
     *
     * <p>For a list that has no separate confirm step - the title screen's player picker, where
     * a name <em>is</em> the start button. The callback lives on the widget rather than on the
     * screen because the widget consumes its clicks: the screen's own {@code onMouseClicked}
     * hook only runs when no widget took the click, so "select, then let the screen decide" is
     * not available to a list at all.
     */
    public void setOnRowClick(java.util.function.Consumer<E> onRowClick) {
        this.onRowClick = onRowClick;
    }

    public void setEntries(List<E> entries) {
        this.entries.clear();
        this.entries.addAll(entries);
        // Rows are laid out bottom-up, so index 0 is the bottom row. A fresh list
        // must therefore select index 0, not "whatever index was selected before":
        // -1 means "nothing yet", and leaving it there meant a newly filled list had
        // no highlight, no scroll position and no selected item for its owner to read.
        if (selectedIndex < 0 || selectedIndex >= entries.size()) {
            selectedIndex = entries.isEmpty() ? -1 : 0;
        }
        scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll()));
    }

    public List<E> entries() {
        return List.copyOf(entries);
    }

    public E selected() {
        return selectedIndex >= 0 && selectedIndex < entries.size() ? entries.get(selectedIndex) : null;
    }

    public int selectedIndex() {
        return selectedIndex;
    }

    public void select(int index) {
        if (index >= -1 && index < entries.size()) {
            selectedIndex = index;
        }
    }

    public int entryHeight() {
        return entryHeight;
    }

    /**
     * How far the list has been scrolled, in pixels.
     *
     * <p>Exposed because a screen that draws a row's interior itself - rather than through {@link
     * EntryRenderer} - has to answer the same question the list does: "which row is at this point".
     * The shop hit-tests its rows to buy them, and deriving that from the widget's own offset is
     * what keeps the drawn row and the clickable row together; the first version recomputed the
     * rows from the list's top edge instead, which is only correct while the list has not scrolled.
     *
     * <p>Clamped, so it is the value {@link #render} actually draws with and not a raw accumulator.
     */
    public int scrollOffset() {
        return Math.min(Math.max(0, scrollOffset), maxScroll());
    }

    public int visibleCount() {
        return Math.max(1, height / entryHeight);
    }

    private int maxScroll() {
        return Math.max(0, entries.size() * entryHeight - height);
    }

    /** First entry index currently drawn; clamped so the last page stays full. */
    private int firstVisible() {
        return Math.min(scrollOffset / entryHeight, Math.max(0, entries.size() - visibleCount()));
    }

    /** Screen y of the bottom of visible row {@code i} (rows are laid out bottom-up). */
    private int rowBottom(int i) {
        return y + height - (i + 1) * entryHeight;
    }

    /**
     * The entry index drawn at a GUI point, or -1 outside the rows.
     *
     * <p>Tests the same rectangles {@link #render} draws rather than recomputing
     * the row from the scroll offset, so the two cannot disagree at a boundary.
     */
    private int indexAt(double guiX, double guiY) {
        if (guiX >= x + width - SCROLLBAR_WIDTH) {
            // The scrollbar column is not a row; clicking it must not change selection.
            return -1;
        }
        int first = firstVisible();
        for (int i = 0; i < visibleCount(); i++) {
            int bottom = rowBottom(i);
            if (guiY >= bottom && guiY < bottom + entryHeight) {
                int index = first + i;
                return index < entries.size() ? index : -1;
            }
        }
        return -1;
    }

    @Override
    public void render(PvzceClient client) {
        if (!visible) {
            return;
        }
        renderBackground(client, 0.04F, 0.06F, 0.03F, 0.8F);
        client.clipping().push(x, y, width, height);
        try {
            int visibleCount = visibleCount();
            int first = firstVisible();
            for (int i = 0; i < visibleCount; i++) {
                int index = first + i;
                if (index >= entries.size()) {
                    break;
                }
                int entryY = rowBottom(i);
                if (index == selectedIndex) {
                    SpriteRenderer.solid(x + 2, entryY, width - 4, entryHeight - 2, 0, 0.4F, 0.55F, 0.25F, 0.8F);
                }
                entryRenderer.render(client, entries.get(index), x + 6, entryY);
            }
        } finally {
            client.clipping().pop();
        }
        if (entries.size() * entryHeight > height) {
            float track = height - 4;
            float thumb = Math.max(20, track * Math.min(1F, (float) visibleCount() / entries.size()));
            float ratio = scrollOffset / (float) Math.max(1, maxScroll());
            SpriteRenderer.solid(x + width - SCROLLBAR_WIDTH, y + 2, 4, track, 0, 0.15F, 0.15F, 0.15F, 1F);
            SpriteRenderer.solid(x + width - SCROLLBAR_WIDTH, y + 2 + (track - thumb) * ratio, 4, thumb,
                    0, 0.7F, 0.7F, 0.7F, 1F);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double guiY, int button) {
        if (!isMouseOver(mouseX, guiY)) {
            return false;
        }
        if (button == 0) {
            int index = indexAt(mouseX, guiY);
            if (index >= 0) {
                selectedIndex = index;
                if (onRowClick != null) {
                    onRowClick.accept(entries.get(index));
                }
            }
        }
        return true;
    }

    @Override
    public void mouseScrolled(double mouseX, double guiY, double amount) {
        scrollOffset = Math.max(0, Math.min(maxScroll(), scrollOffset - (int) (amount * entryHeight)));
    }

    /**
     * This list is a vertical scroll region, one row per step of finger travel.
     *
     * <p>{@link ScrollRegion.Swipe#MIRRORS_WHEEL} rather than "content follows the finger" because
     * the rows run bottom-up ({@link #rowBottom} draws index 0 at the bottom), which makes the wheel
     * the natural direction here already - a finger moving up means wheel up, exactly like today's
     * wheel.
     *
     * <p>Only while there is something to scroll: a list that fits stays an ordinary click target,
     * so "can it scroll" and "is this a scroll region" remain one answer. A press on a list that
     * does not overflow therefore behaves exactly as it did before this existed.
     */
    @Override
    public ScrollRegion scrollRegionAt(double mouseX, double guiY) {
        if (!isMouseOver(mouseX, guiY) || maxScroll() == 0) {
            return null;
        }
        return ScrollRegion.mirrorsWheel(ScrollRegion.Axis.VERTICAL, entryHeight);
    }
}
