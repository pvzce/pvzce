package com.pvzce.client.gui.screens;

import com.pvzce.client.PvzceClient;
import com.pvzce.client.gui.components.Button;
import com.pvzce.client.gui.components.Dialog;
import com.pvzce.common.network.packet.LevelSavePromptS2C;

import java.util.Locale;

/** Modal prompt for a saved run, including one the server could not load. */
public final class LevelSaveDialog extends Dialog {
    private final LevelSavePromptS2C prompt;

    private LevelSaveDialog(PvzceClient client, int x, int y, int width, int height, LevelSavePromptS2C prompt,
                            Runnable onContinue, Runnable onRestart) {
        super(x, y, width, height, prompt.loadFailed() ? "存档无法加载" : "发现存档");
        this.prompt = prompt;
        titleScale(Math.min(1.35F, height / 260F));
        if (prompt.loadFailed()) {
            // This prompt is over a menu, whose tabs may occupy the hanging title's space.
            frameScale(0.28F);
            inlineTitle();
        }
        closeOnEscape(false);

        int buttonWidth = Math.min(260, Math.max(150, width - 80));
        int buttonHeight = Math.min(56, Math.max(30, (height - 84) / 3));
        int gap = Math.max(6, Math.min(12, buttonHeight / 4));
        int buttonX = x + (width - buttonWidth) / 2;
        int startY = y + 18;

        addButton(new Button(buttonX, startY, buttonWidth, buttonHeight,
                prompt.loadFailed() ? "返回关卡列表" : "继续游戏", () -> {
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
        int linesY = y + height - (prompt.loadFailed() ? 64 : 34);
        float scale = 0.9F;
        int textX = x + (prompt.loadFailed() ? 36 : 24);
        String title = "关卡：" + prompt.levelName();
        client.fonts().button().draw(title, textX, linesY, 1F, 1F, 0.95F, 0.75F, 1F);

        int seconds = Math.max(0, prompt.tickCount()) / com.pvzce.common.PvzceConstants.TICKS_PER_SECOND;
        String progress = prompt.loadFailed() ? "存档可能与当前版本不兼容，或数据已损坏。"
                : String.format(Locale.ROOT, "已有存档：进行中 · 第 %d tick（约 %d 分 %d 秒）",
                        prompt.tickCount(), seconds / 60, seconds % 60);
        client.fonts().body().draw(progress, textX, linesY - 24, scale, 0.9F, 0.9F, 0.9F, 1F);

        String plants = prompt.loadFailed() ? "重新开始会清除本局进度；返回则保留存档。"
                : "植物：" + prompt.plantCount() + " 棵    阳光：" + prompt.sun();
        client.fonts().body().draw(plants, textX, linesY - 46, scale, 0.9F, 0.9F, 0.9F, 1F);
    }
}
