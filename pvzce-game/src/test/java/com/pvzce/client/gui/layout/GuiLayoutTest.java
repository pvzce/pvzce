package com.pvzce.client.gui.layout;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuiLayoutTest {
    @Test
    void fittedButtonsNeverOverflowAtSmallGuiScales() {
        for (int guiHeight : new int[]{180, 240, 360, 720}) {
            for (int count : new int[]{1, 3, 4, 5}) {
                int baseHeight = 68;
                int reservedTop = guiHeight / 4;
                int height = GuiLayout.fitHeight(guiHeight, baseHeight, count, reservedTop, 8);
                int gap = GuiLayout.gapFor(height);
                int total = count * height + (count - 1) * gap;
                assertTrue(total <= guiHeight - reservedTop - 8,
                        "height=" + guiHeight + " count=" + count + " total=" + total);
                assertTrue(height >= 18);
            }
        }
    }

    @Test
    void enlargedHeightIsUsedWhenRoomAllows() {
        assertEquals(68, GuiLayout.fitHeight(720, 68, 3, 100, 8));
    }
}
