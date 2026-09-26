package com.pvzce.client.gui.screens;

import com.pvzce.client.PvzceClient;
import com.pvzce.client.gui.GuiLang;
import com.pvzce.client.gui.Screen;
import com.pvzce.client.gui.components.AbstractSelectionList;
import com.pvzce.client.gui.components.Button;
import com.pvzce.client.gui.layout.GuiLayout;
import com.pvzce.client.input.KeyBindings;
import org.lwjgl.glfw.GLFW;

/**
 * The key bindings, one row per action.
 *
 * <p>Click a row and press a key: the page listens for exactly one press and binds it. A row being
 * listened to says so instead of showing a key, because the alternative - "did my click register, or
 * is it waiting for a key?" - is the one thing a rebinding UI must never be ambiguous about.
 *
 * <p>Two keys are refused: ESC, because it is how the player gets out of anything (and of this page
 * itself), and any key already bound, which {@link KeyBindings#bind} takes away from the action that
 * had it. That is stated on the row that lost it rather than hidden: a player who binds F3 to a tool
 * should see the debug row go blank.
 */
public final class KeybindScreen extends Screen {
    /** One row per action, in the enum's own order. */
    private static final KeyBindings.Action[] ACTIONS = KeyBindings.Action.values();

    private AbstractSelectionList<KeyBindings.Action> list;
    /** The action waiting for a key, or {@code null} when the page is just being read. */
    private KeyBindings.Action listening;
    private int titleY;
    private float titleScale;

    public KeybindScreen(PvzceClient client) {
        super(client);
    }

    @Override
    protected void init() {
        int guiH = client.guiHeight();
        int guiW = client.guiWidth();
        int titleReserve = Math.max(56, Math.min(104, guiH / 3));
        int footerHeight = GuiLayout.fitHeight(guiH, 60, 5, titleReserve, 8);
        int rowHeight = Math.max(24, Math.min(40, (guiH - titleReserve - footerHeight - 20)
                / Math.max(1, ACTIONS.length)));
        int listBottom = footerHeight + 12;
        int listHeight = Math.max(60, guiH - titleReserve - listBottom);
        list = new AbstractSelectionList<>(centerX(Math.min(520, guiW - 16)), listBottom,
                Math.min(520, guiW - 16), listHeight, rowHeight, this::renderRow);
        list.setEntries(java.util.Arrays.asList(ACTIONS));
        list.setOnRowClick(this::beginListening);
        addWidget(list);

        titleScale = Math.min(2F, Math.max(1.2F, guiH / 140F));
        titleY = guiH - titleReserve + Math.round(client.fonts().button().lineHeight(titleScale) * 0.4F);

        int buttonWidth = Math.min(180, guiW / 3);
        int y = Math.max(8, (int) (guiH * 0.04F)) + footerHeight / 2;
        addWidget(new Button(centerX(buttonWidth * 2 + 8), y - footerHeight / 2, buttonWidth,
                footerHeight, GuiLang.raw("pvzce.key.reset", "恢复默认"), this::resetAll));
        addWidget(new Button(centerX(buttonWidth * 2 + 8) + buttonWidth + 8, y - footerHeight / 2,
                buttonWidth, footerHeight, GuiLang.raw("pvzce.back", "返回"), this::requestClose));
    }

    /** Starts listening for the row that was clicked; clicking it again cancels. */
    private void beginListening(KeyBindings.Action action) {
        listening = listening == action ? null : action;
    }

    private void resetAll() {
        listening = null;
        client.keyBindings().resetToDefaults();
        client.saveKeyBindings();
    }

    @Override
    public void keyPressed(int key) {
        if (listening != null) {
            if (key == GLFW.GLFW_KEY_ESCAPE) {
                // Escape cancels rather than binding: it is the key that leaves everything, and a
                // player who opens a rebind by mistake would otherwise have to bind something to
                // get out - having lost whatever ESC used to do.
                listening = null;
                return;
            }
            client.keyBindings().bind(listening, key);
            listening = null;
            client.saveKeyBindings();
            return;
        }
        super.keyPressed(key);
    }

    /** One row: the action's name on the left, its key (or the listening prompt) on the right. */
    private void renderRow(PvzceClient renderClient, KeyBindings.Action action, int x, int y) {
        int rowHeight = list.entryHeight();
        boolean selected = list.selected() == action;
        if (selected) {
            com.pvzce.client.renderer.SpriteRenderer.solid(x - 4, y, list.width() - 6, rowHeight,
                    0.05F, 0.3F, 0.4F, 0.5F, 0.55F);
        }
        String name = GuiLang.raw(KeyBindings.describe(action), action.key());
        float baseline = y + (rowHeight - renderClient.fonts().body().lineHeight(1F)) / 2F;
        renderClient.fonts().body().draw(name, x + 4, baseline, 1F, 1F, 1F, 1F, 1F);

        String right;
        float r = 0.85F;
        float g = 0.9F;
        float b = 0.7F;
        if (listening == action) {
            right = GuiLang.raw("pvzce.key.listening", "按下要绑定的键…");
            r = 0.35F;
            g = 0.95F;
            b = 0.55F;
        } else {
            int code = client.keyBindings().code(action);
            right = code == GLFW.GLFW_KEY_UNKNOWN
                    ? GuiLang.raw("pvzce.key.unbound", "未绑定")
                    : KeyBindings.keyName(code);
            if (code == GLFW.GLFW_KEY_UNKNOWN) {
                g = 0.6F;
            }
        }
        renderClient.fonts().body().draw(right,
                x + list.width() - renderClient.fonts().body().width(right, 1F) - 14F,
                baseline, 1F, r, g, b, 1F);
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
        String title = GuiLang.raw("pvzce.key.title", "快捷键");
        client.fonts().button().draw(title,
                (client.guiWidth() - client.fonts().button().width(title, titleScale)) / 2F,
                titleY, titleScale, 1F, 1F, 1F, 1F);
        String hint = GuiLang.raw("pvzce.key.hint", "点一行，再按一个键；Esc 取消");
        client.fonts().body().draw(hint,
                (client.guiWidth() - client.fonts().body().width(hint, 0.9F)) / 2F,
                titleY - client.fonts().button().lineHeight(titleScale) * 0.9F, 0.9F,
                0.85F, 0.9F, 0.85F, 1F);
        for (var widget : widgets) {
            widget.render(client);
        }
    }
}
