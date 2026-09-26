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
        // Two floors and a ceiling. The GUI-proportional part (1/240 of the height) only wins on a
        // very tall window; everywhere else the bar is held at MIN_PHYSICAL_HEIGHT device pixels,
        // because it is a pointer target: 44 px at gui_scale 2 is 22 GUI units, and that is what
        // the reported 1440p desktop gets.
        assertEquals(22, Layout.of(GUI_WIDTH, 720, 2F).height(),
                "the reported desktop: 44 physical px at gui_scale 2");
        assertEquals(44, Layout.of(GUI_WIDTH, 240, 1F).height(),
                "an unscaled GUI needs the whole 44 pixels");
        assertEquals(24, Layout.of(GUI_WIDTH, 5760, 2F).height(), "5760/240 wins over the floor");
        for (int guiHeight : new int[]{240, 360, 540, 720, 1080}) {
            for (float scale : new float[]{1F, 1.5F, 2F, 3F}) {
                Layout layout = Layout.of(GUI_WIDTH, guiHeight, scale);
                assertTrue(layout.height() * scale >= 44F,
                        "gui " + guiHeight + " at scale " + scale
                                + " must still be a 44-device-pixel target");
            }
        }
    }

    @Test
    void theBarAnswersAPointerThatIsSlightlyLow() {
        // This is the bug the class shipped with, in one assertion. The report was "the three
        // buttons do nothing": the pointer sat inside the painted bar and outside the region that
        // answered, because the answer used the wrong Y and a bar that ended at its last pixel.
        // The misses were at raw y = 12.7, 14.5 and 19.0 device pixels, i.e. 6..10 GUI units down
        // from the top on the reported desktop.
        Layout layout = Layout.of(GUI_WIDTH, 720, 2F);
        for (double unitsBelowTop : new double[]{0, 6.35, 9.5}) {
            assertTrue(layout.contains(GUI_WIDTH / 2.0, unitsBelowTop),
                    unitsBelowTop + " GUI units below the top is on the bar");
        }
        assertTrue(layout.activationHeight() > layout.height(),
                "the band that answers is a little taller than the paint, on purpose");
        assertFalse(layout.contains(GUI_WIDTH / 2.0, layout.activationHeight()),
                "and it still stops: the strip below belongs to the game");
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
        // Below the region that answers - not below the paint: the band between the two is on
        // purpose (see theBarAnswersAPointerThatIsSlightlyLow). Past it, the game gets the click,
        // which is what keeps the top row of the card bar out of the window chrome.
        assertFalse(layout.contains(600, layout.activationHeight()));
        assertFalse(layout.contains(600, -1));
        assertFalse(layout.contains(-1, 5));
        assertFalse(layout.contains(GUI_WIDTH, 5));
        assertNull(layout.buttonAt(600, layout.activationHeight()));
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
