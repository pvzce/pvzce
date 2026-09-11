package com.pvzce.client.gui;

import com.pvzce.api.util.Identifier;

import java.util.Locale;

/**
 * Formatting and parsing helpers shared by the GUI screens.
 *
 * <p>These were private copies in the wave editor, the music editor, the level
 * editor, the seed chooser and the in-game HUD - five copies of {@code shortId},
 * two of {@code parseFloat} that disagreed about {@code null} and about which
 * exception to catch, and two {@code formatFloat} variants in the same class.
 */
public final class GuiText {
    /** The path part of an id, for narrow list rows; empty for a null id. */
    public static String shortId(String id) {
        if (id == null) {
            return "";
        }
        int colon = id.indexOf(':');
        return colon >= 0 ? id.substring(colon + 1) : id;
    }

    public static String shortId(Identifier id) {
        return id == null ? "" : id.path();
    }

    /** One decimal place, no exponent - what every editor field shows. */
    public static String formatFloat(float value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    public static String formatPercent(float ratio) {
        return String.format(Locale.ROOT, "%.0f%%", ratio * 100F);
    }

    public static String formatSeconds(float ticks) {
        return formatFloat(ticks / 60F);
    }

    /**
     * Parses a user-entered float, returning {@code fallback} for anything
     * unparseable. The two editor copies differed in that one threw on a null
     * string and the other caught a different exception type; both are handled
     * here so an empty field can never crash a dialog.
     */
    public static float parseFloat(String text, float fallback) {
        if (text == null || text.isBlank()) {
            return fallback;
        }
        try {
            return Float.parseFloat(text.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public static int parseInt(String text, int fallback) {
        if (text == null || text.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** Parses an int and clamps it into range. */
    public static int parseInt(String text, int fallback, int min, int max) {
        return Math.max(min, Math.min(max, parseInt(text, fallback)));
    }

    /** Parses a float and clamps it into range. */
    public static float parseFloat(String text, float fallback, float min, float max) {
        return Math.max(min, Math.min(max, parseFloat(text, fallback)));
    }

    private GuiText() {
    }
}
