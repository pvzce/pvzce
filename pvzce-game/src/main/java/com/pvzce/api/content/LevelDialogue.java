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
 * of a breaking reshape of every level that already has one.
 */
public record LevelDialogue(List<DialogueLine> lines) {
    public static final LevelDialogue EMPTY = new LevelDialogue(List.of());

    public static final Codec<LevelDialogue> CODEC = RecordCodecBuilder.create(i -> i.group(
            DialogueLine.CODEC.listOf().optionalFieldOf("lines", List.of()).forGetter(LevelDialogue::lines)
    ).apply(i, LevelDialogue::new));

    public LevelDialogue {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    public boolean isEmpty() {
        return lines.isEmpty();
    }
}
