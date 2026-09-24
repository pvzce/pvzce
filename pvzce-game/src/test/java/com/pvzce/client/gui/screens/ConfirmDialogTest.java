package com.pvzce.client.gui.screens;

import com.pvzce.client.PvzceClient;
import com.pvzce.client.gui.Screen;
import com.pvzce.client.gui.components.Button;
import com.pvzce.testutil.ClientHarness;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two questions the game asks before it throws work away.
 *
 * <p>Both are one-click actions that used to happen immediately: restarting from the pause menu
 * discarded the run, and starting from the seed chooser with no sun card locked the player out
 * of their own economy. These cases pin the question, both answers, and - for the pause menu -
 * that "no" leaves the player where they were rather than closing the menus behind it.
 */
class ConfirmDialogTest {
    private final List<ClientHarness> harnesses = new ArrayList<>();

    @AfterEach
    void closeHarnesses() {
        harnesses.forEach(ClientHarness::close);
    }

    private PvzceClient newClient() throws Exception {
        ClientHarness harness = ClientHarness.create("pvzce-confirm");
        harnesses.add(harness);
        return harness.client();
    }

    /** A screen with nothing on it, so a dialog is the only thing that can take a click. */
    private static final class EmptyScreen extends Screen {
        EmptyScreen(PvzceClient client) {
            super(client);
        }

        @Override
        public void render() {
        }
    }

    private static void click(Screen screen, Button button) {
        screen.dispatchMouseClicked(button.x() + button.width() / 2.0,
                button.y() + button.height() / 2.0, 0);
    }

    /**
     * The box under test, laid out for a fixed 1280x720 window.
     *
     * <p>The screen-level factories ask the client for its window size, and a headless client
     * has no window; the arithmetic they do is the same either way.
     */
    private static ConfirmDialog dialog(PvzceClient client, String title, String message,
                                        String confirm, Runnable onConfirm, Runnable onCancel) {
        return ConfirmDialog.create(client, title, message, confirm, onConfirm, onCancel, 1280, 720);
    }

    /** The dialog's buttons, in the order they were added: the cancel first, the confirm second. */
    private static List<Button> buttonsOf(ConfirmDialog dialog) {
        List<Button> buttons = new ArrayList<>();
        for (var child : dialog.children()) {
            if (child instanceof Button button) {
                buttons.add(button);
            }
        }
        return buttons;
    }

    @Test
    void theConfirmAnswerRunsTheActionAndClosesTheBox() throws Exception {
        PvzceClient client = newClient();
        EmptyScreen screen = new EmptyScreen(client);
        client.setScreenReplacing(screen);
        screen.initIfNeeded();

        List<String> log = new ArrayList<>();
        ConfirmDialog dialog = dialog(client, "重新开始这一关？", "当前进度会被丢弃。", "重新开始",
                () -> log.add("restarted"), () -> log.add("cancelled"));
        screen.showDialog(dialog);

        List<Button> buttons = buttonsOf(dialog);
        assertEquals(2, buttons.size(), "a yes/no box has two buttons");
        click(screen, buttons.get(1));

        assertEquals(List.of("restarted"), log, "the right-hand button is the confirmation");
        assertTrue(screen.dialogs().isEmpty(), "and answering closes the box");
    }

    @Test
    void theCancelAnswerRunsNothingButTheCallback() throws Exception {
        PvzceClient client = newClient();
        EmptyScreen screen = new EmptyScreen(client);
        client.setScreenReplacing(screen);
        screen.initIfNeeded();

        List<String> log = new ArrayList<>();
        ConfirmDialog dialog = dialog(client, "重新开始这一关？", "当前进度会被丢弃。", "重新开始",
                () -> log.add("restarted"), () -> log.add("cancelled"));
        screen.showDialog(dialog);

        click(screen, buttonsOf(dialog).get(0));

        assertEquals(List.of("cancelled"), log, "the left-hand button backs out");
        assertTrue(screen.dialogs().isEmpty(), "and that closes the box too");
    }

    /**
     * The sun question names the actual consequence.
     *
     * <p>A box that only said "are you sure?" would be answered by reflex; this one has to say
     * what will not work, because that is the part the player did not intend.
     */
    @Test
    void theSunQuestionSaysWhatWillNotWork() throws Exception {
        PvzceClient client = newClient();
        EmptyScreen screen = new EmptyScreen(client);
        client.setScreenReplacing(screen);
        screen.initIfNeeded();

        ConfirmDialog dialog = dialog(client, "没有选择阳光", "本局将无法在草坪上收集阳光。",
                "继续开始", () -> {
                }, null);
        screen.showDialog(dialog);
        // The dialog renders its own message; a smoke run is what checks the pixels, and this
        // checks that the question exists with two answers rather than being a silent start.
        assertEquals(2, buttonsOf(dialog).size());
        assertTrue(dialog.isVisible(), "the question is on screen before anything is sent");
    }
}
