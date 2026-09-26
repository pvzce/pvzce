package com.pvzce.client.input;

import org.lwjgl.glfw.GLFW;

/**
 * What a finger did: a tap is a click, a swipe is a scroll - and a swipe over the board is still
 * an ordinary drag.
 *
 * <p><b>Why this has to exist at all.</b> Touch arrives as mouse input (Windows promotes a single
 * finger to mouse messages, X11/XInput2 emulates the pointer for the first touch), so a tap and a
 * swipe already reach the game without any native code. What does <em>not</em> work is turning a
 * swipe into the wheel: clicks are delivered on <em>press</em> ({@code PvzceWindow} queues
 * {@code GLFW_PRESS} only) and nothing in this project has ever had a movement threshold, so
 * synthesising a scroll from a drag would also fire a click where the finger went down. That click
 * is destructive in exactly the places a player needs to swipe - the shop buys the row under the
 * finger, the player picker switches player, the card bar selects a card.
 *
 * <p>So the gesture owns the press, but <b>only inside a declared scroll region</b>
 * ({@link ScrollRegion}): there it holds the click back until the finger comes up and delivers it
 * only if the finger never really moved, and it converts the travel into unit scroll events.
 * Everywhere else it does not touch anything - the press is delivered immediately and each frame's
 * drag is forwarded, so sweeping over sun, dragging a card from the bar onto a cell, and the
 * 500 ms press-and-hold that launches a mower all behave exactly as they do for a mouse.
 *
 * <p>Unit events, not accumulated amounts: five of the six wheel consumers read only the sign of
 * {@code amount} and step by one row/card/page, so one event per crossed step is the only shape
 * that means the same thing to all of them.
 */
public final class PointerGesture {
    /**
     * How far a finger may travel and still count as a tap, in GUI pixels.
     *
     * <p>Measured as total distance rather than along the region's axis: a finger dragged sideways
     * across a list is a drag, not a tap, even though it scrolled nothing. The value is small on
     * purpose - at 3x UI this is 24 window pixels - because a finger that is leaving a row to
     * scroll has moved further than this within a frame or two, while a deliberate tap almost
     * never moves at all.
     */
    public static final double TAP_SLOP = 8.0;

    /** Where a press at a point would scroll, or {@code null} for "this press is a normal click". */
    public interface Regions {
        ScrollRegion scrollRegionAt(double guiX, double guiY);
    }

    /** Delivers the tap the gesture was holding back. */
    public interface ClickSink {
        void click(double guiX, double guiY, int button);
    }

    /**
     * Delivers one scroll step, in the wheel's own vocabulary.
     *
     * <p>Carries the finger's position because the consumers hit-test it ({@code SeedCardBar.scroll}
     * refuses a point outside the row, the console picks its log or its suggestions) - and because
     * taking it from the live cursor instead would be a hidden coupling that only holds while the
     * gesture is driven by the frame loop. A smoke hook that drives the gesture directly is the proof:
     * with the position passed along, the scroll lands where the finger is, not where the pointer
     * happens to be parked.
     */
    public interface ScrollSink {
        void scroll(double guiX, double guiY, double amount);
    }

    private final Regions regions;
    private final ClickSink clicks;
    private final ScrollSink scrolls;
    private final boolean enabled;

    private boolean active;
    private int button;
    private double originX;
    private double originY;
    private ScrollRegion region;
    /** Travel already turned into scroll events, so a slow swipe still steps once it adds up. */
    private double emitted;
    /** True once the finger has moved far enough that coming back does not make it a tap again. */
    private boolean moved;

    public PointerGesture(Regions regions, ClickSink clicks, ScrollSink scrolls, boolean enabled) {
        this.regions = regions;
        this.clicks = clicks;
        this.scrolls = scrolls;
        this.enabled = enabled;
    }

    /**
     * A button going down.
     *
     * @param stillDown whether the button is still down now, as the frame loop polls it. A press that
     *                  is already up again - both messages arrived inside one {@code glfwPollEvents}
     *                  - is delivered as a tap right here, because the release branch that would
     *                  otherwise deliver it never runs: the polled state simply reads "up".
     * @return true when the gesture took the press over and the caller must <em>not</em> deliver the
     *         click itself; false for every press that stays an ordinary click (including all of them
     *         while touch support is switched off)
     */
    public boolean press(int button, double guiX, double guiY, boolean stillDown) {
        if (!enabled || button != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return false;
        }
        ScrollRegion target = regions.scrollRegionAt(guiX, guiY);
        if (target == null) {
            return false;
        }
        if (!stillDown) {
            clicks.click(guiX, guiY, button);
            return true;
        }
        active = true;
        this.button = button;
        originX = guiX;
        originY = guiY;
        region = target;
        emitted = 0;
        moved = false;
        return true;
    }

    /**
     * The finger moving with the button down; called once per frame.
     *
     * @return true when this gesture absorbed the drag (a scroll region is not a drag target);
     *         false when the caller should forward it to the screen as usual
     */
    public boolean dragged(double guiX, double guiY) {
        if (!active) {
            return false;
        }
        double dx = guiX - originX;
        double dy = guiY - originY;
        if (Math.hypot(dx, dy) >= TAP_SLOP) {
            moved = true;
        }
        double travel = region.travelOnAxis(originX, originY, guiX, guiY);
        double step = region.step();
        // One event per crossed step, in both directions: a swipe that reverses must undo its own
        // steps rather than emit a single accumulated amount the consumers would read as "one row".
        while (travel - emitted >= step) {
            emitted += step;
            scrolls.scroll(guiX, guiY, region.amountForTravel(1));
        }
        while (emitted - travel >= step) {
            emitted -= step;
            scrolls.scroll(guiX, guiY, region.amountForTravel(-1));
        }
        return true;
    }

    /** For diagnostics: true while a press is being held back for a possible scroll. */
    public boolean holding() {
        return active;
    }

    /** For diagnostics: true once this gesture's travel has passed the tap threshold. */
    public boolean travelled() {
        return moved;
    }

    /**
     * The button coming back up.
     *
     * <p>A gesture that never moved is delivered as a click <em>at the point it started</em> - the
     * finger may have drifted a few pixels, and what the player aimed at is where they put it down.
     *
     * @return true when the gesture absorbed the release, so the caller must not send a release to
     *         the screen: the screen never saw the press either
     */
    public boolean released() {
        if (!active) {
            return false;
        }
        active = false;
        region = null;
        if (!moved) {
            clicks.click(originX, originY, button);
        }
        return true;
    }
}
