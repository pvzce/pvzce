package com.pvzce.api.content;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.util.Identifier;

import java.util.List;
import java.util.Locale;

/**
 * One spoken line of a level's opening dialogue.
 *
 * <p>Six fields, one per thing the author decides: who speaks, with which portrait, what
 * they say, which voice clip (if any) plays, which side of the screen they stand on, and
 * what the portrait does while the line is spoken. The character is a reference -
 * everything shared between lines lives in {@link DialogueCharacterDef}.
 *
 * <p>Authored as:
 * <pre>{@code
 * { "character": "pvzce:pea_chan", "portrait": "welcome",
 *   "text": "欢迎来到植物和僵尸的世界", "voice": "", "side": "left" }
 * { "character": "pvzce:pea_chan", "portrait": "panic", "text": "诶诶诶！",
 *   "side": "left", "animation": { "type": "shake" } }
 * }</pre>
 *
 * <p>{@code text} may be empty, which is a line with no words: the portrait is shown on its
 * own and a click moves on. The original stages its silent beats that way, and a blank line
 * that still drew an empty bubble read as a missing translation.
 *
 * <h2>More than one character on screen</h2>
 *
 * <p>{@code slots} is who is standing there <em>while this line is spoken</em>, as
 * {@code [{"slot": "right", "character": "pvzce:pea_chan"}]}. Without it the stage is just the
 * speaker - what every conversation written before two characters shared a screen is - and a
 * written one changes the stage: someone named for the first time walks on from their own side,
 * someone who was there and is gone walks off, and the speaker comes to the front. That is the
 * one thing the old shape could not say: <em>who else is here</em>, and <em>when they arrived</em>.
 *
 * <h2>Lines the player answers</h2>
 *
 * <p>{@code choices} turns the line into a gate: it is spoken like any other, and instead of a
 * click moving on, the bubble grows the authored buttons and the conversation waits. Picking one
 * makes the <em>next</em> line the player's own answer - which is why that line is written with
 * {@code speaker_name} and no character.
 *
 * <p>{@code speaker_name} is the name shown over the bubble for a speaker who is not a character
 * in the registry: the player. It takes the {@code ${user_name}} placeholder exactly like a line's
 * text does, so a level can put the name the player picked at the title screen on their own line.
 *
 * @param character   who speaks, or {@code null} for a line with no character behind it
 * @param speakerName the name to show when {@link #character} is null and there is one
 * @param slots       who else is on stage while this line is spoken; empty means "just the speaker"
 * @param choices     the answers offered instead of "click to continue"; empty means none
 */
public record DialogueLine(Identifier character, String portrait, String text, String voice, Side side,
                           DialogueAnimation animation, List<DialogueSlotEntry> slots,
                           List<DialogueChoice> choices, String speakerName) {
    /** A line with no animation: what every line written before animations existed is. */
    public DialogueLine(Identifier character, String portrait, String text, String voice, Side side) {
        this(character, portrait, text, voice, side, DialogueAnimation.NONE, List.of(), List.of(), "");
    }

    /** A line with an animation and no stage, choices or player-speaker. */
    public DialogueLine(Identifier character, String portrait, String text, String voice, Side side,
                        DialogueAnimation animation) {
        this(character, portrait, text, voice, side, animation, List.of(), List.of(), "");
    }

    /**
     * One entry of {@link #slots()}: a character and the half of the window they stand in.
     *
     * <p>An entry is a character id and a slot name, and nothing else: the portrait is the line's
     * own business (only the speaker's is drawn), so a character who is only standing there needs
     * no look of their own.
     */
    public record DialogueSlotEntry(Identifier character, DialogueSlot slot) {
        public static final Codec<DialogueSlotEntry> CODEC = RecordCodecBuilder.create(i -> i.group(
                Identifier.CODEC.fieldOf("character").forGetter(DialogueSlotEntry::character),
                DialogueSlot.CODEC.optionalFieldOf("slot", DialogueSlot.LEFT).forGetter(DialogueSlotEntry::slot)
        ).apply(i, DialogueSlotEntry::new));

        public DialogueSlotEntry {
            slot = slot == null ? DialogueSlot.LEFT : slot;
        }
    }

    /**
     * Which side of the screen the speaker stands on.
     *
     * <p>{@link #CENTER} puts the portrait in the middle of the window with the bubble
     * across its lower half, which is how a line that is about the character rather than
     * about the conversation is staged. {@link #UNKNOWN} exists so a misspelt value is a
     * level-validation report rather than a codec failure that takes the whole level down:
     * a typo decodes into UNKNOWN (drawn like {@link #LEFT}) and {@code LevelValidator}
     * names the line. This follows {@code LevelRewards.type}, which is also a string
     * discriminant precisely because the DFU codec cannot see a misspelt one.
     */
    public enum Side {
        LEFT,
        CENTER,
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
                case "center", "centre", "middle" -> CENTER;
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

        /** True for the middle of the window; the bubble follows the portrait there. */
        public boolean isCenter() {
            return this == CENTER;
        }
    }

    public static final Codec<DialogueLine> CODEC = RecordCodecBuilder.create(i -> i.group(
            Identifier.CODEC.optionalFieldOf("character").forGetter(line -> java.util.Optional.ofNullable(line.character())),
            Codec.STRING.optionalFieldOf("portrait", "").forGetter(DialogueLine::portrait),
            Codec.STRING.optionalFieldOf("text", "").forGetter(DialogueLine::text),
            Codec.STRING.optionalFieldOf("voice", "").forGetter(DialogueLine::voice),
            Side.CODEC.optionalFieldOf("side", Side.LEFT).forGetter(DialogueLine::side),
            DialogueAnimation.CODEC.optionalFieldOf("animation", DialogueAnimation.NONE)
                    .forGetter(DialogueLine::animation),
            DialogueSlotEntry.CODEC.listOf().optionalFieldOf("slots", List.of()).forGetter(DialogueLine::slots),
            DialogueChoice.CODEC.listOf().optionalFieldOf("choices", List.of()).forGetter(DialogueLine::choices),
            Codec.STRING.optionalFieldOf("speaker_name", "").forGetter(DialogueLine::speakerName)
    ).apply(i, (character, portrait, text, voice, side, animation, slots, choices, speakerName) ->
            new DialogueLine(character.orElse(null), portrait, text, voice, side, animation, slots, choices,
                    speakerName)));

    public DialogueLine {
        portrait = portrait == null ? "" : portrait;
        text = text == null ? "" : text;
        voice = voice == null ? "" : voice;
        side = side == null ? Side.LEFT : side;
        animation = animation == null ? DialogueAnimation.NONE : animation;
        slots = slots == null ? List.of() : List.copyOf(slots);
        choices = choices == null ? List.of() : List.copyOf(choices);
        speakerName = speakerName == null ? "" : speakerName;
    }

    /** True when this line is answered with buttons rather than a click. */
    public boolean hasChoices() {
        return !choices.isEmpty();
    }
}
