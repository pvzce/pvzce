package com.pvzce.client.gui.screens;

import com.pvzce.client.PvzceClient;
import com.pvzce.client.gui.GuiLang;
import com.pvzce.client.gui.Screen;
import com.pvzce.client.gui.components.Button;
import com.pvzce.client.gui.layout.GuiLayout;

/** Settings hub: volume page, video page, difficulty, done. */
public final class SettingsScreen extends Screen {
    private int titleY;
    private float titleScale;

    public SettingsScreen(PvzceClient client) {
        super(client);
    }

    @Override
    protected void init() {
        // A menu theme only when no level is loaded. The controller keeps four independent tracks,
        // so a menu request made from inside a level does not replace the level's music - it plays
        // *with* it, which is what "open the settings from the pause menu" used to sound like.
        // Opened over a level this page now leaves that level's music alone (the level is paused,
        // not over, and the pause dialog does not silence it either).
        //
        // `initialized` rather than a null check: the client's level mirror is one long-lived
        // object (`new ClientLevel()` at construction), so it is never null - it is reset empty
        // when a level is left.
        if (client.music() != null && !client.level().initialized()) {
            client.music().ensureMenu("pvzce:music/crazy_dave");
        }
        int guiH = client.guiHeight();
        int buttonWidth = Math.min(320, client.guiWidth() - 24);
        int titleReserve = Math.max(40, Math.min(72, guiH / 4));
        int buttonHeight = GuiLayout.fitHeight(guiH, 68, LABELS.length, titleReserve, 8);
        int gap = GuiLayout.gapFor(buttonHeight);
        int topY = guiH - titleReserve;
        int buttonTop = topY - gap;
        titleScale = Math.min(2.4F, Math.max(1.2F, guiH / 120F));
        titleY = buttonTop + Math.round(client.fonts().button().lineHeight(titleScale) * 0.35F);
        int x = centerX(buttonWidth);
        for (int i = 0; i < LABELS.length; i++) {
            int y = buttonTop - buttonHeight - i * (buttonHeight + gap);
            addWidget(new Button(x, y, buttonWidth, buttonHeight, LABELS[i], ACTIONS[i].run(this)));
        }
    }

    /**
     * The hub's rows, in the order they are stacked.
     *
     * <p>One table rather than two arrays inside {@code init}: the height calculation above needs
     * the count, and a row added to one list but not the other used to be a button drawn off the
     * bottom of the window.
     */
    private static final String[] LABELS = {
            "音量设置", "视频设置", "难度", "快捷键",
            GuiLang.raw("pvzce.settings.about", "关于"), "完成"};

    /** One row's action, as a function of the screen so the table can be static. */
    private interface Row {
        Runnable run(SettingsScreen screen);
    }

    private static final Row[] ACTIONS = {
            screen -> () -> screen.client().openScreen(screen.client().buildSettingsConfig()),
            screen -> () -> screen.client().openScreen(new VideoSettingsScreen(screen.client())),
            screen -> () -> screen.client().openScreen(new DifficultyScreen(screen.client())),
            screen -> () -> screen.client().openScreen(new KeybindScreen(screen.client())),
            // 合规项，不是装饰：GPL-3.0 §5(d) 要求图形界面显示版权与无担保声明（见 AboutScreen）。
            screen -> () -> screen.client().openScreen(new AboutScreen(screen.client())),
            screen -> screen::requestClose
    };

    @Override
    public boolean blurredBackdrop() {
        return true;
    }

    @Override
    public void render() {
        client.beginGuiView();
        // The frame this page was opened over, blurred: a settings page is a page *over* whatever
        // the player was doing, and the menu art behind it has nothing to do with that.
        if (!renderBlurredBackdrop(0.10F, 0.11F, 0.14F, 0.62F)) {
            renderBackground(0.08F, 0.1F, 0.12F);
        }
        String title = "设置";
        client.fonts().button().draw(title, (client.guiWidth() - client.fonts().button().width(title, titleScale)) / 2F,
                titleY, titleScale, 1, 1, 1, 1);
        for (var widget : widgets) {
            widget.render(client);
        }
    }
}
