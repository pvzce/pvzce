package com.pvzce.client.gui.screens;

import com.pvzce.client.PvzceClient;
import com.pvzce.client.gui.Screen;
import com.pvzce.client.gui.components.Button;
import com.pvzce.client.gui.components.Dialog;
import com.pvzce.testutil.ClientHarness;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A modal dialog must receive clicks before the screen behind it.
 *
 * <p>That used to be a convention: every {@code Screen} subclass had to call
 * {@code super.mouseClicked} <em>before</em> its own hit testing.
 * {@code LevelSelectScreen} checked its card grid first, and the grid covers most of the
 * window, so the grid swallowed every click and the "new level" dialog's buttons were
 * dead - the dialog drew fine, nothing responded. Dispatch is now {@code final} on
 * {@code Screen} and screen-specific handling moved to {@code onMouseClicked}, which
 * only runs when no dialog is open; these tests pin that behaviour.
 */
class ModalDialogInputTest {
    private final List<ClientHarness> harnesses = new ArrayList<>();

    @AfterEach
    void closeHarnesses() {
        harnesses.forEach(ClientHarness::close);
    }

    private PvzceClient newClient() throws Exception {
        ClientHarness harness = ClientHarness.create("pvzce-modal-input");
        harnesses.add(harness);
        return harness.client();
    }

    /** A screen with a full-window hit region of its own, like the level list's card grid. */
    private static final class GreedyScreen extends Screen {
        private int hits;

        GreedyScreen(PvzceClient client) {
            super(client);
        }

        @Override
        protected void onMouseClicked(double guiX, double guiY, int button) {
            // Claims every click, the way the card grid claimed most of the window.
            hits++;
        }

        @Override
        public void render() {
        }
    }

    @Test
    void aClickInsideTheDialogReachesTheDialogAndNotTheScreen() throws Exception {
        PvzceClient client = newClient();
        GreedyScreen screen = new GreedyScreen(client);
        client.setScreenReplacing(screen);
        screen.initIfNeeded();

        List<String> pressed = new ArrayList<>();
        Dialog dialog = new Dialog(100, 100, 300, 200, "test");
        Button button = new Button(120, 120, 120, 30, "确认", () -> pressed.add("confirm"));
        dialog.addButton(button);
        screen.showDialog(dialog);

        screen.dispatchMouseClicked(button.x() + button.width() / 2.0,
                button.y() + button.height() / 2.0, 0);

        assertTrue(pressed.contains("confirm"),
                "the dialog's button must receive the click while the dialog is open");
        assertEquals0(screen.hits, "the screen behind the dialog must not also handle the click");
    }

    @Test
    void theScreenHandlesClicksOnceTheDialogIsClosed() throws Exception {
        PvzceClient client = newClient();
        GreedyScreen screen = new GreedyScreen(client);
        client.setScreenReplacing(screen);
        screen.initIfNeeded();

        Dialog dialog = new Dialog(100, 100, 300, 200, "test");
        dialog.addButton(new Button(120, 120, 120, 30, "确认", () -> {
        }));
        screen.showDialog(dialog);
        dialog.close();

        screen.dispatchMouseClicked(500, 400, 0);

        assertTrue(screen.hits > 0, "with no dialog open the screen handles its own clicks again");
    }

    /** A click outside the dialog must not fall through to the screen behind it. */
    @Test
    void aClickOutsideTheDialogIsSwallowedWhileItIsModal() throws Exception {
        PvzceClient client = newClient();
        GreedyScreen screen = new GreedyScreen(client);
        client.setScreenReplacing(screen);
        screen.initIfNeeded();

        Dialog dialog = new Dialog(400, 300, 200, 150, "test");
        screen.showDialog(dialog);

        // Well outside the dialog rectangle.
        screen.dispatchMouseClicked(2, 2, 0);

        assertEquals0(screen.hits, "a modal dialog must absorb clicks outside its frame");
    }

    private static void assertEquals0(int actual, String message) {
        org.junit.jupiter.api.Assertions.assertEquals(0, actual, message);
    }
}
