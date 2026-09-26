package com.pvzce.client.gui.screens;

import com.pvzce.client.PvzceClient;
import com.pvzce.client.gui.GuiLang;
import com.pvzce.client.gui.Screen;
import com.pvzce.client.gui.components.Button;
import com.pvzce.client.gui.layout.GuiLayout;
import com.pvzce.common.level.Difficulty;

/**
 * The difficulty tier, one row each.
 *
 * <p>Four rows rather than a cycling button: the whole point of the setting is that a player can
 * see what the tiers <em>are</em> before choosing one, and the numbers that separate them are the
 * answer to "why is this harder". Each row therefore says what it changes, and the current tier is
 * marked rather than merely selected - a settings page that looked the same on every tier would
 * make "did that take effect" unanswerable.
 *
 * <p>The choice goes to the server and comes back in the profile snapshot, so this page never
 * applies anything itself. It can be switched mid-level: the server unfolds the old tier out of the
 * level's rules and folds the new one in, and entities already on the lawn keep what they spawned
 * with.
 */
public final class DifficultyScreen extends Screen {
    /** The rows, in the order they are listed: easiest first, which is also how they read. */
    private static final Difficulty[] TIERS = Difficulty.values();

    private int titleY;
    private float titleScale;

    public DifficultyScreen(PvzceClient client) {
        super(client);
    }

    @Override
    protected void init() {
        int guiH = client.guiHeight();
        int buttonWidth = Math.min(360, client.guiWidth() - 24);
        // Taller than the hub's reserve: this page has a hint line above the title, and a title
        // that sat where it does everywhere else left no room for it - the line was drawn under
        // the first button and never seen.
        int titleReserve = Math.max(56, Math.min(104, guiH / 3));
        int buttonHeight = GuiLayout.fitHeight(guiH, 68, TIERS.length + 1, titleReserve, 8);
        int gap = GuiLayout.gapFor(buttonHeight);
        int buttonTop = (guiH - titleReserve) - gap;
        titleScale = Math.min(2F, Math.max(1.2F, guiH / 140F));
        titleY = buttonTop + Math.round(client.fonts().button().lineHeight(titleScale) * 0.35F);
        int x = centerX(buttonWidth);
        Difficulty current = client.profile().difficulty();
        for (int i = 0; i < TIERS.length; i++) {
            Difficulty tier = TIERS[i];
            int y = buttonTop - buttonHeight - i * (buttonHeight + gap);
            addWidget(new Button(x, y, buttonWidth, buttonHeight, label(tier, current),
                    () -> client.requestDifficulty(tier)));
        }
        int backY = buttonTop - buttonHeight - TIERS.length * (buttonHeight + gap);
        addWidget(new Button(x, backY, buttonWidth, buttonHeight,
                GuiLang.raw("pvzce.back", "返回"), this::requestClose));
    }

    /** The row's own line: the tier's name, what it changes, and whether it is the one in force. */
    private static String label(Difficulty tier, Difficulty current) {
        String name = GuiLang.raw("pvzce.difficulty." + tier.key(), tier.key());
        String mark = tier == current ? "● " : "　";
        return mark + name + "　" + summary(tier);
    }

    /**
     * What a tier does to the four numbers, in one line.
     *
     * <p>Spelled out rather than described ("harder", "for experts"): the setting's whole job is to
     * change numbers, and a player deciding between two tiers wants to know which numbers.
     *
     * <p><b>Every tier gets all four</b>, the original included (as four ones). The button sizes
     * its own label to fit, so a shorter row would simply be drawn in a bigger font - a page whose
     * four rows are four different sizes reads as four unrelated things. That the original's column
     * is all ones is also the clearest way to say what "普通 = 原版" means.
     */
    private static String summary(Difficulty tier) {
        return GuiLang.raw("pvzce.difficulty.hp", "血") + "×" + trim(tier.zombieHealth())
                + GuiLang.raw("pvzce.difficulty.speed", " 速") + "×" + trim(tier.zombieSpeed())
                + GuiLang.raw("pvzce.difficulty.spawn", " 出怪") + "×"
                + trim(1F / tier.spawnInterval()) + GuiLang.raw("pvzce.difficulty.sun", " 阳光") + "×"
                + trim(tier.sunRate());
    }

    /** One decimal, without a trailing {@code .0}: {@code 1}, {@code 1.3}, {@code 0.85}. */
    private static String trim(float value) {
        String text = String.format(java.util.Locale.ROOT, "%.2f", value);
        while (text.endsWith("0")) {
            text = text.substring(0, text.length() - 1);
        }
        return text.endsWith(".") ? text.substring(0, text.length() - 1) : text;
    }

    @Override
    public boolean blurredBackdrop() {
        return true;
    }

    @Override
    public void render() {
        client.beginGuiView();
        if (!renderBlurredBackdrop(0.10F, 0.11F, 0.14F, 0.62F)) {
            renderBackground(0.08F, 0.1F, 0.12F);
        }
        String title = GuiLang.raw("pvzce.difficulty.title", "难度");
        client.fonts().button().draw(title,
                (client.guiWidth() - client.fonts().button().width(title, titleScale)) / 2F,
                titleY, titleScale, 1, 1, 1, 1);
        // The one sentence the table cannot say by itself, above the title rather than inside a
        // row: it is about the column as a whole. Anchored off the title's own ascent, so it sits
        // clear of it whatever the window is.
        String hint = GuiLang.raw("pvzce.difficulty.hint", "普通 = 原版难度，随时可以改");
        client.fonts().body().draw(hint,
                (client.guiWidth() - client.fonts().body().width(hint, 0.9F)) / 2F,
                titleY + client.fonts().button().ascent(titleScale) + 8F, 0.9F,
                0.85F, 0.9F, 0.85F, 1F);
        for (var widget : widgets) {
            widget.render(client);
        }
    }
}
