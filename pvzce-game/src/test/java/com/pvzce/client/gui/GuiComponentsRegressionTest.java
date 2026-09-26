package com.pvzce.client.gui;

import com.pvzce.client.gui.components.AbstractSelectionList;
import com.pvzce.client.gui.components.EditBox;
import com.pvzce.client.gui.components.Slider;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the GUI defects that do not need a window: keyboard focus,
 * slider change/commit semantics, list row hit-testing and the shared text helpers.
 */
class GuiComponentsRegressionTest {
    /**
     * Exactly one text field may own the keyboard. Focus used to be a one-way latch
     * (set to "the mouse is over me" and never cleared), so clicking a second field
     * focused it too and the first one kept swallowing every keystroke.
     */
    @Test
    void exactlyOneEditBoxOwnsTheKeyboard() {
        FocusManager focus = new FocusManager();
        EditBox first = new EditBox(0, 0, 100, 20, () -> {
        });
        EditBox second = new EditBox(0, 30, 100, 20, () -> {
        });
        first.setFocusManager(focus);
        second.setFocusManager(focus);

        assertFalse(first.isFocused());
        assertFalse(second.isFocused());

        first.mouseClicked(10, 10, 0);
        assertTrue(first.isFocused(), "clicking a field must focus it");
        assertTrue(focus.hasTextFocus());

        // Clicking the second field moves the keyboard; the first must lose it.
        second.mouseClicked(10, 40, 0);
        assertTrue(second.isFocused(), "the newly clicked field must be focused");
        assertFalse(first.isFocused(), "the previously focused field must be released");

        // And the keystroke lands in the second field only.
        second.charTyped('a');
        assertEquals("a", second.value());
        assertEquals("", first.value(), "the old field must not receive input");
    }

    /** A focused field consumes every character so input cannot fall through. */
    @Test
    void aFocusedFieldConsumesUnhandledCharacters() {
        FocusManager focus = new FocusManager();
        EditBox box = new EditBox(0, 0, 100, 20, () -> {
        });
        box.setFocusManager(focus);
        box.mouseClicked(10, 10, 0);

        assertTrue(box.charTyped('\u0001'), "control characters must still be consumed while focused");
        assertEquals("", box.value());
        assertFalse(box.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_F1), "unhandled keys stay available");
    }

    /** Clearing focus releases the field. */
    @Test
    void clearingFocusReleasesTheField() {
        FocusManager focus = new FocusManager();
        EditBox box = new EditBox(0, 0, 100, 20, () -> {
        });
        box.setFocusManager(focus);
        box.mouseClicked(10, 10, 0);
        assertTrue(focus.hasTextFocus());

        focus.clear();
        assertFalse(box.isFocused());
        assertFalse(focus.hasTextFocus());
    }

    /**
     * The change callback fires only on a real change, and committing is a separate
     * event. The config screen persists from the change callback, so firing it for
     * an unchanged clamped value rewrote the config file on every drag frame.
     */
    @Test
    void sliderOnlyReportsRealChangesAndCommitsOnce() {
        List<Float> changes = new ArrayList<>();
        int[] commits = {0};
        Slider slider = new Slider(0, 0, 100, 20, 0F, 1F, 0.5F, s -> changes.add(s.value()));
        slider.onCommit(() -> commits[0]++);

        slider.setValue(0.5F);
        assertTrue(changes.isEmpty(), "an unchanged value must not fire the callback");

        slider.setValue(0.75F);
        assertEquals(List.of(0.75F), changes);

        // Clamping to the same end twice is still one change.
        slider.setValue(2F);
        slider.setValue(3F);
        assertEquals(List.of(0.75F, 1F), changes, "clamping to the same value is not a new change");

        assertEquals(0, commits[0], "committing only happens on release");
        slider.mouseReleased(0, 0, 0);
        assertEquals(0, commits[0], "releasing without a drag must not commit");

        slider.mouseClicked(50, 10, 0);
        slider.mouseReleased(50, 10, 0);
        assertEquals(1, commits[0], "a drag commits exactly once");
    }

    /**
     * A click on a row selects the row that was drawn there. Render and hit-test
     * used to compute the row separately and disagreed at each row's lower edge.
     */
    @Test
    void listClicksSelectTheRowThatWasDrawn() {
        AbstractSelectionList<String> list = new AbstractSelectionList<>(0, 0, 200, 100, 20,
                (client, entry, x, y) -> {
                });
        list.setEntries(List.of("a", "b", "c", "d", "e"));

        // Rows are laid out bottom-up: row 0 occupies y in [80, 100).
        list.mouseClicked(10, 90, 0);
        assertEquals(0, list.selectedIndex(), "the bottom row is index 0");

        list.mouseClicked(10, 10, 0);
        assertEquals(4, list.selectedIndex(), "the top row is index 4");

        // The lower edge of a row belongs to that row, not to the one above it.
        list.mouseClicked(10, 80, 0);
        assertEquals(0, list.selectedIndex(), "the lower edge of row 0 still selects row 0");
        list.mouseClicked(10, 60, 0);
        assertEquals(1, list.selectedIndex(), "the lower edge of row 1 selects row 1");
    }

    /** Clicking the scrollbar column must not change the selection. */
    @Test
    void listScrollbarColumnIsNotARow() {
        AbstractSelectionList<String> list = new AbstractSelectionList<>(0, 0, 200, 100, 20,
                (client, entry, x, y) -> {
                });
        list.setEntries(List.of("a", "b", "c", "d", "e"));
        list.mouseClicked(10, 90, 0);
        int before = list.selectedIndex();

        list.mouseClicked(198, 10, 0);
        assertEquals(before, list.selectedIndex(), "the scrollbar column must not select a row");
    }

    /** Scrolling is clamped to the real content extent. */
    @Test
    void listScrollIsClamped() {
        AbstractSelectionList<String> list = new AbstractSelectionList<>(0, 0, 200, 40, 20,
                (client, entry, x, y) -> {
                });
        list.setEntries(List.of("a", "b", "c", "d", "e"));
        list.mouseScrolled(10, 10, -100);
        // Scrolled to the end: the bottom entry is still reachable.
        list.mouseClicked(10, 10, 0);
        assertEquals(4, list.selectedIndex());
    }

    /**
     * {@link AbstractSelectionList#scrollOffset()} reports what the list draws with.
     *
     * <p>The getter exists for a screen that hit-tests its own rows - the shop buys the row under
     * the cursor - and its whole value is being the <em>clamped</em> offset: a raw accumulator would
     * let that screen compute a first visible row past the end of its list.
     */
    @Test
    void listScrollOffsetIsTheClampedOneItDrawsWith() {
        AbstractSelectionList<String> list = new AbstractSelectionList<>(0, 0, 200, 40, 20,
                (client, entry, x, y) -> {
                });
        list.setEntries(List.of("a", "b", "c", "d", "e"));
        assertEquals(0, list.scrollOffset(), "a fresh list is at the top");

        // Five rows of 20 in a 40-tall box: the furthest down it can go is 60.
        list.mouseScrolled(10, 10, -1000);
        assertEquals(60, list.scrollOffset(), "scrolled past the end clamps to the content extent");

        list.mouseScrolled(10, 10, 1000);
        assertEquals(0, list.scrollOffset(), "and back up clamps to the first row");
    }

    /** The shared text helpers behave the same for every caller. */
    @Test
    void sharedTextHelpersAreConsistent() {
        assertEquals("pea_shooter", GuiText.shortId("pvzce:pea_shooter"));
        assertEquals("pea_shooter", GuiText.shortId("pea_shooter"));
        assertEquals("", GuiText.shortId((String) null));

        assertEquals(1.5F, GuiText.parseFloat("1.5", 0F), 0.0001F);
        assertEquals(0F, GuiText.parseFloat("", 0F), 0.0001F);
        assertEquals(0F, GuiText.parseFloat(null, 0F), 0.0001F);
        assertEquals(0F, GuiText.parseFloat("abc", 0F), 0.0001F);
        assertEquals(1F, GuiText.parseFloat("9", 0F, 0F, 1F), 0.0001F);

        assertEquals(5, GuiText.parseInt("5", 0));
        assertEquals(0, GuiText.parseInt("nope", 0));
        assertEquals(3, GuiText.parseInt("99", 0, 0, 3));

        assertEquals("50%", GuiText.formatPercent(0.5F));
        assertEquals("1.5", GuiText.formatFloat(1.5F));
    }

    /**
     * An absent or unrecognised status must not be dressed up as a finished level.
     *
     * <p>What a "进行中" card says is a wording choice and lives in the one place both
     * screens read ({@code GuiStatusText}); what matters here is that "we do not know"
     * never renders as "已通关".
     */
    @Test
    void anUnknownSaveStatusIsNotShownAsCompleted() {
        assertEquals("", GuiStatusText.label(null));
        assertEquals("", GuiStatusText.label("nonsense"));
        assertEquals("", GuiStatusText.detail(null));
        assertNotEquals(GuiStatusText.label(null), GuiStatusText.label(GuiStatusText.COMPLETED));
    }
}
