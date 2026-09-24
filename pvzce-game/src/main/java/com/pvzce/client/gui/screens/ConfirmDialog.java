package com.pvzce.client.gui.screens;

import com.pvzce.client.PvzceClient;
import com.pvzce.client.gui.GuiLang;
import com.pvzce.client.gui.components.Button;
import com.pvzce.client.gui.components.Dialog;

/**
 * A yes/no box in front of something the player cannot take back.
 *
 * <p>Two decisions in this game throw away work - restarting a run in progress, and starting a
 * level with no way to collect sun - and both used to happen on the first click. This is the
 * one box they share, so the question, the two answers and the order of the buttons read the
 * same wherever it appears.
 *
 * <p>Both answers are callbacks the caller supplies; the box only closes itself and then calls
 * one. That is what lets the pause menu put this <em>on top of</em> itself and, on "no", leave
 * the player exactly where they were.
 */
public final class ConfirmDialog extends Dialog {
    /**
     * @param title     what is being asked, in one short line
     * @param message   the consequence, in the player's words
     * @param confirm   the button that goes through with it
     * @param onConfirm run after this box closes
     * @param onCancel  run after this box closes when the player backs out; may be null
     */
    private ConfirmDialog(PvzceClient client, int x, int y, int width, int height,
                          String title, String message, String confirm,
                          Runnable onConfirm, Runnable onCancel) {
        super(x, y, width, height, title);
        titleScale(Math.min(1.30F, height / 240F));
        // The title goes inside the frame rather than on the hanging plate: this box is small
        // and the plate is nearly a fifth of its height, which left the message nowhere to go.
        inlineTitle();
        // ESC is the same answer as the cancel button, so it is not a third way out; the
        // dialog's own escape handling needs the callback for that.
        closeOnEscape(false);

        int buttonWidth = Math.min(200, Math.max(110, (width - 90) / 2));
        int buttonHeight = Math.min(48, Math.max(28, height / 5));
        int gap = Math.max(10, buttonWidth / 6);
        int buttonsX = x + (width - (buttonWidth * 2 + gap)) / 2;
        int buttonsY = y + Math.max(14, height / 6);

        addButton(new Button(buttonsX, buttonsY, buttonWidth, buttonHeight, cancelLabel(),
                () -> {
                    close();
                    if (onCancel != null) {
                        onCancel.run();
                    }
                }).style(Button.Style.SEED_CHOOSER));
        addButton(new Button(buttonsX + buttonWidth + gap, buttonsY, buttonWidth, buttonHeight,
                confirm, () -> {
                    close();
                    onConfirm.run();
                }).style(Button.Style.SEED_CHOOSER));

        this.message = message;
    }

    private final String message;

    /** The player's "no", which is the left button and the same word everywhere. */
    private static String cancelLabel() {
        return GuiLang.raw("pvzce.dialog.cancel", "取消");
    }

    /**
     * The box itself, sized to the window.
     *
     * <p>Every caller goes through here rather than the constructor so the geometry is decided
     * in one place: two dialogs of the same kind must not be different sizes depending on which
     * menu opened them.
     */
    public static ConfirmDialog create(PvzceClient client, String title, String message,
                                       String confirm, Runnable onConfirm, Runnable onCancel) {
        return create(client, title, message, confirm, onConfirm, onCancel,
                client.guiWidth(), client.guiHeight());
    }

    /**
     * The same box, laid out for an explicit window size.
     *
     * <p>Separate from the windowed overload because the window is a GL object: a headless test
     * has a client with no window at all, and "how big is the box" is arithmetic that has nothing
     * to do with whether a window exists.
     */
    static ConfirmDialog create(PvzceClient client, String title, String message, String confirm,
                                Runnable onConfirm, Runnable onCancel, int guiWidth, int guiHeight) {
        int width = Math.min(420, guiWidth - 24);
        int height = Math.min(210, guiHeight - 24);
        return new ConfirmDialog(client, (guiWidth - width) / 2, (guiHeight - height) / 2,
                width, height, title, message, confirm, onConfirm, onCancel);
    }

    /** Asking before a run in progress is thrown away. */
    public static ConfirmDialog restart(PvzceClient client, Runnable onRestart, Runnable onCancel) {
        return create(client,
                GuiLang.raw("pvzce.dialog.restart.title", "重新开始这一关？"),
                GuiLang.raw("pvzce.dialog.restart.message", "当前进度会被丢弃，本关从头开始。"),
                GuiLang.raw("pvzce.restart", "重新开始"), onRestart, onCancel);
    }

    /**
     * Asking before a level starts with no sun card in the bar.
     *
     * <p>Not a refusal: a level whose sun comes from somewhere else - a conveyor belt, a grave,
     * a level that pays for kills - is playable without the card, and the player may know that.
     * It is the case where they probably do not that this is for.
     */
    public static ConfirmDialog startWithoutSun(PvzceClient client, Runnable onStart, Runnable onCancel) {
        return create(client,
                GuiLang.raw("pvzce.dialog.no_sun.title", "没有选择阳光"),
                GuiLang.raw("pvzce.dialog.no_sun.message",
                        "你没有把阳光卡放进卡槽，本局将无法在草坪上收集阳光。确定要继续吗？"),
                GuiLang.raw("pvzce.dialog.no_sun.confirm", "继续开始"), onStart, onCancel);
    }

    @Override
    public void render(PvzceClient client) {
        super.render(client);
        if (!visible) {
            return;
        }
        // Wrapped by hand: the dialog is fixed-width, and a message long enough to run past it
        // is a message the player has to be able to read. The first line starts below the inline
        // title, which the superclass draws just under the frame's top border.
        float scale = 0.9F;
        float lineHeight = client.fonts().body().lineHeight(scale) + 2;
        int maxWidth = width - 48;
        float lineY = y + height - 46;
        for (String line : wrap(client, message, maxWidth, scale)) {
            client.fonts().body().draw(line, x + 24, lineY, scale, 0.95F, 0.95F, 0.9F, 1F);
            lineY -= lineHeight;
        }
    }

    /** Breaks a message at spaces, and at any character for a script that has none. */
    private static java.util.List<String> wrap(PvzceClient client, String text, int maxWidth, float scale) {
        java.util.List<String> lines = new java.util.ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (client.fonts().body().width(candidate, scale) <= maxWidth) {
                line.setLength(0);
                line.append(candidate);
                continue;
            }
            if (!line.isEmpty()) {
                lines.add(line.toString());
                line.setLength(0);
            }
            // A single word can still be wider than the box (a long id, or a language without
            // spaces): cut it by character rather than letting it run over the frame.
            StringBuilder chunk = new StringBuilder();
            for (int i = 0; i < word.length(); i++) {
                if (client.fonts().body().width(chunk.toString() + word.charAt(i), scale) > maxWidth
                        && !chunk.isEmpty()) {
                    lines.add(chunk.toString());
                    chunk.setLength(0);
                }
                chunk.append(word.charAt(i));
            }
            line.append(chunk);
        }
        if (!line.isEmpty()) {
            lines.add(line.toString());
        }
        return lines;
    }
}
