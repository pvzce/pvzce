package com.pvzce.api.content;

import com.mojang.serialization.Codec;

import java.util.Locale;

/**
 * Which half of the window a character stands in while a line is spoken.
 *
 * <p>A line's own {@link DialogueLine.Side} says where the <em>speaker</em> is. A slot says where
 * <em>someone on stage</em> is, and the two are different questions the moment two characters
 * share the screen: the speaker is one of them, and the other has to be somewhere. Slots are
 * therefore named after the half of the window rather than after a side of the conversation, and
 * the author writes them as a pair - "left: pvzce:entang, right: pvzce:pea_chan".
 *
 * <p>{@link #UNKNOWN} follows {@link DialogueLine.Side#UNKNOWN}: a misspelt value decodes into it
 * rather than failing the level, it is ignored when the stage is built (the character keeps the
 * slot they had, or the one their side asks for), and {@code LevelValidator} names the line that
 * wrote it. A typo must not take a whole conversation down.
 */
public enum DialogueSlot {
    LEFT,
    RIGHT,
    UNKNOWN;

    public static final Codec<DialogueSlot> CODEC = Codec.STRING.xmap(DialogueSlot::parse, DialogueSlot::id);

    /** Never fails; an unrecognised value is {@link #UNKNOWN}. */
    public static DialogueSlot parse(String raw) {
        if (raw == null) {
            return UNKNOWN;
        }
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "left" -> LEFT;
            case "right" -> RIGHT;
            default -> UNKNOWN;
        };
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** True only for {@link #LEFT}; UNKNOWN is not a side and is skipped by the stage. */
    public boolean isLeft() {
        return this == LEFT;
    }
}
