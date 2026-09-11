package com.pvzce.client.gui.layout;

/**
 * Button-row height fitting shared by the menu screens.
 *
 * <p>Row/column coordinate builders used to live here too; they had no callers
 * while nine screens hand-rolled their own rows with five different gap values,
 * so they were removed rather than left as a second, unused convention.
 */
public final class GuiLayout {
    private GuiLayout() {
    }

    /**
     * Fits {@code count} buttons into the logical GUI height. Prefers
     * {@code baseHeight} (callers already pass the ~40% enlarged size) but
     * shrinks it when the available area is smaller, so even 4x UI never
     * clips buttons off screen.
     */
    public static int fitHeight(int guiHeight, int baseHeight, int count, int reservedTop, int reservedBottom) {
        int gap = gapFor(baseHeight);
        int available = Math.max(24, guiHeight - reservedTop - reservedBottom);
        int fitted = (available - gap * (count - 1)) / count;
        return Math.max(18, Math.min(baseHeight, fitted));
    }

    /** Spacing grows with button height (40%-height buttons need breathing room). */
    public static int gapFor(int height) {
        return Math.max(6, height / 4);
    }
}
