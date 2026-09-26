package com.pvzce.api.content;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * One answer the player may give to a line, offered as a button.
 *
 * <p>A conversation is normally read: a click moves on. A <em>choice</em> is the one place where
 * the click has to mean something before it advances - the level asks a question and waits for an
 * answer, and the answer is part of the script. That is why choices are a gate rather than extra
 * dialogue: the line below them exists, is written, and is not reached until the player picks it.
 *
 * <p>Authored as:
 * <pre>{@code
 * { "character": "pvzce:entang", "portrait": "confused", "text": "嗯，你谁？",
 *   "choices": [ { "text": "莉安" }, { "text": "路过的人" } ] }
 * }</pre>
 *
 * <p>{@code text} is both what the button says and what the player's own line says after the
 * button is pressed - one string for one answer, so the two cannot drift apart. The reply line
 * itself is written in the script as an ordinary line with {@code speaker_name} and no character
 * (the player has no portrait): <em>what</em> is said is the data's, and <em>when</em> it appears
 * is the gate's.
 *
 * <p>The voice clip is optional, like a line's: an answer may have a sound of its own.
 *
 * @param text  the answer, shown on the button and spoken as the player's line
 * @param voice the sound clip played when this answer is picked, or empty
 */
public record DialogueChoice(String text, String voice) {
    /** An answer with no sound of its own. */
    public DialogueChoice(String text) {
        this(text, "");
    }

    public static final Codec<DialogueChoice> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.optionalFieldOf("text", "").forGetter(DialogueChoice::text),
            Codec.STRING.optionalFieldOf("voice", "").forGetter(DialogueChoice::voice)
    ).apply(i, DialogueChoice::new));

    public DialogueChoice {
        text = text == null ? "" : text;
        voice = voice == null ? "" : voice;
    }
}
