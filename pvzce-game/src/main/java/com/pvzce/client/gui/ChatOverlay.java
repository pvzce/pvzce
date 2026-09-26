package com.pvzce.client.gui;

import com.pvzce.client.PvzceClient;
import com.pvzce.client.gui.components.EditBox;
import com.pvzce.client.gui.components.AbstractWidget;
import com.pvzce.client.input.ScrollRegion;
import com.pvzce.common.network.packet.ChatC2S;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * The chat line: what the player says, and the log of everything the server has said.
 *
 * <p>A thin strip along the bottom rather than a panel in the middle. The console is the panel -
 * it is a tool, it takes over the screen and it is open for a while - while chat is a line you
 * type and forget. The two used to be the same thing on two keys: {@code T} opened an empty
 * console, which is why "chat" and "command" were indistinguishable.
 *
 * <p><b>The log is the level's own.</b> Every message the server sends already goes into
 * {@code ClientLevel}'s list, which the HUD draws the last few of; this shows a longer window of
 * the same list and can scroll back through it. A second log would be the same facts twice, and the
 * two would eventually disagree about what was said.
 *
 * <p><b>What the player types goes to the server</b> ({@code ChatC2S}) and comes back as an
 * ordinary server message. It would be simpler to append it locally, and it would be wrong: a
 * message the server never saw is not in anyone else's log, and a multiplayer build would then have
 * one client whose chat only it can see.
 */
public final class ChatOverlay extends Overlay {
    /** How many lines the closed overlay shows; the open one shows the same, plus the input. */
    private static final int VISIBLE_LINES = 8;
    private static final int LINE_HEIGHT = 20;
    private static final int INPUT_HEIGHT = 26;
    /** How much of the window's width the text may use, so a long line wraps instead of running off. */
    private static final float MAX_WIDTH_FRACTION = 0.72F;

    private EditBox input;
    private String typed = "";
    /** Lines scrolled back from the newest; zero means the newest line is the bottom one. */
    private int scroll;

    public ChatOverlay(PvzceClient client) {
        super(client);
    }

    @Override
    protected void init() {
        int width = Math.max(160, client.guiWidth() - 8);
        input = new EditBox(4, 4, width, INPUT_HEIGHT, 256, this::submit);
        input.setBordered(false);
        input.setFocused(true);
        addWidget(input);
        input.setValue(typed);
    }

    /** Closes the line and sends what was typed. */
    private void submit() {
        String line = input == null ? typed : input.value();
        client.dismissOverlay(this);
        if (line != null && !line.isBlank()) {
            client.connection().send(new ChatC2S(line.trim()));
        }
    }

    /**
     * A rebuild (the window was resized) throws the widget away, so the text lives here.
     *
     * <p>Same rule the console follows, and for the same reason: a resize mid-sentence must not eat
     * what the player was typing.
     */
    private void rememberTyped() {
        if (input != null) {
            typed = input.value();
        }
    }

    @Override
    public void tick() {
        rememberTyped();
    }

    @Override
    public void keyPressed(int key) {
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            client.dismissOverlay(this);
            return;
        }
        if (key == GLFW.GLFW_KEY_PAGE_UP) {
            scrollBack(VISIBLE_LINES);
            return;
        }
        if (key == GLFW.GLFW_KEY_PAGE_DOWN) {
            scrollBack(-VISIBLE_LINES);
            return;
        }
        super.keyPressed(key);
    }

    /** Walks the log; positive is further back, and the newest line is the floor. */
    private void scrollBack(int lines) {
        int oldest = Math.max(0, lines().size() - VISIBLE_LINES);
        scroll = Math.max(0, Math.min(oldest, scroll + lines));
    }

    @Override
    protected ScrollRegion onScrollRegionAt(double guiX, double guiY) {
        // The whole strip scrolls, so a wheel over the log walks it back the same way PageUp does.
        // The rectangle itself is this overlay's own business (see `onMouseScrolled`); a region is
        // only "which way does a finger travel, and how far is one step".
        return guiY <= stripHeight()
                ? new ScrollRegion(ScrollRegion.Axis.VERTICAL, LINE_HEIGHT,
                        ScrollRegion.Swipe.DRAGS_CONTENT)
                : null;
    }

    @Override
    protected void onMouseScrolled(double guiX, double guiY, double amount) {
        if (guiY <= stripHeight()) {
            scrollBack(amount > 0 ? 1 : -1);
        }
    }

    /** How tall the strip is: the log plus the input line. */
    private float stripHeight() {
        return VISIBLE_LINES * LINE_HEIGHT + INPUT_HEIGHT + 8;
    }

    /** Everything the server has said, oldest first. */
    private List<String> lines() {
        return client.level() == null ? List.of() : client.level().messages();
    }

    @Override
    public void render() {
        int guiW = client.guiWidth();
        List<String> all = lines();
        float panelWidth = guiW * MAX_WIDTH_FRACTION;
        float bottom = INPUT_HEIGHT + 6;
        float panelHeight = VISIBLE_LINES * LINE_HEIGHT + 8;

        // The history, oldest at the top of the window. Scrolled back, the newest line is no longer
        // the bottom one - which is the whole point of scrolling.
        int newest = Math.max(0, all.size() - 1 - scroll);
        int first = Math.max(0, newest - VISIBLE_LINES + 1);
        // Nearly opaque on purpose: the HUD draws the same message list in the same corner, and
        // two copies of it a few pixels apart is what a half-transparent panel looked like. This
        // strip *is* the log while it is open.
        client.drawSolid(0F, 0F, panelWidth, bottom + panelHeight, 0.55F, 0.02F, 0.03F, 0.05F, 0.86F);
        float y = bottom + panelHeight - LINE_HEIGHT;
        for (int i = first; i <= newest && i < all.size(); i++) {
            client.fonts().body().draw(all.get(i), 6F, y, 0.9F, 1F, 1F, 1F, 1F);
            y -= LINE_HEIGHT;
        }

        // The input line, on its own darker strip so it reads as "this is where you type".
        client.drawSolid(0F, 0F, panelWidth, bottom, 0.6F, 0.05F, 0.06F, 0.09F, 0.94F);
        for (AbstractWidget widget : widgets) {
            widget.render(client);
        }
    }
}
