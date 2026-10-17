package com.pvzce.client.gui.screens;

import com.pvzce.client.gui.components.Button;
import com.pvzce.client.gui.layout.MenuPageCanvas.Canvas;
import com.pvzce.testutil.ClientHarness;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The old corner-overlap regressions now exercise the original selector's shared layout. */
class TitleScreenTrayTest {
    @Test
    void entriesFitTheWindowAndKeepTheirShape() {
        for (int[] size : new int[][]{{427, 240}, {960, 540}, {640, 320}, {320, 480}, {1280, 540}}) {
            TitleScreen.Layout layout = TitleScreen.layoutForGui(size[0], size[1]);
            assertEquals(layout.left().scale(), layout.right().scale());
            for (TitleScreen.Entry entry : TitleScreen.Entry.values()) {
                Canvas canvas = layout.canvas(entry);
                float x = canvas.x(entry.x);
                float y = canvas.y(entry.top + entry.height);
                float width = canvas.scaled(entry.width);
                float height = canvas.scaled(entry.height);
                assertTrue(x >= 0 && y >= 0, entry + " must be on screen");
                assertTrue(x + width <= size[0] + 0.01F, entry + " exceeds the right edge");
                assertTrue(y + height <= size[1] + 0.01F, entry + " exceeds the top edge");
                assertEquals(entry.width / entry.height, width / height, 0.001F,
                        entry + " must not stretch at a different window aspect ratio");
            }
            assertTrue(layout.left().x(TitleScreen.Entry.PLAYER.x + TitleScreen.Entry.PLAYER.width)
                    < layout.right().x(TitleScreen.Entry.START.x), "player board must clear the menu");
        }
    }

    @Test
    void overlappingTransparentCornersDoNotStealAnotherPlaquesClick() throws Exception {
        try (ClientHarness harness = ClientHarness.create("pvzce-selector-bounds")) {
            TitleScreen menu = new TitleScreen(harness.client());
            // The first four widgets are the four actual stone buttons, in their paint order.
            for (int i = 0; i < 4; i++) {
                Button target = (Button) menu.widgets().get(i);
                double x = target.x() + target.width() / 2D;
                double y = target.y() + target.height() / 2D;
                assertTrue(target.isMouseOver(x, y), target.label() + " must accept its centre");
                for (int other = 0; other < 4; other++) {
                    if (other != i) {
                        assertFalse(menu.widgets().get(other).isMouseOver(x, y),
                                target.label() + " must not activate another entry");
                    }
                }
                assertFalse(target.isMouseOver(target.x() + target.width() - 1,
                        target.y() + target.height() - 1), "transparent upper-right corner");
            }
        }
    }
}
