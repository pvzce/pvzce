package com.pvzce.client.gui;

import com.pvzce.client.gui.WindowTitleBar.Button;
import com.pvzce.client.gui.WindowTitleBar.Layout;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Geometry of the client-drawn window title bar, without a window.
 *
 * <p>This exists because the bar is anchored to the <em>right</em> edge and hit-tested in a
 * coordinate space whose Y grows the other way from the one the bar is drawn in: a sign or an
 * anchor slip puts "close" where "minimize" should be, and on screen that is indistinguishable
 * from a stuck window. The window itself (whether the desktop decorated it) is not testable here;
 * what is testable is every number between that answer and the three clickable rectangles.
 */
class WindowTitleBarTest {
    /** The window the report came from: 2560x1440 framebuffer, gui_scale 2. */
    private static final int GUI_WIDTH = 1280;
    private static final int GUI_HEIGHT = 720;

    @Test
    void barHeightScalesWithTheGuiAndStaysLegible() {
        // 1.9% of the GUI height, with a floor and a ceiling. The floor is what every real desktop
        // hits: a 1440p window at gui_scale 2 is 720 GUI units tall, and 720/240 = 3 would be an
        // invisible sliver, so the bar is 18 units there and stays 18 at any smaller GUI.
        assertEquals(18, Layout.of(GUI_WIDTH, 240).height(), "a tiny GUI clamps to the floor");
        assertEquals(18, Layout.of(GUI_WIDTH, 720).height(),
                "the floor is what the reported 1440p window lands on");
        assertEquals(24, Layout.of(GUI_WIDTH, 5760).height(), "5760/240, above the floor");
        assertEquals(46, Layout.of(GUI_WIDTH, 20_000).height(), "a huge GUI clamps to the ceiling");
    }

    @Test
    void buttonsAreAnchoredToTheRightEdgeInReadingOrder() {
        Layout layout = Layout.of(GUI_WIDTH, GUI_HEIGHT);
        int width = layout.buttonWidth();
        // The three sit flush against the right edge, minimize leftmost and close last.
        assertEquals(GUI_WIDTH, layout.buttonLeft(Button.CLOSE) + width, "close ends at the edge");
        assertEquals(layout.buttonLeft(Button.CLOSE), layout.buttonLeft(Button.MAXIMIZE) + width);
        assertEquals(layout.buttonLeft(Button.MAXIMIZE), layout.buttonLeft(Button.MINIMIZE) + width);
        assertTrue(layout.buttonLeft(Button.MINIMIZE) > 0, "the title must have room to its left");
    }

    @Test
    void eachButtonOwnsItsOwnStripAndNothingElse() {
        Layout layout = Layout.of(GUI_WIDTH, GUI_HEIGHT);
        double middle = layout.height() / 2.0;
        for (Button button : Button.values()) {
            int left = layout.buttonLeft(button);
            int width = layout.buttonWidth();
            // The strip is half-open - [left, left + width) - and its two edges are where an
            // off-by-one puts a click on the neighbouring button.
            assertEquals(button, layout.buttonAt(left, middle), "first pixel of " + button);
            assertEquals(button, layout.buttonAt(left + width - 0.5, middle),
                    "last pixel of " + button);
            assertEquals(button, layout.buttonAt(left + width / 2.0, middle), "middle of " + button);
            if (button != Button.MINIMIZE) {
                assertEquals(Button.values()[button.ordinal() - 1],
                        layout.buttonAt(left - 0.5, middle),
                        "the pixel before " + button + " belongs to its neighbour");
            }
        }
    }

    @Test
    void emptyPartOfTheBarIsChromeWithoutBeingAButton() {
        Layout layout = Layout.of(GUI_WIDTH, GUI_HEIGHT);
        double middle = layout.height() / 2.0;
        assertTrue(layout.contains(5, middle), "the far left of the bar is still the bar");
        assertNull(layout.buttonAt(5, middle), "but it is not a button");
        assertTrue(layout.contains(GUI_WIDTH - 1.0, middle));
    }

    @Test
    void aPointOffTheBarBelongsToTheGame() {
        Layout layout = Layout.of(GUI_WIDTH, GUI_HEIGHT);
        // One pixel below the bar is the game's: that boundary is what keeps a click on the top row
        // of the card bar out of the window chrome.
        assertFalse(layout.contains(600, layout.height()));
        assertFalse(layout.contains(600, -1));
        assertFalse(layout.contains(-1, 5));
        assertFalse(layout.contains(GUI_WIDTH, 5));
        assertNull(layout.buttonAt(600, layout.height()));
    }

    @Test
    void closeIsTheRightmostButtonAtEveryScale() {
        // The order is the desktop convention, not a layout accident: a bar whose X moved because
        // the window got wider would be worse than no bar.
        for (int guiHeight : new int[]{240, 360, 720, 1440}) {
            Layout layout = Layout.of(GUI_WIDTH, guiHeight);
            assertTrue(layout.buttonLeft(Button.MINIMIZE)
                            < layout.buttonLeft(Button.MAXIMIZE),
                    "minimize left of maximize at gui height " + guiHeight);
            assertTrue(layout.buttonLeft(Button.MAXIMIZE) < layout.buttonLeft(Button.CLOSE),
                    "maximize left of close at gui height " + guiHeight);
        }
    }
}
