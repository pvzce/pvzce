package com.pvzce.client.gui.screens;

import com.pvzce.common.network.packet.SlotInfo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SunBank is a selectable slot: no sun card means no in-level bank HUD. */
class InGameScreenSunBankTest {
    @Test
    void sunBankVisibilityFollowsSelectedSlots() {
        assertFalse(InGameScreen.hasSunBank(List.of(
                new SlotInfo(0, "pvzce:pea_shooter", "plant", 100, 0, true))));
        assertTrue(InGameScreen.hasSunBank(List.of(
                new SlotInfo(0, "pvzce:pea_shooter", "plant", 100, 0, true),
                new SlotInfo(1, "pvzce:sun", "resource", 0, 0, true))));
    }
}
