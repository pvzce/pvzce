package com.pvzce.client.gui.screens;

import com.pvzce.api.util.Identifier;
import com.pvzce.client.PvzceClient;
import com.pvzce.client.gui.Screen;
import com.pvzce.client.gui.components.Button;
import com.pvzce.client.gui.layout.GuiLayout;
import com.pvzce.client.gui.mods.ModsScreen;
import net.fabricmc.loader.api.FabricLoader;

/** Main menu: start, mods, settings, quit. */
public final class TitleScreen extends Screen {
    /** Place your image here: assets/pvzce/textures/gui/screen/title/title_logo.png */
    private static final Identifier TITLE_LOGO =
            Identifier.withDefaultNamespace("textures/gui/screen/title/title_logo");
    /** Place your image here: assets/pvzce/textures/gui/screen/title/title_background.png */
    private static final Identifier TITLE_BACKGROUND =
            Identifier.withDefaultNamespace("textures/gui/screen/title/title_background");
    private static final float TITLE_LOGO_ASPECT = 2170F / 725F;

    private int titleY;
    private int subtitleY;
    private float titleScale;
    private float subtitleScale;
    private int buttonTop;

    public TitleScreen(PvzceClient client) {
        super(client);
    }

    @Override
    protected Identifier backgroundTexture() {
        return TITLE_BACKGROUND;
    }

    @Override
    protected void init() {
        client.music().ensureMenu("pvzce:music/crazy_dave");
        int guiW = client.guiWidth();
        int guiH = client.guiHeight();
        int buttonWidth = Math.min(280, guiW - 24);
        int titleReserve = Math.max(56, Math.min(140, guiH * 30 / 100));
        // 48px legacy height enlarged by 40%; shrinks automatically when 4x UI has less room.
        int buttonHeight = GuiLayout.fitHeight(guiH, 68, 4, titleReserve, 8);
        int gap = GuiLayout.gapFor(buttonHeight);
        int blockHeight = 4 * buttonHeight + 3 * gap;

        // Buttons are anchored to the bottom so the menu fills the screen and
        // no large blank strip remains under the last button.
        int blockTop = 8 + blockHeight;
        buttonTop = blockTop;
        String[] labels = {"开始游戏", "模组列表", "设置", "退出"};
        Runnable[] actions = {
                () -> client.openScreen(new WorldSelectScreen(client)),
                () -> client.openScreen(new ModsScreen(client)),
                () -> client.openScreen(new SettingsScreen(client)),
                () -> client.window().requestClose()
        };
        int x = centerX(buttonWidth);
        for (int i = 0; i < labels.length; i++) {
            int y = blockTop - buttonHeight - i * (buttonHeight + gap);
            addWidget(new Button(x, y, buttonWidth, buttonHeight, labels[i], actions[i]).scale(1.1F));
        }

        // Title and subtitle sit in the space left above the button block,
        // never above the window edge.
        subtitleScale = Math.min(1.2F, Math.max(0.8F, guiH / 240F));
        subtitleY = blockTop + gap;
        titleY = subtitleY + client.font().lineHeight(subtitleScale) + gap;
        float availableAbove = Math.max(20F, guiH - titleY - 4F);
        titleScale = Math.min(4.2F, Math.max(0.9F, availableAbove / 30F));
    }

    @Override
    public void render() {
        client.beginGuiView();
        int guiW = client.guiWidth();
        int guiH = client.guiHeight();
        renderBackground(0.16F, 0.3F, 0.13F);

        if (client.hasTexture(TITLE_LOGO)) {
            // Keep the 2170x725 aspect ratio inside the title area above the buttons.
            float availableHeight = Math.max(24F, guiH - buttonTop - 12F);
            float maxWidth = guiW * 0.72F;
            float logoHeight = Math.min(availableHeight * 0.9F, maxWidth / TITLE_LOGO_ASPECT);
            float logoWidth = logoHeight * TITLE_LOGO_ASPECT;
            float logoX = (guiW - logoWidth) / 2F;
            float logoY = buttonTop + (availableHeight - logoHeight) / 2F;
            client.drawTexture(TITLE_LOGO, logoX, logoY, logoWidth, logoHeight, 0.1F, 1F, 1F, 1F, 1F);
        } else {
            String title = "PVZ 社区版";
            client.font().draw(title, (guiW - client.font().width(title, titleScale)) / 2F,
                    titleY, titleScale, 1F, 0.92F, 0.35F, 1F);
            String subtitle = "植物大战僵尸 · 社区版";
            client.font().draw(subtitle, (guiW - client.font().width(subtitle, subtitleScale)) / 2F,
                    subtitleY, subtitleScale, 0.85F, 0.95F, 0.85F, 1F);
        }

        String loaderVersion = FabricLoader.getInstance().getModContainer("fabricloader")
                .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");
        String versionType = System.getProperty("pvzce.versionType", "release");
        String info = "PVZCE 1.0.0 · fabricloader " + loaderVersion + " · "
                + FabricLoader.getInstance().getAllMods().size() + " mods · " + versionType;
        client.font().draw(info, 8, 8, 0.7F, 0.7F, 0.75F, 0.7F, 1F);
        for (var widget : widgets) {
            widget.render(client);
        }
    }
}
