package com.pvzce.client.gui.screens;

import com.pvzce.common.network.packet.SlotInfo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The in-level card bar groups its slots the same way the seed chooser does:
 * resources leftmost, then plants, then tools. The SunBank slot is HUD and
 * must never become a card.
 */
class InGameScreenCardOrderTest {
    private static SlotInfo slot(int index, String defId, String kind) {
        return new SlotInfo(index, defId, kind, 0, 0, true);
    }

    @Test
    void cardBarOrdersResourcesThenPlantsThenTools() {
        List<SlotInfo> ordered = InGameScreen.orderCardSlots(List.of(
                slot(0, "pvzce:pea_shooter", "plant"),
                slot(1, "pvzce:shovel", "tool"),
                slot(2, "pvzce:star", "resource"),
                slot(3, "pvzce:wall_nut", "plant"),
                slot(4, "pvzce:hammer", "tool")));

        assertEquals(List.of("pvzce:star", "pvzce:pea_shooter", "pvzce:wall_nut",
                        "pvzce:shovel", "pvzce:hammer"),
                ordered.stream().map(SlotInfo::defId).toList());
    }

    @Test
    void sunBankNeverBecomesACard() {
        List<SlotInfo> ordered = InGameScreen.orderCardSlots(List.of(
                slot(0, "pvzce:sun", "resource"),
                slot(1, "pvzce:sunflower", "plant")));

        assertEquals(List.of("pvzce:sunflower"), ordered.stream().map(SlotInfo::defId).toList());
    }
}
