package com.pvzce.client.gui.screens;

import com.pvzce.client.PvzceWindow;
import com.pvzce.client.gui.components.Button;
import com.pvzce.testutil.ClientHarness;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AccountScreenTest {
    @Test void offlineScreenKeepsActionsOnscreenAndReturnsToItsParent() throws Exception {
        try (var harness = ClientHarness.create("launcher-account-ui")) {
            var client = harness.client();
            client.config().setGuiScale(2);
            var singleton = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            singleton.setAccessible(true);
            var window = (PvzceWindow) ((sun.misc.Unsafe) singleton.get(null)).allocateInstance(PvzceWindow.class);
            var windowField = client.getClass().getDeclaredField("window");
            windowField.setAccessible(true);
            windowField.set(client, window);
            for (int[] size : new int[][]{{854, 480}, {1920, 1080}, {640, 960}}) {
                for (int dimension = 0; dimension < 2; dimension++) {
                    var field = PvzceWindow.class.getDeclaredField(dimension == 0 ? "width" : "height");
                    field.setAccessible(true);
                    field.setInt(window, size[dimension]);
                }
                var parent = new TitleScreen(client);
                client.setScreenReplacing(parent);
                var screen = new AccountScreen(client);
                client.openScreen(screen);
                screen.initIfNeeded();
                screen.tick();
                for (var widget : screen.widgets()) {
                    assertTrue(widget.x() >= 0 && widget.y() >= 0);
                    assertTrue(widget.x() + widget.width() <= client.guiWidth());
                    assertTrue(widget.y() + widget.height() <= client.guiHeight());
                }
                var logout = (Button) screen.widgets().getFirst();
                assertFalse(logout.isActive());
                var back = (Button) screen.widgets().getLast();
                screen.dispatchMouseClicked(back.x() + back.width() / 2D, back.y() + back.height() / 2D, 0);
                assertSame(parent, client.currentScreen());
                assertEquals("world", client.currentWorld());
            }
        }
    }
}
