package com.pvzce.client.gui.screens;

import com.pvzce.client.PvzceClient;
import com.pvzce.client.gui.components.Button;
import com.pvzce.client.gui.components.Dialog;

/**
 * In-game pause dialog. Unlike the old {@code PauseScreen}, it never replaces
 * the gameplay screen: it is an ordinary {@link Dialog} shown on top of
 * {@link InGameScreen}, so the board remains visible behind the backdrop.
 *
 * <p>It follows the two conventions the other dialogs in this game settled on. The stone frame is
 * drawn thin ({@link #FRAME_SCALE}) because its native border is most of a dialog this size, and
 * the title is drawn inside the frame rather than on the hanging wooden plate - that plate is
 * about 90 units tall at this width and would leave no room for the buttons on a screen that is
 * only 240 GUI units high. The buttons keep the stone style: they sit on a stone frame, and the
 * wooden ones looked like a different widget had been dropped into it.
 *
 * <p>The rows are laid out bottom-up from the frame's inside edge, not from {@code y}: the border
 * is part of this widget, and content placed from its outside edge is content drawn on the frame.
 */
public final class PauseDialog extends Dialog {
    public static final int WIDTH = 400;
    /**
     * Short on purpose: the point of a pause dialog is that the player can still see the board it
     * is pausing, and four short rows do not need a panel that covers it. {@link #create} clamps it
     * to the window anyway, and the layout below derives every row from the height it is given.
     */
    public static final int HEIGHT = 300;
    /**
     * How much of the stone frame's native border to draw.
     *
     * <p>Thinner than {@link PlayerPickerDialog}'s 0.55 on purpose: at 240 GUI units of screen height
     * this dialog is clamped to 216, and the border is taken off both ends - 0.55 leaves four rows
     * of twenty units, which is a row of stamps rather than of buttons. 0.45 keeps the carving and
     * gives the rows a button's height.
     */
    private static final float FRAME_SCALE = 0.45F;
    /** Between the rows, and between a row and the title above it. */
    private static final int ROW_GAP = 3;

    private PauseDialog(PvzceClient client, int x, int y, int width, int height) {
        super(x, y, width, height, "游戏暂停");
        titleScale(Math.min(1.3F, height / 200F));
        frameScale(FRAME_SCALE);
        // A plain title inside the frame: the hanging plate is taller than two rows of buttons here.
        inlineTitle();

        int pad = Math.round(frameInset());
        int titleRoom = Math.round(client.fonts().button().lineHeight(titleScale())) + ROW_GAP;
        int bottom = y + pad + 2;
        int top = y + height - pad - titleRoom;
        String[] labels = {"继续游戏", "设置", "重新开始", "保存并退出"};
        Runnable[] actions = {
                this::close,
                // The settings *menu* (音量设置 / 视频设置), not the volume page directly: this row
                // says 设置, and a player who wanted the volume page can walk one step further.
                // Opening a full screen over a paused level is safe - the board does not tick - and
                // closing it comes back to this dialog, which is still visible because the level is
                // still paused.
                () -> client.openScreen(new SettingsScreen(client)),
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

        int rows = labels.length;
        int rowHeight = Math.max(18, (top - bottom - ROW_GAP * (rows - 1)) / rows);
        int buttonWidth = Math.min(240, width - pad * 2 - 24);
        int buttonX = x + (width - buttonWidth) / 2;
        for (int i = 0; i < rows; i++) {
            addButton(new Button(buttonX, top - rowHeight - i * (rowHeight + ROW_GAP),
                    buttonWidth, rowHeight, labels[i], actions[i]));
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
