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

    @Test
    void theShippedLevelsCarryTheSunCard() throws Exception {
        // Sun needs its card in the bar (collectible_without_card is false), so every
        // shipped level has to list the SunBank card - otherwise a level would start with
        // no way to collect sun at all.
        com.pvzce.common.tag.TestContent.loadBuiltInContentAndTags();
        assertFalse(com.pvzce.common.core.BuiltInRegistries.RESOURCES.get(com.pvzce.common.PvzceIds.SUN)
                .collectibleWithoutCard(), "sun is collected through its card");
        for (String name : new String[]{"1_1", "1_2", "1_3"}) {
            var level = com.pvzce.common.core.BuiltInRegistries.LEVELS
                    .get(com.pvzce.api.util.Identifier.withDefaultNamespace("yard/adventure/" + name));
            assertTrue(level.slots().contains(com.pvzce.common.PvzceIds.SUN),
                    name + " must list the SunBank card");
        }
    }
}
