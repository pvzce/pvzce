package com.pvzce.client.gui;

import com.pvzce.client.PvzceClient;
import com.pvzce.client.gui.components.AbstractWidget;
import com.pvzce.client.gui.components.EditBox;
import com.pvzce.client.input.ScrollRegion;

import java.util.ArrayList;
import java.util.List;

/**
 * A layer drawn over the current screen without becoming part of the navigation stack.
 *
 * <p>The console is the first of these. It used to be an ordinary {@link Screen} that was
 * pushed onto the stack, which meant the client had to special-case it in two places to
 * keep driving (and drawing) the screen underneath - and, worse, the pushed entry counted
 * towards {@link ScreenStack#depth()}, so a screen asking "am I the only one here?" got a
 * different answer while the console happened to be open.
 *
 * <p>An overlay is deliberately outside that stack: the screen underneath keeps its
 * lifecycle, the navigation stack keeps its meaning, and any number of overlay kinds can
 * exist without adding a case to the client's frame loop. What an overlay owns is the
 * same shape a screen owns - widgets, one keyboard focus, its own input handling - because
 * a command line needs an {@link EditBox} and that box needs the focus manager.
 *
 * <p>An overlay is created when it opens and discarded when it closes. Like a screen it
 * builds its widgets lazily ({@link #initIfNeeded()}), because building them needs a window
 * and a freshly constructed overlay may be driven by a test that has none.
 */
public abstract class Overlay {
    protected final PvzceClient client;
    protected final List<AbstractWidget> widgets = new ArrayList<>();

    private final FocusManager focusManager = new FocusManager();
    private boolean initialized;

    protected Overlay(PvzceClient client) {
        this.client = client;
    }

    public PvzceClient client() {
        return client;
    }

    /** Builds the overlay's widgets, once, on first use. */
    protected void init() {
    }

    /** Runs {@link #init()} the first time the overlay is used. */
    public final void initIfNeeded() {
        if (!initialized) {
            initialized = true;
            init();
        }
    }

    /** Called every client frame before {@link #render()}. */
    public void tick() {
    }

    /**
     * Draws the overlay. The GUI projection is already active from the screen pass; the
     * overlay must not assume it is the only thing on screen, so it draws only its own
     * layer and leaves the background to the screen below.
     */
    public abstract void render();

    /** True while a text box holds the keyboard, so the client can route typing to it. */
    public boolean hasTextInputFocused() {
        return focusManager.hasTextFocus();
    }

    /**
     * Handles a key press.
     *
     * <p>An overlay owns the keyboard while it is open, so this is called instead of the
     * screen's handler. Subclasses end their own implementation with
     * {@link #keyPressedToWidgets} so a text box still receives ordinary typing.
     */
    public void keyPressed(int key) {
        keyPressedToWidgets(key);
    }

    /**
     * Rebuilds the overlay for a new window size.
     *
     * <p>Drops the widgets and lets {@link #initIfNeeded()} build them again at the new
     * size. Subclasses that hold state the player can see (typed text, a selection) restore
     * it in {@link #init()}.
     */
    public void onResize() {
        focusManager.clear();
        widgets.clear();
        initialized = false;
    }

    // ------------------------------------------------------------------
    // Widget plumbing, same contract as Screen
    // ------------------------------------------------------------------

    public FocusManager focusManager() {
        return focusManager;
    }

    protected void addWidget(AbstractWidget widget) {
        if (widget instanceof EditBox box) {
            box.setFocusManager(focusManager);
        }
        widgets.add(widget);
    }

    /**
     * Sends a key to the widgets, first one to consume it wins.
     *
     * <p>Subclasses call this at the end of their own {@code keyPressed} so an
     * {@link EditBox} still receives ordinary typing after the overlay has handled its own
     * shortcuts.
     */
    protected void keyPressedToWidgets(int key) {
        for (AbstractWidget widget : widgets) {
            if (widget.keyPressed(key)) {
                return;
            }
        }
    }

    /** Sends a typed character to the focused widget. */
    public void charTyped(char codepoint) {
        for (AbstractWidget widget : widgets) {
            if (widget.charTyped(codepoint)) {
                return;
            }
        }
    }

    /** Forwards a click, on logical GUI coordinates. */
    public void mouseClicked(double guiX, double guiY, int button) {
        for (AbstractWidget widget : widgets) {
            if (widget.isMouseOver(guiX, guiY) && widget.mouseClicked(guiX, guiY, button)) {
                focusManager.request(widget instanceof EditBox box ? box : null);
                return;
            }
        }
        focusManager.clear();
        onMouseClicked(guiX, guiY, button);
    }

    /** Overlay-specific click handling, called only when no widget consumed the click. */
    protected void onMouseClicked(double guiX, double guiY, int button) {
    }

    /** Forwards a button release, on logical GUI coordinates. */
    public void mouseReleased(double guiX, double guiY, int button) {
        for (AbstractWidget widget : widgets) {
            widget.mouseReleased(guiX, guiY, button);
        }
        onMouseReleased(guiX, guiY, button);
    }

    /** Overlay-specific release handling. */
    protected void onMouseReleased(double guiX, double guiY, int button) {
    }

    /** Forwards the cursor moving with a button held, on logical GUI coordinates. */
    public void mouseDragged(double guiX, double guiY, int button) {
        for (AbstractWidget widget : widgets) {
            widget.mouseDragged(guiX, guiY, button);
        }
        onMouseDragged(guiX, guiY, button);
    }

    /** Overlay-specific drag handling. */
    protected void onMouseDragged(double guiX, double guiY, int button) {
    }

    /** Forwards hover state so widgets can highlight. */
    public void mouseMoved(double guiX, double guiY) {
        for (AbstractWidget widget : widgets) {
            widget.mouseMoved(guiX, guiY);
        }
        onMouseMoved(guiX, guiY);
    }

    /** Overlay-specific hover tracking. */
    protected void onMouseMoved(double guiX, double guiY) {
    }

    /** Forwards a scroll, on logical GUI coordinates. */
    public void mouseScrolled(double guiX, double guiY, double amount) {
        for (AbstractWidget widget : widgets) {
            if (widget.isMouseOver(guiX, guiY)) {
                widget.mouseScrolled(guiX, guiY, amount);
            }
        }
        onMouseScrolled(guiX, guiY, amount);
    }

    /** Overlay-specific scroll handling. */
    protected void onMouseScrolled(double guiX, double guiY, double amount) {
    }

    /**
     * The scroll region a press at this point would drive, or {@code null}.
     *
     * <p>Mirrors {@code Screen.scrollRegionAt} for the layer that floats above screens, in the same
     * order ({@link #mouseScrolled} feeds widgets first, then the overlay's own hook). The console is
     * the only overlay there is, and it scrolls its message log.
     */
    public final ScrollRegion scrollRegionAt(double guiX, double guiY) {
        if (AbstractWidget.claimsDragAt(widgets, guiX, guiY)) {
            return null;
        }
        ScrollRegion widget = AbstractWidget.regionAt(widgets, guiX, guiY);
        return widget != null ? widget : onScrollRegionAt(guiX, guiY);
    }

    /**
     * Overlay-specific scroll region. Must be the same rectangle {@link #onMouseScrolled} tests, so
     * a swipe and the wheel cannot come apart.
     */
    protected ScrollRegion onScrollRegionAt(double guiX, double guiY) {
        return null;
    }
}
