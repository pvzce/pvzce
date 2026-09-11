package com.pvzce.client.gui.screens;

import com.pvzce.client.PvzceClient;
import com.pvzce.client.gui.Screen;
import com.pvzce.client.gui.components.Button;
import com.pvzce.client.gui.layout.GuiLayout;

/** Settings hub: volume page, video page, done. */
public final class SettingsScreen extends Screen {
    private int titleY;
    private float titleScale;

    public SettingsScreen(PvzceClient client) {
        super(client);
    }

    @Override
    protected void init() {
        client.music().ensureMenu("pvzce:music/crazy_dave");
        int guiH = client.guiHeight();
        int buttonWidth = Math.min(320, client.guiWidth() - 24);
        int titleReserve = Math.max(40, Math.min(72, guiH / 4));
        int buttonHeight = GuiLayout.fitHeight(guiH, 68, 3, titleReserve, 8);
        int gap = GuiLayout.gapFor(buttonHeight);
        int topY = guiH - titleReserve;
        int buttonTop = topY - gap;
        titleScale = Math.min(2.4F, Math.max(1.2F, guiH / 120F));
        titleY = buttonTop + Math.round(client.font().lineHeight(titleScale) * 0.35F);
        int x = centerX(buttonWidth);
        String[] labels = {"音量设置", "视频设置", "完成"};
        Runnable[] actions = {
                () -> client.openScreen(client.buildSettingsConfig()),
                () -> client.openScreen(new VideoSettingsScreen(client)),
                () -> client.closeScreen()
        };
        for (int i = 0; i < labels.length; i++) {
            int y = buttonTop - buttonHeight - i * (buttonHeight + gap);
            addWidget(new Button(x, y, buttonWidth, buttonHeight, labels[i], actions[i]).scale(1.1F));
        }
    }

    @Override
    public void render() {
        client.beginGuiView();
        renderBackground(0.08F, 0.1F, 0.12F);
        String title = "设置";
        client.font().draw(title, (client.guiWidth() - client.font().width(title, titleScale)) / 2F,
                titleY, titleScale, 1, 1, 1, 1);
        for (var widget : widgets) {
            widget.render(client);
        }
    }
}
