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

    /** The first cell opens the shop; the second opens the packs page. */
    @Test
    void eachTrayCellOpensItsPage() throws Exception {
        PvzceClient client = newClient();

        TitleScreen menu = new TitleScreen(client);
        client.setScreenReplacing(menu);
        TitleScreen.TrayCell shop = menu.trayCell(0);
        TitleScreen.TrayCell packs = menu.trayCell(1);

        menu.dispatchMouseClicked(shop.centerX(), shop.centerY(), 0);
        assertInstanceOf(ShopScreen.class, client.currentScreen(),
                "the coin cell opens the shop");

        menu.dispatchMouseClicked(packs.centerX(), packs.centerY(), 0);
        assertInstanceOf(PackScreen.class, client.currentScreen(),
                "the seed-packet cell opens the packs page");
    }

    /**
     * The two cells do not overlap, and neither reaches into the menu column.
     *
     * <p>The menu column's left edge is what the tray had to be built around: three earlier
     * versions drew cells underneath the 开始游戏 button. The column starts at the same x the
     * buttons are placed at, so this measures against the real widget rather than a constant.
     */
    @Test
    void theCellsSitInsideTheCornerAndClearTheMenu() throws Exception {
        PvzceClient client = newClient();
        TitleScreen menu = new TitleScreen(client);
        client.setScreenReplacing(menu);

        TitleScreen.TrayCell shop = menu.trayCell(0);
        TitleScreen.TrayCell packs = menu.trayCell(1);
        float[] tray = menu.trayBounds();

        assertTrue(packs.centerX() > shop.centerX(),
                "the two cells run left to right, not stacked on each other");

        int menuLeft = Integer.MAX_VALUE;
        for (var widget : menu.widgets()) {
            menuLeft = Math.min(menuLeft, widget.x());
        }
        assertTrue(tray[0] + tray[2] <= menuLeft,
                "the tray's right edge (" + (tray[0] + tray[2]) + ") must clear the menu column ("
                        + menuLeft + ")");
    }

    /** A click on the plate between the cells belongs to the plate, not to a page. */
    @Test
    void aClickOnThePlateItselfOpensNothing() throws Exception {
        PvzceClient client = newClient();
        TitleScreen menu = new TitleScreen(client);
        client.setScreenReplacing(menu);
        TitleScreen.TrayCell shop = menu.trayCell(0);
        float[] tray = menu.trayBounds();

        // The plate's bottom-left corner: inside the tray, outside both cells (the cells are
        // inset by the plate's own padding).
        menu.dispatchMouseClicked(tray[0] + 1F, tray[1] + 1F, 0);

        assertSame(menu, client.currentScreen(), "the tray's frame is not a button");
    }
}
