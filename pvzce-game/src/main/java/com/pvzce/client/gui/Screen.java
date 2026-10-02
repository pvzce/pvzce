package com.pvzce.client.gui;

import com.pvzce.api.util.Identifier;
import com.pvzce.client.PvzceClient;
import com.pvzce.client.gui.components.AbstractWidget;
import com.pvzce.client.gui.components.Dialog;
import com.pvzce.client.gui.components.EditBox;
import com.pvzce.client.input.ScrollRegion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.ArrayList;
import java.util.List;

/**
 * A modal GUI layer. Mirrors Minecraft's Screen responsibilities:
 * init/render/tick/mouse/key handling, with widgets as the building blocks.
 *
 * <p>All Screen coordinates are logical (GUI-scaled) pixels. Raw framebuffer
 * mouse coordinates are converted with {@link PvzceClient#guiMouseX} /
 * {@link PvzceClient#guiMouseY} before reaching widgets.
 *
 * <p>The screen also owns the two pieces of cross-cutting state that every screen
 * used to improvise: keyboard focus ({@link FocusManager}) and the modal dialog
 * stack. Both are exposed to subclasses so a screen never has to re-scan its own
 * widget list to find "the dialog that is currently on top".
 */
public abstract class Screen {
    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger("PVZCE/Input");
    /**
     * Default full-screen background used by every menu screen that does not
     * override {@link #backgroundTexture()}.
     */
    public static final Identifier DEFAULT_BACKGROUND_TEXTURE =
            Identifier.withDefaultNamespace("textures/gui/screen/title/title_background");

    protected final PvzceClient client;
    protected final List<AbstractWidget> widgets = new ArrayList<>();

    private final FocusManager focusManager = new FocusManager();
    private boolean initialized;

    protected Screen(PvzceClient client) {
        this.client = client;
    }

    public PvzceClient client() {
        return client;
    }

    /** Called once when the screen is first shown (or after a resize). */
    protected void init() {
    }

    /**
     * Releases whatever {@link #init()} acquired, when this screen leaves the stack.
     *
     * <p>Called exactly once per screen, for every way a screen can go away: a
     * {@link Navigation.Pop}, a root replacement, or the client shutting down. Before this
     * hook existed, a screen that attached animation playbacks or built preview entities in
     * {@code init()} had to remember to release them at each of its own exits -
     * {@code EditorScreen} did it in two separate methods, and any third exit would have
     * leaked silently.
     *
     * <p>Not called on resize: {@link #onResize()} rebuilds a screen in place, and the
     * screen survives it.
     */
    protected void onRemoved() {
    }

    /**
     * Where "back" goes from this screen.
     *
     * <p>Defaults to {@link Navigation#POP}: the screen was nested by
     * {@link PvzceClient#openScreen} and the one underneath is what the player expects to
     * see. A screen that the client installs as the root of a flow - after a level ends,
     * after a save is loaded - overrides this to name its own destination, because there is
     * nothing underneath it to reveal.
     *
     * <p>This replaces reading the stack depth. The depth cannot tell the difference between
     * "the world list is underneath me" and "I am alone because a level just ended", yet
     * {@code LevelSelectScreen} needs different destinations for those two cases.
     */
    public Navigation backTarget() {
        return Navigation.POP;
    }

    /** Called every client frame; the GUI projection is already active. */
    public abstract void render();

    /** Called every client frame before render. */
    public void tick() {
    }

    /**
     * Called when the user presses Escape or a screen-provided back button.
     * Screens may override this to route the request through a custom callback.
     */
    public void requestClose() {
        client.navigateBack();
    }

    /**
     * Override to give a screen its own full-window background image. Screens
     * that return {@code null} automatically use
     * {@link #DEFAULT_BACKGROUND_TEXTURE}.
     */
    protected Identifier backgroundTexture() {
        return null;
    }

    /**
     * True when this screen wants the frame it was opened over, blurred, behind it.
     *
     * <p>For screens that are a page *over* something the player was looking at - the settings
     * pages are the ones that exist today. It is off by default because most screens are a place of
     * their own with their own background, and a screen that asks for it but was never opened over
     * anything (the first screen of a session) falls back to {@link #renderBackground}.
     */
    public boolean blurredBackdrop() {
        return false;
    }

    /**
     * Draws the blurred frame this screen was opened over, with a flat tint over it, and answers
     * whether there was one. The tint matters: a blurred game view is still busy enough to fight
     * text. Callers fall back to {@link #renderBackground} when this returns false.
     */
    protected boolean renderBlurredBackdrop(float tintR, float tintG, float tintB, float tintA) {
        return client.backdrop().render(client.guiWidth(), client.guiHeight(),
                tintR, tintG, tintB, tintA);
    }

    /** When true, {@link #renderBackground} scales the image to cover the window instead of stretching it. */
    protected boolean backgroundCover() {
        return false;
    }

    /**
     * Where a cover-fitted background image ends up, in GUI pixels.
     *
     * <p>Exposed so a screen whose clickable panels are authored in background-image
     * pixels can map them the same way the image was drawn, instead of repeating the
     * cover-fit formula (which is how {@code LevelSetupScreen} drifted from the base
     * class: the drawn background and the clickable rectangles came from two
     * independent copies of the same algorithm).
     */
    public record CoverFit(float offsetX, float offsetY, float scale, float drawWidth, float drawHeight) {
        /** Maps a point authored in background-image pixels to GUI pixels. */
        public float mapX(float textureX) {
            return offsetX + textureX * scale;
        }

        /** Maps a y authored in top-down background-image pixels to GUI y (bottom-up). */
        public float mapY(float textureYFromTop, float textureHeight) {
            return offsetY + (textureHeight - textureYFromTop) * scale;
        }

        /**
         * The inverse: GUI x back to background-image pixels.
         *
         * <p>For a page that has to place authored content relative to something the
         * <em>window</em> decides - a line just above a button that sits at the bottom edge, say -
         * rather than relative to the art. Written here rather than at the call site so the two
         * directions cannot drift apart.
         */
        public float textureX(float guiX) {
            return scale == 0F ? 0F : (guiX - offsetX) / scale;
        }

        /** The inverse of {@link #mapY}: GUI y (bottom-up) back to top-down image pixels. */
        public float textureY(float guiY, float textureHeight) {
            return scale == 0F ? 0F : textureHeight - (guiY - offsetY) / scale;
        }
    }

    /**
     * The cover-fit transform, for a caller that draws a background itself.
     *
     * <p>Public so the shared menu-page canvas ({@code gui.layout.MenuPageCanvas}) can map the
     * title screen's field the way the title screen does. It is the same object
     * {@link #coverFit} returns: one formula, two callers, and no third copy of it.
     */
    public CoverFit coverFitFor(int textureWidth, int textureHeight) {
        return coverFit(textureWidth, textureHeight);
    }

    /** The cover-fit transform for an image of the given size in the current window. */
    protected CoverFit coverFit(int textureWidth, int textureHeight) {
        float scale = Math.max(client.guiWidth() / (float) Math.max(1, textureWidth),
                client.guiHeight() / (float) Math.max(1, textureHeight));
        float drawWidth = textureWidth * scale;
        float drawHeight = textureHeight * scale;
        return new CoverFit((client.guiWidth() - drawWidth) / 2F,
                (client.guiHeight() - drawHeight) / 2F, scale, drawWidth, drawHeight);
    }

    /**
     * Draws this screen's background (or the shared default image) stretched
     * to the full window. Falls back to the given flat color while the image
     * has not been installed yet.
     */
    protected void renderBackground(float fallbackR, float fallbackG, float fallbackB) {
        Identifier texture = backgroundTexture();
        if (texture == null) {
            texture = DEFAULT_BACKGROUND_TEXTURE;
        }
        int width = client.guiWidth();
        int height = client.guiHeight();
        if (client.hasTexture(texture)) {
            if (backgroundCover()) {
                var tex = client.textures().getOrLoad(texture);
                CoverFit fit = coverFit(tex.width(), tex.height());
                client.drawTexture(texture, fit.offsetX(), fit.offsetY(),
                        fit.drawWidth(), fit.drawHeight(), -1F, 1F, 1F, 1F, 1F);
            } else {
                client.drawTexture(texture, 0, 0, width, height, -1F, 1F, 1F, 1F, 1F);
            }
        } else {
            client.drawSolid(0, 0, width, height, -1F, fallbackR, fallbackG, fallbackB, 1F);
        }
    }

    /**
     * Char-wraps text into the band between {@code y} and {@code minY}. Text that
     * does not fit is truncated with an ellipsis so the reader can tell it was cut
     * rather than wondering why the description stops mid-sentence.
     *
     * <p>On the base class because more than one page draws a blob whose author did not lay it
     * out - a mod's description, a pack's - and the first of them had this privately.
     *
     * @return the y a block drawn under this text should start at: below the last line that was
     *         drawn, or {@code y} when nothing was
     */
    protected float drawWrappedText(String text, float x, float y, float maxWidth, float scale,
                                    float r, float g, float b, float a, float minY) {
        if (text == null || text.isEmpty()) {
            return y;
        }
        float lineHeight = client.fonts().body().lineHeight(scale) + 2;
        StringBuilder line = new StringBuilder();
        float cursorY = y;
        boolean truncated = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            String candidate = line.toString() + ch;
            boolean wrap = ch == '\n' || client.fonts().body().width(candidate, scale) > maxWidth;
            if (wrap) {
                if (!line.isEmpty()) {
                    if (cursorY - lineHeight < minY) {
                        truncated = true;
                        break;
                    }
                    client.fonts().body().draw(line.toString(), x, cursorY, scale, r, g, b, a);
                    cursorY -= lineHeight;
                    line.setLength(0);
                }
                if (ch != '\n') {
                    line.append(ch);
                }
            } else {
                line.append(ch);
            }
        }
        if (!line.isEmpty() && !truncated) {
            if (cursorY - lineHeight < minY) {
                truncated = true;
            } else {
                client.fonts().body().draw(line.toString(), x, cursorY, scale, r, g, b, a);
                cursorY -= lineHeight;
            }
        }
        if (truncated && cursorY >= minY) {
            client.fonts().body().draw("…", x, cursorY, scale, r, g, b, a);
            cursorY -= lineHeight;
        }
        return cursorY;
    }

    // ------------------------------------------------------------------
    // Focus
    // ------------------------------------------------------------------

    /** The screen's single keyboard-focus owner for text inputs. */
    public FocusManager focusManager() {
        return focusManager;
    }

    // ------------------------------------------------------------------
    // Dialog stack
    // ------------------------------------------------------------------

    /** Adds a dialog on top of this screen; visible modal dialogs receive input first. */
    public void showDialog(Dialog dialog) {
        wireFocus(dialog);
        widgets.add(dialog);
    }

    /** Closes and removes a dialog. */
    public void removeDialog(Dialog dialog) {
        if (focusManager.focused() != null && dialog.children().contains(focusManager.focused())) {
            focusManager.clear();
        }
        widgets.remove(dialog);
    }

    /** Every dialog currently on this screen, in stacking order. */
    public List<Dialog> dialogs() {
        List<Dialog> result = new ArrayList<>();
        for (AbstractWidget widget : widgets) {
            if (widget instanceof Dialog dialog && dialog.isVisible()) {
                result.add(dialog);
            }
        }
        return result;
    }

    /**
     * The topmost visible modal dialog, or {@code null}. Subclasses used to
     * re-implement this scan three times over, with subtly different filters.
     */
    protected Dialog modalDialog() {
        for (int i = widgets.size() - 1; i >= 0; i--) {
            AbstractWidget widget = widgets.get(i);
            if (widget instanceof Dialog dialog && dialog.isVisible() && dialog.isModal()) {
                return dialog;
            }
        }
        return null;
    }

    private void wireFocus(Dialog dialog) {
        for (AbstractWidget child : dialog.children()) {
            if (child instanceof EditBox box) {
                box.setFocusManager(focusManager);
            }
        }
    }

    // ------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------

    /**
     * Dispatches a click to the modal dialog when one is open, otherwise to the widgets.
     *
     * <p>{@code final} on purpose. A modal dialog must see input before the screen
     * behind it, and that used to be a convention each subclass had to honour by
     * calling {@code super.mouseClicked} <em>first</em> - which is not what a subclass
     * with its own hit regions naturally does. {@code LevelSelectScreen} checked its
     * card grid (most of the window) before delegating, so every button in the
     * "new level" dialog was unreachable: the grid swallowed the click and returned.
     * Screen-specific hit testing now goes in {@link #onMouseClicked}, which only runs
     * when no dialog is open.
     */
    public final void mouseClicked(double mouseX, double mouseY, int button) {
        dispatchMouseClicked(client.guiMouseX(mouseX), client.guiMouseY(mouseY), button);
    }

    /**
     * This screen's widgets, in dispatch order.
     *
     * <p>Read-only and public for tests, which have to reach a control that has no accessor of
     * its own (the player list on the title screen) to drive a real click through
     * {@link #dispatchMouseClicked} rather than calling the screen's action directly.
     */
    public java.util.List<AbstractWidget> widgets() {
        return java.util.List.copyOf(widgets);
    }

    /**
     * The click dispatch itself, on logical GUI coordinates.
     *
     * <p>Separate from {@link #mouseClicked} so it can be driven without a window: the
     * only thing the raw entry point adds is the framebuffer-to-GUI conversion, and a
     * test that wants to prove a dialog receives a click should not have to create a GL
     * context to do it.
     */
    public void dispatchMouseClicked(double guiX, double guiY, int button) {
        // Development diagnostic (`-Dpvzce.traceInput=true`): which widget a click landed on.
        // A synthetic click that misses by one row is otherwise completely silent - the screen
        // simply does nothing - and this is the only place that knows the geometry it was
        // aimed at. See the smoke guide for the other trace switches.
        if (Boolean.getBoolean("pvzce.traceInput")) {
            StringBuilder where = new StringBuilder();
            for (AbstractWidget widget : widgets) {
                where.append(' ').append(widget.getClass().getSimpleName())
                        .append('[').append(widget.x()).append(',').append(widget.y())
                        .append(' ').append(widget.width()).append('x').append(widget.height())
                        .append(widget.isMouseOver(guiX, guiY) ? " HIT" : "").append(']');
            }
            LOGGER.info("input trace: click {},{} on {}:{}",
                    guiX, guiY, getClass().getSimpleName(), where);
        }
        Dialog modal = modalDialog();
        if (modal != null) {
            if (!modal.mouseClicked(guiX, guiY, button)) {
                focusManager.clear();
            }
            return;
        }
        for (AbstractWidget widget : widgets) {
            if (widget.isMouseOver(guiX, guiY) && widget.mouseClicked(guiX, guiY, button)) {
                // The widget that takes the click takes the keyboard; anything else
                // releases it. Without this, focus could only ever be gained.
                focusManager.request(widget instanceof EditBox box ? box : null);
                return;
            }
        }
        focusManager.clear();
        onMouseClicked(guiX, guiY, button);
    }

    /**
     * Screen-specific click handling on logical GUI coordinates, called only when no
     * modal dialog is open. Override this instead of {@link #mouseClicked}.
     */
    protected void onMouseClicked(double guiX, double guiY, int button) {
    }

    /**
     * Raw framebuffer X for a logical GUI X.
     *
     * <p>Exists for the one caller that cannot work in GUI space: the in-game camera
     * maps raw GLFW cursor positions to board cells itself (including the top-down Y
     * flip), so {@code InGameScreen} needs the raw pair. Everywhere else should use the
     * GUI coordinates the hooks receive.
     */
    protected final double rawMouseX(double guiX) {
        return guiX * Math.max(1, client.window().width()) / (double) Math.max(1, client.guiWidth());
    }

    /** Raw framebuffer Y (top-down, as GLFW reports it) for a logical GUI Y. */
    protected final double rawMouseY(double guiY) {
        return Math.max(1, client.window().height())
                - guiY * Math.max(1, client.window().height()) / (double) Math.max(1, client.guiHeight());
    }

    /**
     * Called every frame so widgets can keep their hover state in sync with the cursor.
     *
     * <p>{@code final} for the same reason as {@link #mouseClicked}: with a dialog open
     * the screen behind it must not also track hover, or both highlight at once.
     */
    public final void mouseMoved(double mouseX, double mouseY) {
        double guiX = client.guiMouseX(mouseX);
        double guiY = client.guiMouseY(mouseY);
        Dialog modal = modalDialog();
        if (modal != null) {
            modal.mouseMoved(guiX, guiY);
            return;
        }
        for (AbstractWidget widget : widgets) {
            widget.mouseMoved(guiX, guiY);
        }
        onMouseMoved(guiX, guiY);
    }

    /** Screen-specific hover tracking, called only when no modal dialog is open. */
    protected void onMouseMoved(double guiX, double guiY) {
    }

    /**
     * A button coming back up, on logical GUI coordinates.
     *
     * <p>{@code final} for the same reason as {@link #mouseClicked}: the modal dispatch below
     * is what keeps a dialog owning the mouse, and a subclass that overrode this would have
     * to remember to repeat it. Screens handle their own release in
     * {@link #onMouseReleased}.
     */
    public final void mouseReleased(double mouseX, double mouseY, int button) {
        double guiX = client.guiMouseX(mouseX);
        double guiY = client.guiMouseY(mouseY);
        Dialog modal = modalDialog();
        if (modal != null) {
            modal.mouseReleased(guiX, guiY, button);
            return;
        }
        for (AbstractWidget widget : widgets) {
            widget.mouseReleased(guiX, guiY, button);
        }
        onMouseReleased(guiX, guiY, button);
    }

    /** Screen-specific button release, called only when no modal dialog is open. */
    protected void onMouseReleased(double guiX, double guiY, int button) {
    }

    /**
     * The cursor moving with a button held, on logical GUI coordinates.
     *
     * <p>Sent every frame while the button is down (see {@code PvzceClient.pollInput}), which
     * is what makes drag interactions possible at all: sweeping over pickups, or carrying a
     * card from the bar to a cell.
     */
    public final void mouseDragged(double mouseX, double mouseY, int button) {
        double guiX = client.guiMouseX(mouseX);
        double guiY = client.guiMouseY(mouseY);
        Dialog modal = modalDialog();
        if (modal != null) {
            modal.mouseDragged(guiX, guiY, button);
            return;
        }
        for (AbstractWidget widget : widgets) {
            widget.mouseDragged(guiX, guiY, button);
        }
        onMouseDragged(guiX, guiY, button);
    }

    /** Screen-specific drag handling, called only when no modal dialog is open. */
    protected void onMouseDragged(double guiX, double guiY, int button) {
    }

    public final void mouseScrolled(double mouseX, double mouseY, double amount) {
        double guiX = client.guiMouseX(mouseX);
        double guiY = client.guiMouseY(mouseY);
        Dialog modal = modalDialog();
        if (modal != null) {
            modal.mouseScrolled(guiX, guiY, amount);
            return;
        }
        for (AbstractWidget widget : widgets) {
            if (widget.isMouseOver(guiX, guiY)) {
                widget.mouseScrolled(guiX, guiY, amount);
            }
        }
        onMouseScrolled(guiX, guiY, amount);
    }

    /** Screen-specific scroll handling, called only when no modal dialog is open. */
    protected void onMouseScrolled(double guiX, double guiY, double amount) {
    }

    /**
     * The scroll region a press at this point would drive, or {@code null} for "an ordinary press".
     *
     * <p>This is what tells the touch gesture layer where a swipe is allowed to scroll
     * ({@code client.input.PointerGesture}): inside a region the press is held back until the finger
     * comes up, so a swipe cannot also press whatever it started on - and pressing is destructive in
     * the screens that scroll (the shop buys, the player picker switches, the card bar selects).
     *
     * <p>The order is the order a click takes - modal dialog first, then the widgets, then the
     * screen's own hook - because the gesture and the click have to agree about which layer owns a
     * point. {@code final} for the same reason the mouse dispatch above it is.
     */
    public final ScrollRegion scrollRegionAt(double guiX, double guiY) {
        if (AbstractWidget.claimsDragAt(widgets, guiX, guiY)) {
            return null;
        }
        Dialog modal = modalDialog();
        if (modal != null) {
            return modal.scrollRegionAt(guiX, guiY);
        }
        ScrollRegion widget = AbstractWidget.regionAt(widgets, guiX, guiY);
        return widget != null ? widget : onScrollRegionAt(guiX, guiY);
    }

    /**
     * Screen-specific scroll region, called only when no modal dialog is open and no widget took the
     * point. A screen that scrolls its own drawing - the card bar, the seed pool, the level grid, the
     * almanac - overrides this and returns the <em>same</em> rectangle its wheel handler tests, so
     * that "can it scroll" and "does a swipe scroll it" cannot drift apart.
     */
    protected ScrollRegion onScrollRegionAt(double guiX, double guiY) {
        return null;
    }

    public void keyPressed(int key) {
        Dialog modal = modalDialog();
        if (modal != null) {
            modal.keyPressed(key);
            return;
        }
        for (AbstractWidget widget : widgets) {
            if (widget.keyPressed(key)) {
                return;
            }
        }
    }

    public void charTyped(char codepoint) {
        Dialog modal = modalDialog();
        if (modal != null) {
            modal.charTyped(codepoint);
            return;
        }
        for (AbstractWidget widget : widgets) {
            if (widget.charTyped(codepoint)) {
                return;
            }
        }
    }

    /**
     * Rebuilds the screen for a new window size.
     *
     * <p>Dialogs survive the resize. Clearing the widget list used to delete any
     * dialog opened through {@link #showDialog}, and because callers guard against
     * re-showing with a "prompt is open" flag, the dialog could never come back:
     * a window resize while the "found a save" prompt was up made continue/restart
     * permanently unreachable for that level.
     */
    public void onResize() {
        List<Dialog> open = dialogs();
        widgets.clear();
        focusManager.clear();
        initialized = false;
        // Base widgets must exist at the new size before a dialog is laid out on top.
        initIfNeeded();
        for (Dialog dialog : open) {
            dialog.onResize(client.guiWidth(), client.guiHeight());
            wireFocus(dialog);
            // A dialog that lives in a field and is (re-)added by init() is already back in
            // the list; adding it again would draw it twice and keep one copy per resize.
            if (!widgets.contains(dialog)) {
                widgets.add(dialog);
            }
        }
    }

    public final void initIfNeeded() {
        if (!initialized) {
            initialized = true;
            init();
        }
    }

    protected void addWidget(AbstractWidget widget) {
        if (widget instanceof EditBox box) {
            box.setFocusManager(focusManager);
        }
        widgets.add(widget);
    }

    protected void clearWidgets() {
        focusManager.clear();
        widgets.clear();
    }

    public boolean isInitialized() {
        return initialized;
    }

    public boolean hasTextInputFocused() {
        return focusManager.hasTextFocus();
    }

    /**
     * Routes pasted text into whichever field has the keyboard, if any.
     *
     * <p>Goes through the focus manager rather than "the first EditBox on the page": a page may hold
     * several fields, and the one that owns the keyboard is the same one a typed character would
     * have reached. The modal is asked first, because that is where the keyboard is when one is up.
     *
     * @return true when some field took the text
     */
    public boolean pasteText(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        EditBox box = focusManager.focused();
        if (box != null && box.isFocused() && box.appendText(text)) {
            return true;
        }
        Dialog modal = modalDialog();
        return modal != null && modal.pasteText(text);
    }

    /** Helper: centered horizontal position in logical GUI pixels. */
    protected int centerX(int width) {
        return (client.guiWidth() - width) / 2;
    }

}
