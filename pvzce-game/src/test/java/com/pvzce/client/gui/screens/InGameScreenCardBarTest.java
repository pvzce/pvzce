package com.pvzce.client.gui.screens;

import com.pvzce.client.PvzceWindow;
import com.pvzce.common.network.packet.PickCardC2S;
import com.pvzce.common.network.packet.SlotInfo;
import com.pvzce.common.tag.TestContent;
import com.pvzce.testutil.ClientHarness;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class InGameScreenCardBarTest {
    @BeforeAll static void load() throws Exception { TestContent.loadBuiltInContentAndTags(); }

    private static void field(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Test
    void arrowClicksScrollThroughTheScreenWithoutPickingOrPlacingACard() throws Exception {
        try (ClientHarness harness = ClientHarness.create("pvzce-card-arrows")) {
            Field singleton = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            singleton.setAccessible(true);
            var window = (PvzceWindow) ((sun.misc.Unsafe) singleton.get(null)).allocateInstance(PvzceWindow.class);
            field(window, "width", 1920);
            field(window, "height", 1080);
            var client = harness.client();
            field(client, "window", window);
            client.config().setGuiScale(2);
            List<SlotInfo> slots = IntStream.range(0, 20)
                    .mapToObj(i -> new SlotInfo(i, "pvzce:pea_shooter", "plant", 0, 0, true)).toList();
            client.level().init("pvzce:yard/adventure/combat_test", 9, 5, slots,
                    List.of(), List.of(), 20, List.of(), List.of(),
                    "pvzce:plant_team", "植物方", List.of());
            client.level().setSun(10000);
            var screen = new InGameScreen(client);
            client.setScreenReplacing(screen);
            var bar = screen.cardBar();
            double leftArrow = 103;
            double rightArrow = screen.rightBound() - 9;
            double y = client.guiHeight() - bar.cardHeight() / 2.0 - 8;
            // Layout is refreshed by picking, as it is by rendering in the real window.
            assertEquals(0, bar.slotAt(138, y));
            assertEquals(-1, bar.slotAt(leftArrow, y));
            assertEquals(-1, bar.slotAt(rightArrow, y));
            screen.dispatchMouseClicked(leftArrow, y, 0); // Disabled at the beginning.
            screen.dispatchMouseClicked(rightArrow, y, 0);
            assertEquals(1, bar.slotAt(138, y));
            assertEquals(-1, screen.selectedCardIndex());
            assertTrue(harness.sentPackets().isEmpty());
            screen.dispatchMouseClicked(leftArrow, y, 0);
            assertEquals(0, bar.slotAt(138, y));
            screen.dispatchMouseClicked(138, y, 0);
            assertEquals(0, screen.selectedCardIndex());
            long picks = harness.sentPackets().stream().filter(PickCardC2S.class::isInstance).count();
            for (int i = 0; i < 30; i++) screen.dispatchMouseClicked(rightArrow, y, 0);
            int lastVisible = bar.slotAt(138, y);
            screen.dispatchMouseClicked(rightArrow, y, 0);
            assertEquals(lastVisible, bar.slotAt(138, y));
            assertEquals(0, screen.selectedCardIndex());
            assertEquals(picks, harness.sentPackets().stream().filter(PickCardC2S.class::isInstance).count());
            assertFalse(bar.click(rightArrow, y, 1));
        }
    }
}
