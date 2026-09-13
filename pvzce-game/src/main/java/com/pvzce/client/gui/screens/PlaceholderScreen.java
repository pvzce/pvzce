package com.pvzce.client.gui.screens;

import com.pvzce.client.PvzceClient;
import com.pvzce.client.gui.Screen;
import com.pvzce.client.gui.components.Button;

/** Temporary screen for features that arrive in a later milestone. */
public final class PlaceholderScreen extends Screen {
    private final String title;
    private final String body;

    public PlaceholderScreen(PvzceClient client, String title, String body) {
        super(client);
        this.title = title;
        this.body = body;
    }

    @Override
    protected void init() {
        int buttonWidth = 220;
        int buttonHeight = 44;
        addWidget(new Button(centerX(buttonWidth), 90, buttonWidth, buttonHeight, "返回", this::requestClose));
    }

    @Override
    public void render() {
        client.beginGuiView();
        renderBackground(0.08F, 0.1F, 0.12F);
        float scale = 2.4F;
        client.font().draw(title, (client.guiWidth() - client.font().width(title, scale)) / 2F,
                client.guiHeight() * 0.68F, scale, 1F, 1F, 1F, 1F);
        client.font().draw(body, (client.guiWidth() - client.font().width(body, 0.9F)) / 2F,
                client.guiHeight() * 0.58F, 0.9F, 0.85F, 0.85F, 0.85F, 1F);
        for (var widget : widgets) {
            widget.render(client);
        }
    }
}
