package com.pvzce.client.gui;

import com.pvzce.client.PvzceClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * The screen stack: which full-window screen is on top, and what leaving it means.
 *
 * <p>Three operations, and the difference between them is the whole point:
 *
 * <ul>
 *   <li>{@link #replace} - this screen is a new <em>root</em>. Whatever was below it is
 *       gone (a level just ended, the player changed world, a save was reloaded). Used by
 *       the {@code showX} entry points and by {@code onLevelInit}.</li>
 *   <li>{@link #push} - this screen <em>nests</em>. The one below stays alive and is
 *       revealed by back. Used for dialogs-as-screens and for menu drill-down.</li>
 *   <li>{@link #back} - leave the current screen the way <em>it</em> asked to be left
 *       ({@link Screen#backTarget()}).</li>
 * </ul>
 *
 * <p>The stack depth is <strong>not</strong> a navigation depth: the level list sits three
 * deep when opened from the title screen and one deep when the client installs it after a
 * level. A screen therefore states its own destination instead of reconstructing "where did
 * I come from" from the size of this stack - that is what {@link Navigation} is for, and why
 * {@link #back} is driven by the screen rather than by a depth test at each call site.
 *
 * <p>{@link #replace} and {@link #back} both drop screens, and a dropped screen may be
 * holding resources it acquired in {@link Screen#init()} (animation playbacks, preview
 * entities). Every screen that leaves the stack for any reason gets
 * {@link Screen#onRemoved()} exactly once - including the ones a root replacement discards
 * wholesale, which is the path that used to leak silently.
 */
public final class ScreenStack {
    private static final Logger LOGGER = LoggerFactory.getLogger("PVZCE/Client");

    private final PvzceClient client;
    private final Deque<Screen> screens = new ArrayDeque<>();

    public ScreenStack(PvzceClient client) {
        this.client = client;
    }

    // ------------------------------------------------------------------
    // Reading
    // ------------------------------------------------------------------

    /** The screen on top, or {@code null} before one is installed. */
    public Screen current() {
        return screens.peek();
    }

    /** How many full-window screens are on the stack. Overlays are not counted. */
    public int depth() {
        return screens.size();
    }

    // ------------------------------------------------------------------
    // Navigation
    // ------------------------------------------------------------------

    /** Installs {@code screen} as the new root, discarding and releasing what was there. */
    public void replace(Screen screen) {
        requireScreen(screen);
        bringDown();
        screens.push(screen);
    }

    /** Puts {@code screen} on top; the screen underneath stays alive. */
    public void push(Screen screen) {
        requireScreen(screen);
        screens.push(screen);
    }

    /**
     * Leaves the current screen the way {@link Screen#backTarget()} says.
     *
     * <p>A {@link Navigation.Pop} with nothing underneath is a programming error rather
     * than a silent no-op: popping the last screen would leave the client with nothing to
     * render, so it is reported and the screen stays up. Every root installed by this class
     * names its own destination, so this branch only fires on a screen that was pushed onto
     * an empty stack.
     */
    public void back() {
        Screen top = screens.peek();
        if (top == null) {
            return;
        }
        Navigation navigation = top.backTarget();
        if (navigation instanceof Navigation.ReplaceRoot replaceRoot) {
            replace(replaceRoot.factory().create(client));
            return;
        }
        if (screens.size() <= 1) {
            LOGGER.error("{} asked to pop but is the only screen on the stack; staying put",
                    top.getClass().getSimpleName());
            return;
        }
        Screen removed = screens.pop();
        removed.onRemoved();
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    /**
     * Rebuilds every screen on the stack for a new window size.
     *
     * <p>Only the screens: the overlay is the client's to resize, because it is not part of
     * this stack and this class deliberately knows nothing about it.
     */
    public void resizeAll() {
        for (Screen screen : screens) {
            screen.onResize();
        }
    }

    /**
     * Takes the stack apart, telling each screen it is leaving.
     *
     * <p>Collects first and notifies afterwards: {@code onRemoved} is allowed to look at
     * {@link #current()}, and popping as we go would make that answer depend on the order
     * the screens happened to be in.
     */
    private void bringDown() {
        if (screens.isEmpty()) {
            return;
        }
        List<Screen> leaving = List.copyOf(screens);
        screens.clear();
        for (Screen screen : leaving) {
            screen.onRemoved();
        }
    }

    private static void requireScreen(Screen screen) {
        if (screen == null) {
            throw new NullPointerException("screen");
        }
    }
}
