package com.pvzce.client.gui.screens;

import com.pvzce.client.PvzceClient;
import com.pvzce.client.gui.GuiLang;
import com.pvzce.client.gui.Screen;
import com.pvzce.client.gui.components.Button;
import com.pvzce.client.gui.components.EditBox;
import com.pvzce.client.gui.layout.GuiLayout;

import java.util.List;

/**
 * Where the player tells the game how to reach Jev.
 *
 * <p>Three rows and a hint: which service (three buttons that fill the row below them), the
 * endpoint and model, and the key. The key row is the reason this page is not part of the ordinary
 * config screen - that screen's entries are numbers and switches, and a credential needs a text
 * field, a paste, and a place to say "leave this empty".
 *
 * <p><b>Empty means the built-in opponent.</b> The game never refuses to start a versus level
 * because there is no key: it plays its own policy and says so on the HUD. So the honest default is
 * an empty key and a filled-in endpoint - the one row a player has to touch is the key.
 *
 * <p>Saved on 完成 rather than on every keystroke: the config file is written whole, and a page that
 * saved as you typed would rewrite it once per character.
 */
public final class JevSettingsScreen extends Screen {
    /**
     * The services that were verified while this was built.
     *
     * <p>A convenience, not a whitelist: every row stays editable, because the endpoint is not one
     * service and a player may be behind a proxy or on a provider this list has never heard of. The
     * URLs, the model names and the response envelopes are written down in
     * {@code docs/架构变更记录.md}.
     */
    private record Preset(String labelKey, String fallback, String url, String model) {
    }

    private static final List<Preset> PRESETS = List.of(
            new Preset("gui.pvzce.jev.preset.openrouter", "OpenRouter",
                    "https://openrouter.ai/api/alpha/decisions", "typesafe/jev-1.13"),
            new Preset("gui.pvzce.jev.preset.edgeone", "EdgeOne",
                    "https://ai-gateway.edgeone.link/v1/systemone", "@makers/jev"),
            new Preset("gui.pvzce.jev.preset.official", "JevAI 官方",
                    "https://www.jevai.org/api/v1/decisions", "jev-latest"));

    private EditBox urlBox;
    private EditBox modelBox;
    private EditBox keyBox;
    private final int[] labelY = new int[3];
    private int titleY;
    private float titleScale;

    public JevSettingsScreen(PvzceClient client) {
        super(client);
    }

    @Override
    protected void init() {
        int margin = 16;
        int guiH = client.guiHeight();
        int titleReserve = Math.max(34, Math.min(52, guiH / 6));
        int rowHeight = GuiLayout.fitHeight(guiH, 38, 3, titleReserve, 10);
        int gap = GuiLayout.gapFor(rowHeight);
        int fullWidth = client.guiWidth() - margin * 2;
        int labelWidth = 96;
        int fieldX = margin + labelWidth + 8;
        int fieldWidth = margin + fullWidth - fieldX;
        int topY = guiH - titleReserve;
        int y = topY - gap - rowHeight;
        titleScale = Math.min(1.7F, Math.max(1.0F, guiH / 150F));
        titleY = (y + rowHeight) + Math.round(client.fonts().button().lineHeight(titleScale) * 0.35F);

        // Row 1: one button per known service. Three fixed labels rather than a cycling button,
        // because a cycling button's own text has to be rebuilt on every click and the fields it
        // just filled must not be.
        int presetGap = 6;
        int presetWidth = (fieldWidth - presetGap * (PRESETS.size() - 1)) / PRESETS.size();
        for (int i = 0; i < PRESETS.size(); i++) {
            Preset preset = PRESETS.get(i);
            int x = fieldX + i * (presetWidth + presetGap);
            addWidget(new Button(x, y, presetWidth, rowHeight,
                    GuiLang.raw(preset.labelKey(), preset.fallback()),
                    () -> {
                        urlBox.setValue(preset.url());
                        modelBox.setValue(preset.model());
                    }));
        }
        labelY[0] = center(y, rowHeight);
        y -= rowHeight + gap;

        // The URL and the model share a row: they are one answer ("which endpoint"), and the
        // preset buttons above already fill both.
        int modelWidth = Math.min(240, fieldWidth / 3);
        urlBox = new EditBox(fieldX, y, fieldWidth - modelWidth - gap, rowHeight, 300, null);
        urlBox.setValue(client.config().jevUrl());
        addWidget(urlBox);
        modelBox = new EditBox(fieldX + fieldWidth - modelWidth, y, modelWidth, rowHeight, 120, null);
        modelBox.setValue(client.config().jevModel());
        addWidget(modelBox);
        labelY[1] = center(y, rowHeight);
        y -= rowHeight + gap;

        // The key is the row every player has to touch, so it sits beside the two buttons rather
        // than under them, and it is the widest field on the page.
        int buttonWidth = Math.min(140, fieldWidth / 4);
        int keyWidth = fieldWidth - buttonWidth * 2 - gap * 2;
        keyBox = new EditBox(fieldX, y, keyWidth, rowHeight, 300, null);
        keyBox.setValue(client.config().jevKey());
        addWidget(keyBox);
        labelY[2] = center(y, rowHeight);
        addWidget(new Button(fieldX + keyWidth + gap, y, buttonWidth, rowHeight,
                GuiLang.raw("gui.pvzce.jev.cancel", "取消"), this::requestClose));
        addWidget(new Button(fieldX + keyWidth + gap + buttonWidth + gap, y, buttonWidth, rowHeight,
                GuiLang.raw("gui.pvzce.jev.done", "完成"), this::save));
    }

    private static int center(int y, int rowHeight) {
        return y + rowHeight / 2 - 5;
    }

    /** Writes the three rows into the client config and hands them to the server. */
    private void save() {
        client.config().setJevUrl(urlBox.value());
        client.config().setJevModel(modelBox.value());
        client.config().setJevKey(keyBox.value());
        client.config().save();
        // The server's copy is a session copy: sending it here means a key pasted mid-run is in play
        // on the next decision rather than on the next level.
        client.syncJevSettings();
        requestClose();
    }

    @Override
    public void render() {
        client.beginGuiView();
        if (!renderBlurredBackdrop(0.10F, 0.11F, 0.14F, 0.62F)) {
            renderBackground(0.08F, 0.1F, 0.12F);
        }
        String title = GuiLang.raw("gui.pvzce.jev.title", "AI 对战（Jev）");
        client.fonts().button().draw(title,
                (client.guiWidth() - client.fonts().button().width(title, titleScale)) / 2F,
                titleY, titleScale, 1F, 1F, 1F, 1F);
        label(0, GuiLang.raw("gui.pvzce.jev.preset.label", "服务商"));
        label(1, GuiLang.raw("gui.pvzce.jev.url", "接口地址 / 模型"));
        label(2, GuiLang.raw("gui.pvzce.jev.key", "API Key"));
        for (var widget : widgets) {
            widget.render(client);
        }
        // Three lines under everything, about the things a player cannot see from here: what an
        // empty key does, where the key is kept, and how to get a long key into the field at all.
        client.fonts().body().draw(GuiLang.raw("gui.pvzce.jev.hint",
                        "Key 留空就用内置策略；填写并保存后，进入对战关即由 Jev 指挥对手。"),
                16, 58, 0.8F, 0.85F, 0.9F, 0.95F, 1F);
        client.fonts().body().draw(GuiLang.raw("gui.pvzce.jev.note",
                        "Key 只保存在本机 config/pvzce-client.toml，不会写进存档或日志。"),
                16, 44, 0.8F, 0.7F, 0.75F, 0.8F, 1F);
        client.fonts().body().draw(GuiLang.raw("gui.pvzce.jev.paste",
                        "点进输入框后按 Ctrl+V 可以粘贴。"),
                16, 30, 0.8F, 0.7F, 0.75F, 0.8F, 1F);
    }

    private void label(int row, String text) {
        client.fonts().body().draw(text, 16, labelY[row], 0.85F, 1F, 1F, 1F, 1F);
    }
}
