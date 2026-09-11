package com.pvzce.client.gui.screens;

import com.pvzce.client.PvzceClient;
import com.pvzce.client.gui.components.Button;
import com.pvzce.client.gui.components.Dialog;
import com.pvzce.common.network.packet.LevelSavePromptS2C;

import java.util.Locale;

/** Modal prompt shown when a level already has a resumable save. */
public final class LevelSaveDialog extends Dialog {
    private final LevelSavePromptS2C prompt;

    private LevelSaveDialog(PvzceClient client, int x, int y, int width, int height, LevelSavePromptS2C prompt,
                            Runnable onContinue, Runnable onRestart) {
        super(x, y, width, height, "发现存档");
        this.prompt = prompt;
        titleScale(Math.min(1.35F, height / 260F));
        closeOnEscape(false);

        int buttonWidth = Math.min(260, Math.max(150, width - 80));
        int buttonHeight = Math.min(56, Math.max(30, (height - 84) / 3));
        int gap = Math.max(6, Math.min(12, buttonHeight / 4));
        int buttonX = x + (width - buttonWidth) / 2;
        int startY = y + 18;

        addButton(new Button(buttonX, startY, buttonWidth, buttonHeight, "继续游戏", () -> {
            close();
            onContinue.run();
        }).style(Button.Style.SEED_CHOOSER));
        addButton(new Button(buttonX, startY + buttonHeight + gap, buttonWidth, buttonHeight, "重新开始", () -> {
            close();
            onRestart.run();
        }).style(Button.Style.SEED_CHOOSER));
    }

    public static LevelSaveDialog create(PvzceClient client, LevelSavePromptS2C prompt,
                                         Runnable onContinue, Runnable onRestart) {
        int width = Math.min(520, client.guiWidth() - 24);
        int height = Math.min(300, client.guiHeight() - 24);
        return new LevelSaveDialog(client, (client.guiWidth() - width) / 2, (client.guiHeight() - height) / 2,
                width, height, prompt, onContinue, onRestart);
    }

    @Override
    public void render(PvzceClient client) {
        super.render(client);
        if (!visible) {
            return;
        }
        int linesY = y + height - 34;
        float scale = 0.9F;
        String title = "关卡：" + prompt.levelName();
        client.font().draw(title, x + 24, linesY, 1F, 1F, 0.95F, 0.75F, 1F);

        int seconds = Math.max(0, prompt.tickCount()) / 60;
        String progress = String.format(Locale.ROOT, "已有存档：进行中 · 第 %d tick（约 %d 分 %d 秒）",
                prompt.tickCount(), seconds / 60, seconds % 60);
        client.font().draw(progress, x + 24, linesY - 24, scale, 0.9F, 0.9F, 0.9F, 1F);

        String plants = "植物：" + prompt.plantCount() + " 棵    阳光：" + prompt.sun();
        client.font().draw(plants, x + 24, linesY - 46, scale, 0.9F, 0.9F, 0.9F, 1F);
    }
}
