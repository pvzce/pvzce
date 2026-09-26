package com.pvzce.client.gui;

import com.pvzce.client.PvzceClient;
import com.pvzce.client.gui.components.AbstractSelectionList;
import com.pvzce.client.gui.components.Dialog;
import com.pvzce.client.gui.components.Slider;
import com.pvzce.client.input.ScrollRegion;
import com.pvzce.testutil.ClientHarness;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Which widget answers "would a press here scroll?" - the question the touch gesture layer asks
 * before it lets a press become a swipe.
 *
 * <p>Two ways to get this wrong are worth pinning: a list inside a dialog is not in the screen's own
 * widget list (so it is easy to leave it unreachable), and a slider needs the whole press-drag-release
 * triple (so it must win over any region drawn under it, or it would be dead on a touchscreen).
 */
class ScreenScrollRegionTest {
    private final List<ClientHarness> harnesses = new ArrayList<>();

    @AfterEach
    void closeHarnesses() {
        harnesses.forEach(ClientHarness::close);
    }

    private PvzceClient newClient() throws Exception {
        ClientHarness harness = ClientHarness.create("pvzce-scroll-region");
        harnesses.add(harness);
        return harness.client();
    }

    /** A screen with nothing of its own: the widgets under test are the whole subject. */
    private static final class BareScreen extends Screen {
        BareScreen(PvzceClient client) {
            super(client);
        }

        @Override
        public void render() {
        }
    }

    /** More rows than fit, so the list has something to scroll. */
    private static AbstractSelectionList<String> overflowingList(int x, int y, int width, int height) {
        AbstractSelectionList<String> list =
                new AbstractSelectionList<>(x, y, width, height, 20, (client, entry, rowX, rowY) -> {
                });
        list.setEntries(List.of("a", "b", "c", "d", "e", "f"));
        return list;
    }

    private static AbstractSelectionList<String> fittingList(int x, int y, int width, int height) {
        AbstractSelectionList<String> list =
                new AbstractSelectionList<>(x, y, width, height, 20, (client, entry, rowX, rowY) -> {
                });
        list.setEntries(List.of("a"));
        return list;
    }

    private static double centreX(AbstractSelectionList<String> list) {
        return list.x() + list.width() / 2.0;
    }

    private static double centreY(AbstractSelectionList<String> list) {
        return list.y() + list.height() / 2.0;
    }

    @Test
    void aListAnswersForItsOwnRectangleOnly() throws Exception {
        PvzceClient client = newClient();
        BareScreen screen = new BareScreen(client);
        client.setScreenReplacing(screen);
        AbstractSelectionList<String> list = overflowingList(10, 10, 100, 60);
        screen.addWidget(list);

        ScrollRegion region = screen.scrollRegionAt(centreX(list), centreY(list));
        assertNotNull(region, "a list with rows to spare is a scroll region");
        assertEquals(ScrollRegion.Axis.VERTICAL, region.axis());
        assertEquals(20, region.step(), "one row of finger travel scrolls one row");

        assertNull(screen.scrollRegionAt(500, 400), "a press on the screen itself scrolls nothing");
    }

    @Test
    void aListThatFitsStaysAnOrdinaryClickTarget() throws Exception {
        PvzceClient client = newClient();
        BareScreen screen = new BareScreen(client);
        client.setScreenReplacing(screen);
        AbstractSelectionList<String> list = fittingList(10, 10, 100, 60);
        screen.addWidget(list);

        assertNull(screen.scrollRegionAt(centreX(list), centreY(list)),
                "a list that fits must not turn its presses into scroll candidates");
    }

    @Test
    void aListInsideADialogIsReachableThroughTheModal() throws Exception {
        PvzceClient client = newClient();
        BareScreen screen = new BareScreen(client);
        client.setScreenReplacing(screen);

        Dialog dialog = new Dialog(100, 100, 320, 220, "test");
        AbstractSelectionList<String> list = overflowingList(120, 140, 200, 80);
        dialog.addChild(list);
        screen.showDialog(dialog);

        assertNotNull(screen.scrollRegionAt(centreX(list), centreY(list)),
                "the lists that live inside dialogs (player picker, card pool editor, every editor "
                        + "page) have to be swipe-scrollable too");
    }

    @Test
    void aSliderUnderThePointBeatsAScrollRegion() throws Exception {
        PvzceClient client = newClient();
        BareScreen screen = new BareScreen(client);
        client.setScreenReplacing(screen);
        AbstractSelectionList<String> list = overflowingList(10, 10, 200, 100);
        screen.addWidget(list);
        // Drawn over the list, the way an editor's form field can be.
        Slider slider = new Slider(120, 40, 80, 20, 0F, 1F, 0.5F, value -> {
        });
        screen.addWidget(slider);

        assertNull(screen.scrollRegionAt(slider.x() + slider.width() / 2.0,
                        slider.y() + slider.height() / 2.0),
                "a press that a slider needs for its drag must never become a scroll candidate");

        assertNotNull(screen.scrollRegionAt(20, 20),
                "and the list keeps the rest of its own rectangle");
    }
}
