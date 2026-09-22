package com.pvzce.client.gui.screens;

import com.pvzce.common.network.packet.SlotInfo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Which card a glove move is sent with.
 *
 * <p>Both halves of the move - the lift and the drop - go to the <em>glove's</em> slot, and it is
 * looked up in the bar rather than remembered from the other half. Resolving it any other way is
 * what broke the drop: falling back to the card that grants the carried plant sends a
 * <em>plant</em> card down the tool path, and the server answers "不是工具卡" while the plant
 * stays in hand.
 */
class InGameScreenCarrySlotTest {
    private static SlotInfo card(int index, String defId, String kind) {
        return new SlotInfo(index, defId, kind, 0, 0, true);
    }

    @Test
    void theDropGoesToTheGloveAndNothingElse() {
        List<SlotInfo> bar = List.of(
                card(0, "pvzce:sun", "resource"),
                card(1, "pvzce:pea_shooter", "plant"),
                card(2, "pvzce:shovel", "tool"),
                card(3, "pvzce:glove", "tool"));

        assertEquals(3, InGameScreen.gloveSlotIn(bar),
                "the glove's own index, whatever else the bar holds");
    }

    @Test
    void aBarWithoutAGloveHasNoDropSlot() {
        assertEquals(-1, InGameScreen.gloveSlotIn(List.of(
                card(0, "pvzce:pea_shooter", "plant"),
                card(1, "pvzce:shovel", "tool"))));
        assertEquals(-1, InGameScreen.gloveSlotIn(List.of()));
        assertEquals(-1, InGameScreen.gloveSlotIn(null));
    }
}
