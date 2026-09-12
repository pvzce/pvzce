package com.pvzce.client.gui;

import com.pvzce.common.network.packet.LevelListS2C;

/**
 * The one place a level's save status is turned into text.
 *
 * <p>The level select and level setup screens each had their own switch over the
 * same {@code "in_progress"} / {@code "completed"} literals and had already
 * drifted into two different wordings for the same state.
 *
 * <p>The strings themselves come from the packet that carries them
 * ({@link LevelListS2C.LevelInfo}), so "does this level have a run to resume" and "what does
 * the card say" cannot disagree.
 */
public final class GuiStatusText {
    public static final String IN_PROGRESS = LevelListS2C.LevelInfo.IN_PROGRESS;
    public static final String COMPLETED = LevelListS2C.LevelInfo.COMPLETED;

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
