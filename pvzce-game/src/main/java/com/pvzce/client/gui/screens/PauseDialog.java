package com.pvzce.client.gui.screens;

import com.pvzce.client.PvzceClient;
import com.pvzce.client.gui.components.Button;
import com.pvzce.client.gui.components.Dialog;

/**
 * In-game pause dialog. Unlike the old {@code PauseScreen}, it never replaces
 * the gameplay screen: it is an ordinary {@link Dialog} shown on top of
 * {@link InGameScreen}, so the board remains visible behind the backdrop.
 */
public final class PauseDialog extends Dialog {
    public static final int WIDTH = 440;
    public static final int HEIGHT = 400;

    private PauseDialog(PvzceClient client, int x, int y, int width, int height) {
        super(x, y, width, height, "游戏暂停");
        titleScale(Math.min(1.6F, height / 220F));

        int buttonWidth = Math.min(260, Math.max(160, width - 100));
        int buttonHeight = Math.min(64, Math.max(30, (height - 100) / 3));
        int gap = Math.max(8, Math.min(16, buttonHeight / 4));
        int buttonX = x + (width - buttonWidth) / 2;
        int startY = y + height - buttonHeight - Math.max(24, (height - buttonHeight * 3 - gap * 2) / 2);
        String[] labels = {"继续游戏", "重新开始", "保存并退出"};
        Runnable[] actions = {
                this::close,
                // Closes the running level first, then enters the normal seed-chooser
                // flow. Layering the chooser on top of the live level used to leave it
                // running underneath, so ESC fell back into this dialog and the level
                // never really restarted.
                () -> client.restartCurrentLevel(),
                () -> {
                    close();
                    client.leaveLevel();
                }
        };
        for (int i = 0; i < labels.length; i++) {
            addButton(new Button(buttonX, startY - i * (buttonHeight + gap),
                    buttonWidth, buttonHeight, labels[i], actions[i]).scale(Math.min(1.05F, buttonHeight / 42F)));
        }
    }

    public static PauseDialog create(PvzceClient client) {
        int width = Math.min(WIDTH, client.guiWidth() - 24);
        int height = Math.min(HEIGHT, client.guiHeight() - 24);
        return new PauseDialog(client,
                (client.guiWidth() - width) / 2,
                (client.guiHeight() - height) / 2,
                width,
                height);
    }
}
