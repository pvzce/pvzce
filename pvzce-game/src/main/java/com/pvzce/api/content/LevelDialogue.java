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
public record LevelDialogue(List<DialogueLine> lines, DialogueEffect enter, DialogueEffect exit,
                            List<Timed> timed) {
    public static final LevelDialogue EMPTY =
            new LevelDialogue(List.of(), DialogueEffect.SLIDE, DialogueEffect.SLIDE, List.of());

    /** A conversation that only says its lines; both effects are the default slide. */
    public LevelDialogue(List<DialogueLine> lines) {
        this(lines, DialogueEffect.SLIDE, DialogueEffect.SLIDE, List.of());
    }

    /** The same, with no lines spoken on a clock - every level but the tutorial. */
    public LevelDialogue(List<DialogueLine> lines, DialogueEffect enter, DialogueEffect exit) {
        this(lines, enter, exit, List.of());
    }

    public static final Codec<LevelDialogue> CODEC = RecordCodecBuilder.create(i -> i.group(
            DialogueLine.CODEC.listOf().optionalFieldOf("lines", List.of()).forGetter(LevelDialogue::lines),
            DialogueEffect.CODEC.optionalFieldOf("enter", DialogueEffect.SLIDE).forGetter(LevelDialogue::enter),
            DialogueEffect.CODEC.optionalFieldOf("exit", DialogueEffect.SLIDE).forGetter(LevelDialogue::exit),
            Timed.CODEC.listOf().optionalFieldOf("timed", List.of()).forGetter(LevelDialogue::timed)
    ).apply(i, LevelDialogue::new));

    public LevelDialogue {
        lines = lines == null ? List.of() : List.copyOf(lines);
        enter = enter == null ? DialogueEffect.SLIDE : enter;
        exit = exit == null ? DialogueEffect.SLIDE : exit;
        timed = timed == null ? List.of() : List.copyOf(timed);
    }

    /** True when there is nothing to say at all, in either half. */
    public boolean isEmpty() {
        return lines.isEmpty() && timed.isEmpty();
    }

    /** True when only the opening half is empty. */
    public boolean hasOpening() {
        return !lines.isEmpty();
    }

    /**
     * A line the level says while it is being played, at a tick of its own clock.
     *
     * <p>The opening conversation is a conversation: it plays before the player does anything, and
     * it is over. A tutorial has to talk <em>during</em> the level - "look at the card bar, it just
     * changed" is only true at the moment it changes - so the script needs moments as well as words.
     * The tick is the level's own counter, the same one a mutation's schedule is written against,
     * so a line can be aimed at the same event the mutation fires on.
     *
     * <p>Played by the client, one at a time, pausing the level while a line is up: the point of a
     * tutorial line is that it is read, and a player who is being shot at will not read it.
     *
     * @param atTick the tick of the level's clock, counted from the run's start
     * @param line   what is said
     */
    public record Timed(int atTick, DialogueLine line) {
        public static final Codec<Timed> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.INT.fieldOf("at_tick").forGetter(Timed::atTick),
                DialogueLine.CODEC.fieldOf("line").forGetter(Timed::line)
        ).apply(i, Timed::new));

        public Timed {
            atTick = Math.max(0, atTick);
        }
    }
}
