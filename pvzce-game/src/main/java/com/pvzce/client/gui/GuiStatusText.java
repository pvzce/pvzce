package com.pvzce.client.gui;

/**
 * The one place a level's save status is turned into text.
 *
 * <p>The level select and level setup screens each had their own switch over the
 * same {@code "in_progress"} / {@code "completed"} literals and had already
 * drifted into two different wordings for the same state.
 */
public final class GuiStatusText {
    public static final String IN_PROGRESS = "in_progress";
    public static final String COMPLETED = "completed";

    /** Short form for a level card. */
    public static String label(String status) {
        return switch (status == null ? "" : status) {
            case IN_PROGRESS -> "进行中";
            case COMPLETED -> "已通关";
            default -> "";
        };
    }

    /** Long form for a detail panel. */
    public static String detail(String status) {
        return switch (status == null ? "" : status) {
            case IN_PROGRESS -> "已有进行中的存档";
            case COMPLETED -> "已通关";
            default -> "";
        };
    }

    private GuiStatusText() {
    }
}
