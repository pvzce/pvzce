package com.pvzce.client.gui.hud.cardbar;

import com.pvzce.common.network.packet.SlotInfo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which cards ride a conveyor belt and which stay beside it.
 *
 * <p>The reported bug: a mutation's belt dealt the player's plants while the shovel and the
 * watering can rode along on the treads, so the tray - sized to the belt's plant capacity -
 * overflowed and a tool looked like a card the belt was about to hand over. The bar has to
 * partition itself: plants on the belt, everything else fixed outside, the sun card in neither
 * list because the HUD draws it as a bank.
 */
class BeltCardBarTest {
    private static SlotInfo card(int index, String id, String kind) {
        return new SlotInfo(index, id, kind, 0, 0, true);
    }

    @Test
    void plantsRideAndToolsStayBeside() {
        SlotInfo sun = card(0, "pvzce:sun", "resource");
        SlotInfo peashooter = card(1, "pvzce:pea_shooter", "plant");
        SlotInfo shovel = card(2, "pvzce:shovel", "tool");
        SlotInfo wateringCan = card(3, "pvzce:watering_can", "tool");
        SlotInfo sunflower = card(4, "pvzce:sunflower", "plant");

        BeltCardBar.Split split = BeltCardBar.split(
                List.of(sun, peashooter, shovel, wateringCan, sunflower));

        assertEquals(List.of(peashooter, sunflower), split.belt(),
                "the belt deals the plants, in the server's order");
        assertEquals(List.of(shovel, wateringCan), split.side(),
                "the tools are on the bar but not on the belt");
        assertTrue(split.belt().stream().noneMatch(slot -> "pvzce:sun".equals(slot.defId())),
                "the sun card is drawn as the bank, not as a belt card");
        assertTrue(split.side().stream().noneMatch(slot -> "pvzce:sun".equals(slot.defId())),
                "and it is not drawn beside the tray either");
    }

    /** A bar with only tools still lays them out; the belt simply deals nothing. */
    @Test
    void aBarWithNoPlantsLeavesTheBeltEmpty() {
        BeltCardBar.Split split = BeltCardBar.split(List.of(
                card(0, "pvzce:sun", "resource"),
                card(1, "pvzce:shovel", "tool")));
        assertEquals(List.of(), split.belt());
        assertEquals(1, split.side().size());
    }
}
