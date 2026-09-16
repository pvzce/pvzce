package com.pvzce.api.content;

import com.mojang.serialization.Codec;

import java.util.Locale;

/**
 * How a conversation's portrait comes on and goes off screen.
 *
 * <p>Two values today - {@link #SLIDE} (the default) and {@link #NONE} - because that is the
 * whole choice an author has: characters either arrive and leave, or they are simply there.
 * The enum exists rather than a boolean so a third staging (a fade, a bounce) is a value
 * beside these instead of a second field next to {@code slide_in}.
 *
 * <p>{@link #UNKNOWN} follows {@link DialogueLine.Side#UNKNOWN}: a misspelt value decodes
 * into it rather than failing the level, it behaves like the default so a typo cannot make a
 * conversation look broken, and {@code LevelValidator} names the line that wrote it.
 */
public enum DialogueEffect {
    SLIDE,
    NONE,
    UNKNOWN;

    public static final Codec<DialogueEffect> CODEC = Codec.STRING.xmap(DialogueEffect::parse, DialogueEffect::id);

    /** Never fails; an unrecognised value is {@link #UNKNOWN}. */
    public static DialogueEffect parse(String raw) {
        if (raw == null) {
            return UNKNOWN;
        }
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "slide" -> SLIDE;
            case "none" -> NONE;
            default -> UNKNOWN;
        };
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * True when the portrait slides on or off.
     *
     * <p>{@link #UNKNOWN} answers true: a typo should leave the conversation looking like
     * every other one, with the mistake reported on the log rather than staged on screen.
     */
    public boolean slides() {
        return this != NONE;
    }
}
