package com.pvzce.client.gui.screens;

import com.pvzce.client.PvzceClient;
import com.pvzce.testutil.ClientHarness;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The title screen's corner tray: two cells that open the shop and the packs page.
 *
 * <p>Worth a test rather than a screenshot because the tray's geometry is the whole difficulty. It
 * is drawn from the same rectangles that are hit-tested, but those rectangles were moved four times
 * while this page was being laid out - a vertical plate, a horizontal one, with labels, without -
 * and every version had to fit between the menu column's left edge and the window's. A click a few
 * units off the cell is silent: nothing happens and the screenshot still looks fine.
 *
 * <p>The page lives on the title screen because that is where the two pages are reached from, so
 * this also pins the entry points themselves: the shop and the packs page were menu rows before,
 * and the tray is now the only way to either of them.
 */
class TitleScreenTrayTest {
    private final List<ClientHarness> harnesses = new ArrayList<>();

    @AfterEach
    void closeHarnesses() {
        harnesses.forEach(ClientHarness::close);
    }

    private PvzceClient newClient() throws Exception {
        ClientHarness harness = ClientHarness.create("pvzce-tray");
        harnesses.add(harness);
        return harness.client();
    }

    /**
     * The icons are sized against the menu buttons, not by a constant.
     *
     * <p>What this pins is the second bug report: at 1080p/2x the tray looked like a doodle next to
     * a 68-unit-tall menu column, because its icons were a fixed 22 units. Three button heights are
     * checked rather than one, because the failure mode was a tray that only looked right at the
     * size it happened to be drawn at - and the tray must also stay narrower than the column it
     * sits under, which is the constraint that ruled out a fixed wide plate.
     */
    @Test
    void theTrayScalesWithTheMenuButtons() {
        // Two real windows: the default (GUI 427x240) and 1080p at 2x UI (960x540), plus a small
        // one no window would open but a 4x-UI display can produce.
        for (int[] size : new int[][]{{427, 240}, {640, 320}, {960, 540}}) {
            int buttonHeight = TitleScreen.menuButtonHeight(size[1]);
            TitleScreen.TrayLayout tray = TitleScreen.trayForGui(size[0], buttonHeight);
            assertTrue(tray.iconBox() >= buttonHeight * 0.4F,
                    "a " + buttonHeight + "-unit button leaves a " + tray.iconBox()
                            + "-unit icon: the corner would look empty beside it");
            assertTrue(tray.iconBox() <= buttonHeight * 1.1F,
                    "and not bigger than the button it stands beside (" + tray.iconBox() + ")");
            assertTrue(tray.width() < buttonHeight * 6.5F,
                    "the tray has to stay narrow enough for the corner: " + tray.width());
        }
    }

    /**
     * The tray stays inside the window and clear of the menu column, at every size.
     *
     * <p>Two bugs are behind this: a tray that grew under the player board (whose click is tested
     * first, so its cells opened the player picker), and a board-anchored tray at GUI 960x540 that
     * came out ending 126 units below the bottom edge - i.e. invisible, which is what the 1080p
     * screenshot showed.
     */
    @Test
    void theTrayFitsTheWindowItIsDrawnIn() {
        for (int[] size : new int[][]{{427, 240}, {960, 540}, {640, 320}}) {
            int buttonHeight = TitleScreen.menuButtonHeight(size[1]);
            TitleScreen.TrayLayout tray =
                    TitleScreen.trayForGui(size[0], buttonHeight);
            assertTrue(tray.y() >= 8F, "at GUI " + size[0] + "x" + size[1] + " the tray starts at "
                    + tray.y() + ", off the bottom");
            assertTrue(tray.y() + tray.height() <= size[1],
                    "at GUI " + size[0] + "x" + size[1] + " the tray ends at "
                            + (tray.y() + tray.height()) + ", past the top edge");
            // The menu column is centred and 280 wide, or the window minus 24 when it is narrow.
            int buttonWidth = Math.min(280, size[0] - 24);
            float menuLeft = (size[0] - buttonWidth) / 2F;
            assertTrue(tray.x() + tray.width() <= menuLeft + 0.5F,
                    "at GUI " + size[0] + "x" + size[1] + " the tray reaches "
                            + (tray.x() + tray.width()) + " into the menu column at " + menuLeft);
        }
    }

}
