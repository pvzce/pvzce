package com.pvzce.api.content;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.List;

/**
 * The conversation a level opens with, before the player does anything.
 *
 * <p>A level may leave this out entirely; a level that has one plays it once per fresh
 * run - continuing a save does not replay it, replaying a finished level does. See
 * {@code DialogueOverlay} for how it is presented.
 *
 * <p>Authored as:
 * <pre>{@code
 * "dialogue": { "lines": [ { "character": "...", "portrait": "...", "text": "..." } ] }
 * }</pre>
 *
 * <p>The lines live behind an object rather than directly under {@code "dialogue"} so that
 * later additions (a trigger, a one-shot flag) can be fields beside {@code lines} instead
 * of a breaking reshape of every level that already has one - {@code enter} and {@code exit}
 * are the first two of those.
 *
 * <p>{@code enter} is how the first portrait comes on screen and {@code exit} how it leaves
 * when the conversation is over; both default to {@code slide}, and {@code "none"} turns
 * them into a cut. The middle of a conversation is never staged: a line that changes speaker
 * changes portrait, which is what makes the two effects mean "this conversation began" and
 * "this conversation ended".
 */
public record LevelDialogue(List<DialogueLine> lines, DialogueEffect enter, DialogueEffect exit) {
    public static final LevelDialogue EMPTY =
            new LevelDialogue(List.of(), DialogueEffect.SLIDE, DialogueEffect.SLIDE);

    /** A conversation that only says its lines; both effects are the default slide. */
    public LevelDialogue(List<DialogueLine> lines) {
        this(lines, DialogueEffect.SLIDE, DialogueEffect.SLIDE);
    }

    public static final Codec<LevelDialogue> CODEC = RecordCodecBuilder.create(i -> i.group(
            DialogueLine.CODEC.listOf().optionalFieldOf("lines", List.of()).forGetter(LevelDialogue::lines),
            DialogueEffect.CODEC.optionalFieldOf("enter", DialogueEffect.SLIDE).forGetter(LevelDialogue::enter),
            DialogueEffect.CODEC.optionalFieldOf("exit", DialogueEffect.SLIDE).forGetter(LevelDialogue::exit)
    ).apply(i, LevelDialogue::new));

    public LevelDialogue {
        lines = lines == null ? List.of() : List.copyOf(lines);
        enter = enter == null ? DialogueEffect.SLIDE : enter;
        exit = exit == null ? DialogueEffect.SLIDE : exit;
    }

    public boolean isEmpty() {
        return lines.isEmpty();
    }
}
