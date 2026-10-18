package com.pvzce.client.gui.screens;

import com.pvzce.client.PvzceWindow;
import com.pvzce.client.gui.components.AbstractSelectionList;
import com.pvzce.client.gui.components.Button;
import com.pvzce.common.network.packet.BuyShopItemC2S;
import com.pvzce.common.shop.ShopItems;
import com.pvzce.testutil.ClientHarness;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ShopScreenInteractionTest {
    @Test
    void scaledCatalogueRowsSelectWithoutSpendingAndOnlyPurchaseSendsTheSelectedItem() throws Exception {
        try (var harness = ClientHarness.create("shop-scaled-rows")) {
            var client = harness.client();
            client.config().setGuiScale(2);
            var unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            var window = (PvzceWindow) ((sun.misc.Unsafe) unsafeField.get(null)).allocateInstance(PvzceWindow.class);
            var windowField = client.getClass().getDeclaredField("window");
            windowField.setAccessible(true);
            windowField.set(client, window);
            for (int[] size : new int[][]{{854, 480}, {1920, 1080}, {640, 960}}) {
                for (int dimension = 0; dimension < 2; dimension++) {
                    var field = PvzceWindow.class.getDeclaredField(dimension == 0 ? "width" : "height");
                    field.setAccessible(true);
                    field.setInt(window, size[dimension]);
                }
                client.setProfile(5000, List.of(), false);
                var screen = new ShopScreen(client);
                client.setScreenReplacing(screen);
                screen.initIfNeeded();
                var list = screen.widgets().stream().filter(AbstractSelectionList.class::isInstance)
                        .map(AbstractSelectionList.class::cast).findFirst().orElseThrow();
                var purchase = screen.widgets().stream().filter(Button.class::isInstance).map(Button.class::cast)
                        .filter(button -> !button.label().equals("返回")).findFirst().orElseThrow();
                assertTrue(purchase.x() > list.x() + list.width(), "purchase action must stay inside the detail column");
                assertEquals(ShopItems.ITEMS.getFirst(), list.selected());
                assertTrue(list.visibleCount() >= ShopItems.ITEMS.size(), "all current items must fit after scaling");
                for (int row = 0; row < ShopItems.ITEMS.size(); row++) {
                    long previous = harness.sentPackets().stream().filter(BuyShopItemC2S.class::isInstance).count();
                    screen.dispatchMouseClicked(list.x() + list.width() / 2D,
                            list.y() + list.height() - (row + .5) * list.entryHeight(), 0);
                    assertEquals(previous, harness.sentPackets().stream().filter(BuyShopItemC2S.class::isInstance).count());
                    assertEquals(5000, client.profile().coins());
                    screen.dispatchMouseClicked(purchase.x() + purchase.width() / 2D,
                            purchase.y() + purchase.height() / 2D, 0);
                    var sent = harness.sentPackets().stream().filter(BuyShopItemC2S.class::isInstance)
                            .map(BuyShopItemC2S.class::cast).toList();
                    assertEquals(previous + 1, sent.size());
                    assertEquals(ShopItems.ITEMS.get(row).id().toString(), sent.getLast().itemId());
                }
                client.setProfile(0, List.of(), false);
                screen.tick();
                assertFalse(purchase.isActive(), "a new wallet snapshot must update affordability");
            }
        }
    }
}
