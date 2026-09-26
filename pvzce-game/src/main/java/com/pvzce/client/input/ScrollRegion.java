package com.pvzce.client.input;

/**
 * A place on a screen that a swipe scrolls, and how far a finger must travel for one step.
 *
 * <p>This is the one thing a screen has to declare for touch: <em>where</em> a press becomes a
 * scroll instead of a click. Without it the gesture layer cannot tell "the player is about to
 * press a row" from "the player is starting a swipe", and a swipe would fire a click at its own
 * start - in the shop that press <em>buys</em>, in the player picker it switches player, on the
 * card bar it selects a card.
 *
 * <p>The region a screen declares must be the same rectangle its wheel handler already tests,
 * not a second copy of it: "能滚" and "滑得动" have to be the same answer, the way a card's
 * clickable rectangle and its drawn rectangle are the same rectangle.
 *
 * <p>{@link #step} is the region's own row or card pitch, so one step of finger travel moves one
 * row - a fixed step would make a list of 16px rows scroll at half speed and a bar of 44px cards
 * at double.
 *
 * @param axis  which way the finger travels to scroll this region
 * @param step  GUI pixels of travel per scroll step
 * @param swipe what a swipe means here; see {@link Swipe}
 */
public record ScrollRegion(Axis axis, double step, Swipe swipe) {
    /** Which way a finger moves to scroll a region. */
    public enum Axis {
        VERTICAL,
        HORIZONTAL
    }

    /**
     * What the finger's direction means, which is a fact about the region's own layout.
     *
     * <p>The two differ only on the vertical axis, and both exist because this project's
     * vertical lists do not run the same way: {@code AbstractSelectionList} draws index 0 at the
     * <em>bottom</em> row and grows upward, while the seed chooser's grid and the level grid start
     * at the top and grow downward. A finger moving up therefore means "wheel up" in the first and
     * "wheel down" in the others; one global rule would make one of them feel inverted.
     */
    public enum Swipe {
        /** The content drags along with the finger, like every touch UI: finger up moves content up. */
        DRAGS_CONTENT,
        /**
         * The swipe does exactly what this region's wheel does (finger up = wheel up).
         *
         * <p>For a region whose content already runs bottom-up the wheel <em>is</em> the natural
         * direction, so this and {@link #DRAGS_CONTENT} coincide there; for a selection stepper
         * (the almanac's next/previous entry) there is no content to drag, and matching the wheel
         * is the only predictable answer.
         */
        MIRRORS_WHEEL
    }

    /** One step of travel for a region that has no pitch of its own, in GUI pixels. */
    public static final double DEFAULT_STEP = 32.0;

    public ScrollRegion {
        if (step <= 0 || !Double.isFinite(step)) {
            step = DEFAULT_STEP;
        }
    }

    /** A region whose content runs the way the screen reads: down, or left to right. */
    public static ScrollRegion dragsContent(Axis axis, double step) {
        return new ScrollRegion(axis, step, Swipe.DRAGS_CONTENT);
    }

    /** A region whose swipe is defined as "the same as its wheel". */
    public static ScrollRegion mirrorsWheel(Axis axis, double step) {
        return new ScrollRegion(axis, step, Swipe.MIRRORS_WHEEL);
    }

    /** The component of the finger's travel this region scrolls on. */
    public double travelOnAxis(double fromX, double fromY, double toX, double toY) {
        return axis == Axis.VERTICAL ? toY - fromY : toX - fromX;
    }

    /**
     * The wheel amount for one step taken in the direction of {@code travel}.
     *
     * <p>Consumers ignore the magnitude (five of the six only read the sign and step by one row,
     * card or page), so the gesture layer emits one unit event per crossed step and the sign is
     * the whole contract. Horizontal regions need no distinction: dragging the content to the
     * right and turning the wheel up both mean "show what is to the left".
     */
    public double amountForTravel(double travel) {
        double forward = Math.signum(travel);
        if (axis == Axis.HORIZONTAL) {
            return forward;
        }
        return swipe == Swipe.DRAGS_CONTENT ? -forward : forward;
    }
}
