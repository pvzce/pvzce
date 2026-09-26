package com.pvzce.client.gui;

import com.pvzce.client.gui.WindowTitleBar.Button;
import com.pvzce.client.gui.WindowTitleBar.Layout;

/**
 * The gesture rules of a title bar: what a press, a drag-through and a release mean.
 *
 * <p>Split out of {@link WindowTitleBar} because this half needs no window, no GL and no client -
 * it is a state machine over GUI coordinates and a clock, so it can be asserted directly. That
 * matters more here than for most widgets: "the close button needs a release" and "two clicks on the
 * empty strip maximize" are invisible until someone tries them by hand, and both are exactly the
 * kind of rule a real desktop has and a hand-rolled bar forgets.
 *
 * <p>The rules, in one place:
 * <ul>
 *   <li>a press on the bar is chrome and never reaches the game;</li>
 *   <li>a release fires a button only if it lands on the button the press started on - press close,
 *       slide off, release, and the window survives, as on every desktop;</li>
 *   <li>two presses on the empty strip within {@link #DOUBLE_CLICK_SECONDS} and within one bar
 *       height of each other are a double click, which maximizes (or restores) exactly as a desktop
 *       title bar does.</li>
 * </ul>
 *
 * <p>Mutable and single-threaded: one instance per bar, touched only from the client's frame loop.
 */
public final class TitleBarGesture {
    /**
     * How long two presses on the empty part of the bar may be apart and still count as a double
     * click, in seconds.
     *
     * <p>Not read from the desktop's own double-click setting: that lives in GTK/dconf and is not
     * reachable from here, so this is a value most desktops ship (400 ms is GNOME's, 500 ms is
     * Windows' default). The tighter one is the safer mistake - a slow double click simply does not
     * maximize, while a loose one turns two deliberate clicks into one.
     */
    public static final double DOUBLE_CLICK_SECONDS = 0.4D;

    /** What the gesture decided a release meant. */
    public enum Action {
        /** Nothing: the release was not on the bar, or not on the button the press started on. */
        NONE,
        MINIMIZE,
        MAXIMIZE,
        CLOSE,
        /** Two clicks on the empty strip: toggle maximization. */
        TOGGLE_MAXIMIZED
    }

    private Button hovered;
    private Button pressed;
    /** True from a press the bar owns until the release: the whole gesture is chrome. */
    private boolean armed;
    private double pressTime = Double.NEGATIVE_INFINITY;
    private double pressX;
    private double pressY;
    private boolean pressWasDoubleClick;

    /** Hover feedback for the button a point is over; call once a frame with the live pointer. */
    public void hover(Layout layout, double guiX, double guiY) {
        hovered = layout == null ? null : layout.buttonAt(guiX, guiY);
        if (layout == null) {
            pressed = null;
        }
    }

    /** Which button the pointer is over, for drawing. */
    public Button hovered() {
        return hovered;
    }

    /**
     * The button the current press went down on, or {@code null} - which is also the answer for a
     * press on the empty strip, and is not the same as "no press": see {@link #dragging()}.
     */
    public Button pressed() {
        return pressed;
    }

    /**
     * Whether a press the bar owns is still down, which is what makes the whole gesture chrome: a
     * sweep that started on the bar must not drag the game's cards, sun or mowers.
     *
     * <p>Tracked separately from {@link #pressed()} because the empty strip is a legitimate place to
     * press (that is where a title bar is dragged and double-clicked) and leaves no button behind.
     */
    public boolean dragging() {
        return armed;
    }

    /**
     * A press at a point.
     *
     * @return true when the bar owns the point and the game must not see the press
     */
    public boolean press(Layout layout, double guiX, double guiY, double now) {
        if (layout == null || !layout.contains(guiX, guiY)) {
            pressWasDoubleClick = false;
            return false;
        }
        pressWasDoubleClick = now - pressTime <= DOUBLE_CLICK_SECONDS
                && Math.abs(guiX - pressX) <= layout.height()
                && Math.abs(guiY - pressY) <= layout.height();
        pressTime = now;
        pressX = guiX;
        pressY = guiY;
        pressed = layout.buttonAt(guiX, guiY);
        armed = true;
        return true;
    }

    /**
     * The release that ends a press.
     *
     * @return what to do about it; {@link Action#NONE} still means "the bar owned this release" when
     *         the press was on the bar, which is why {@link #dragging()} is what callers use to
     *         decide whether the game should see it
     */
    public Action release(Layout layout, double guiX, double guiY) {
        Button released = layout == null ? null : layout.buttonAt(guiX, guiY);
        Button started = pressed;
        boolean wasDoubleClick = pressWasDoubleClick;
        pressed = null;
        pressWasDoubleClick = false;
        armed = false;
        if (started != null) {
            if (started == released) {
                return switch (started) {
                    case MINIMIZE -> Action.MINIMIZE;
                    case MAXIMIZE -> Action.MAXIMIZE;
                    case CLOSE -> Action.CLOSE;
                };
            }
            return Action.NONE;
        }
        // The press was on the empty strip; a second one there is a double click.
        return wasDoubleClick && released == null ? Action.TOGGLE_MAXIMIZED : Action.NONE;
    }
}
