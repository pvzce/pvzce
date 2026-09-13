package com.pvzce.api.content;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.util.Identifier;

import java.util.Locale;

/**
 * One spoken line of a level's opening dialogue.
 *
 * <p>Five fields, one per thing the author decides: who speaks, with which portrait, what
 * they say, which voice clip (if any) plays, and which side of the screen they stand on.
 * The character is a reference - everything shared between lines lives in
 * {@link DialogueCharacterDef}.
 *
 * <p>Authored as:
 * <pre>{@code
 * { "character": "pvzce:pea_chan", "portrait": "welcome",
 *   "text": "欢迎来到植物和僵尸的世界", "voice": "", "side": "left" }
 * }</pre>
 */
public record DialogueLine(Identifier character, String portrait, String text, String voice, Side side) {
    /**
     * Which side of the screen the speaker stands on.
     *
     * <p>{@link #UNKNOWN} exists so a misspelt value is a level-validation report rather
     * than a codec failure that takes the whole level down: a typo decodes into UNKNOWN
     * (drawn like {@link #LEFT}) and {@code LevelValidator} names the line. This follows
     * {@code LevelRewards.type}, which is also a string discriminant precisely because the
     * DFU codec cannot see a misspelt one.
     */
    public enum Side {
        LEFT,
        RIGHT,
        UNKNOWN;

        public static final Codec<Side> CODEC = Codec.STRING.xmap(Side::parse, Side::id);

        /** Never fails; an unrecognised value is {@link #UNKNOWN}. */
        public static Side parse(String raw) {
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

        /** True only for an explicit {@code right}; UNKNOWN falls back to the left side. */
        public boolean isRight() {
            return this == RIGHT;
        }
    }

    public static final Codec<DialogueLine> CODEC = RecordCodecBuilder.create(i -> i.group(
            Identifier.CODEC.fieldOf("character").forGetter(DialogueLine::character),
            Codec.STRING.optionalFieldOf("portrait", "").forGetter(DialogueLine::portrait),
            Codec.STRING.optionalFieldOf("text", "").forGetter(DialogueLine::text),
            Codec.STRING.optionalFieldOf("voice", "").forGetter(DialogueLine::voice),
            Side.CODEC.optionalFieldOf("side", Side.LEFT).forGetter(DialogueLine::side)
    ).apply(i, DialogueLine::new));

    public DialogueLine {
        portrait = portrait == null ? "" : portrait;
        text = text == null ? "" : text;
        voice = voice == null ? "" : voice;
        side = side == null ? Side.LEFT : side;
    }
}
