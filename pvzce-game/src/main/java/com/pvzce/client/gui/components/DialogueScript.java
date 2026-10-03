package com.pvzce.client.gui.components;

import com.pvzce.api.content.DialogueAnimation;
import com.pvzce.api.content.DialogueCharacterDef;
import com.pvzce.api.content.DialogueChoice;
import com.pvzce.api.content.DialogueLine;
import com.pvzce.api.content.DialogueSlot;
import com.pvzce.api.content.LevelDialogue;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.core.BuiltInRegistries;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A conversation as it is being played: which lines are behind us, who is standing on stage,
 * and what a click means right now.
 *
 * <p>Split out of {@link DialogueOverlay} on purpose, the same way {@link DialogueMotion} was:
 * the overlay owns textures, layout and input, and none of that can be asserted, while "who is
 * on stage after this click" is where the mistakes live - a character who never leaves, a line
 * reached before its question was answered, a stage rebuilt from scratch so someone who was
 * already there walks on again. Those are questions about a list and two maps, so they live in a
 * class a test can drive without a window.
 *
 * <h2>The stage</h2>
 *
 * <p>Two characters may share the screen, one in each half of the window. A line says who is there
 * with {@code slots}; the script compares that with the line before it and starts a slide for
 * whoever appeared and a leave for whoever is gone. A line that says nothing about the stage keeps
 * it exactly as it was - which is how a conversation written the old way (one character, no
 * {@code slots} at all) stages what it always did, and why a missing {@code slots} can never make
 * somebody walk off.
 *
 * <p>Each slide has its own clock rather than sharing the conversation's, because there are now
 * three kinds of them: the opening one, one per arrival or departure mid-conversation, and the
 * closing one. A slide that has finished is simply not in the map any more.
 *
 * <h2>Choices</h2>
 *
 * <p>A line with {@code choices} is a <em>gate</em>: it does not advance on a click, and the line
 * written under it - the player's own answer - is not reached until a button is pressed. From the
 * outside that is one extra state to ask about, {@link #awaitingChoice()}, and one more verb,
 * {@link #choose(int)} where {@link #advance()} would be.
 *
 * <p>Reached lines are remembered in {@link #revealed}: a line written under a question is normally
 * <em>not</em> reachable - walking on must not step onto an answer the player never gave - and is
 * made reachable by the one thing that can say it: the button that picks it.
 */
final class DialogueScript {
    private static final Logger LOGGER = LoggerFactory.getLogger("PVZCE/Dialogue");

    /** One slide that is running: which way it goes, and when it started. */
    static final class Slide {
        final boolean entering;
        final long startNanos;

        Slide(boolean entering, long startNanos) {
            this.entering = entering;
            this.startNanos = startNanos;
        }
    }

    /** A character standing in one half of the window. */
    static final class StagePortrait {
        final DialogueCharacterDef character;
        DialogueSlot slot;
        /** The portrait file name they are drawn with; the speaker's comes from their own line. */
        String portrait;
        /**
         * The size they stand at, on this line and the next ones.
         *
         * <p>Not simply {@link DialogueCharacterDef#scale()}, and not final: a {@code scale}
         * animation <em>is</em> a change of size, and one written about somebody standing there
         * changes it for good - which is how a scene says "她变小了" once and then talks to the
         * small one for the next twenty lines. A character the stage has just put up stands at
         * their own size, so somebody who leaves and comes back is themselves again.
         */
        float baseScale;

        StagePortrait(DialogueCharacterDef character, DialogueSlot slot, String portrait) {
            this(character, slot, portrait, character.scale());
        }

        StagePortrait(DialogueCharacterDef character, DialogueSlot slot, String portrait, float baseScale) {
            this.character = character;
            this.slot = slot;
            this.portrait = portrait;
            this.baseScale = baseScale;
        }
    }

    private final List<DialogueLine> lines;
    private final String playerName;
    private final Set<Integer> revealed = new HashSet<>();
    private final Map<Identifier, StagePortrait> stage = new LinkedHashMap<>();
    private final Map<Identifier, Slide> slides = new LinkedHashMap<>();
    /** Per line: the name over the bubble, already substituted. */
    private final List<String> speakerNames = new ArrayList<>();
    /** Per line: the voice clip, already resolved from the line or from the chosen answer. */
    private final List<String> voices = new ArrayList<>();
    private final long startNanos;
    private final boolean exitSlides;
    private int index;
    private boolean finished;
    private boolean exiting;
    private long exitStartNanos;

    private DialogueScript(List<DialogueLine> lines, String playerName, long startNanos, boolean exitSlides) {
        this.lines = lines;
        this.playerName = playerName == null ? "" : playerName;
        this.startNanos = startNanos;
        this.exitSlides = exitSlides;
        for (DialogueLine line : lines) {
            this.speakerNames.add(substitute(line.speakerName(), this.playerName));
            this.voices.add(line.voice() == null ? "" : line.voice());
        }
        for (int i = 0; i < lines.size(); i++) {
            this.revealed.add(i);
        }
        // A reply written under a question is not on the way anywhere: it is reached by answering.
        // Marking it reachable above and taking it back here keeps "reachable" the single list of
        // what a click may step onto, rather than two rules that have to agree.
        for (int i = 0; i + 1 < lines.size(); i++) {
            if (lines.get(i).hasChoices()) {
                this.revealed.remove(i + 1);
            }
        }
        if (!lines.isEmpty()) {
            enter(lines.get(0), startNanos);
            applySize(lines.get(0));
        }
    }

    /**
     * Reads a conversation into a playable script.
     *
     * <p>The names a line may put over its bubble ({@code ${user_name}} for the player) are
     * substituted here and not while drawing: one line then has one final text, and the
     * typewriter, the wrapping and the choice buttons all measure the same string.
     */
    static DialogueScript of(LevelDialogue dialogue, String playerName) {
        List<DialogueLine> lines = dialogue == null ? List.of() : dialogue.lines();
        boolean exitSlides = dialogue == null || dialogue.exit().slides();
        return new DialogueScript(List.copyOf(lines), playerName, System.nanoTime(), exitSlides);
    }

    /** The same substitution the overlay's line texts go through, for a name. */
    private static String substitute(String text, String playerName) {
        if (text == null) {
            return "";
        }
        if (!text.contains(DialogueOverlay.USER_NAME_PLACEHOLDER)) {
            return text;
        }
        return text.replace(DialogueOverlay.USER_NAME_PLACEHOLDER, playerName == null ? "" : playerName);
    }

    // ------------------------------------------------------------------
    // Reading the script
    // ------------------------------------------------------------------

    boolean isEmpty() {
        return lines.isEmpty();
    }

    boolean isActive() {
        return !finished && !lines.isEmpty();
    }

    int index() {
        return index;
    }

    int size() {
        return lines.size();
    }

    DialogueLine line() {
        return lines.get(index);
    }

    DialogueLine line(int at) {
        return lines.get(at);
    }

    /** True while the closing slides are playing: nothing left to advance, waiting for them. */
    boolean isExiting() {
        return exiting;
    }

    /** True when the closing slides are run at all; {@code "exit": "none"} cuts instead. */
    boolean exitSlides() {
        return exitSlides;
    }

    long exitStartNanos() {
        return exitStartNanos;
    }

    /** True when the current line must be answered with a button before anything moves on. */
    boolean awaitingChoice() {
        return isActive() && !exiting && line().hasChoices();
    }

    /** The buttons the current question offers, or none. */
    List<DialogueChoice> choices() {
        return awaitingChoice() ? line().choices() : List.of();
    }

    /** The name to draw over the bubble: the character's, or the one written on the line. */
    String speakerName() {
        if (!isActive()) {
            return "";
        }
        String written = speakerNames.get(index);
        if (!written.isBlank()) {
            return written;
        }
        // A line with a character and no name of its own speaks for that character; a line with
        // neither (the player's own answer, before it is answered) is drawn without a name.
        DialogueCharacterDef character = characterOf(line().character());
        return character == null ? "" : character.displayName();
    }

    /** The voice clip the current line plays, or empty. */
    String voice() {
        return isActive() ? voices.get(index) : "";
    }

    /** The characters on stage, in the order they arrived. */
    List<StagePortrait> portraits() {
        return new ArrayList<>(stage.values());
    }

    /**
     * The character standing in {@code slot}, or {@code null} when that half is empty.
     *
     * <p>Two characters asking for one slot is an author's mistake that the validator reports; the
     * first to arrive keeps it, so the picture is stable rather than depending on map order.
     */
    StagePortrait portraitIn(DialogueSlot slot) {
        for (StagePortrait portrait : stage.values()) {
            if (portrait.slot == slot) {
                return portrait;
            }
        }
        return null;
    }

    /**
     * A slide that is still running for a character who is on stage, or {@code null}.
     *
     * <p>A character who has already left is not a slide any more, however recently they went: the
     * overlay asks this once per portrait it is about to draw, and somebody who is not on stage is
     * not drawn at all.
     */
    Slide slideOf(Identifier id) {
        return id == null || !stage.containsKey(id) ? null : slides.get(id);
    }

    /** When the conversation opened; the opening slide is clocked from here. */
    long startNanos() {
        return startNanos;
    }

    // ------------------------------------------------------------------
    // Playing it
    // ------------------------------------------------------------------

    /**
     * One click, once the overlay has dealt with the typewriter: move to the next line.
     *
     * <p>Three answers live here. A question waits - nothing happens until a button is pressed.
     * The last line ends the conversation, which is where the closing slides start. Anything else
     * steps forward, skipping over any line still waiting behind an unanswered question.
     *
     * @return true when it moved to another line
     */
    boolean advance() {
        if (!isActive() || exiting) {
            return false;
        }
        if (line().hasChoices()) {
            // The question is the gate: a click is not an answer.
            return false;
        }
        return step();
    }

    /**
     * Answers the current question with its {@code choiceIndex}-th button.
     *
     * <p>What the answer does is reveal the line written under the gate and step past it: the line
     * under a question is the <em>player's own words</em> (it is what the button said), and it is
     * never drawn - a conversation shows the character talking, and the button the player pressed
     * has already been read. The step lands on the character's next line in the same click, so
     * pressing a button is immediately followed by the answer to it.
     *
     * <p>A line's own {@code voice}, if it has one, is the clip for the answer, and its speaker name
     * has already been filled in from the player - neither of which is drawn, since the line is not.
     *
     * @return true when the answer was taken and the conversation moved on
     */
    boolean choose(int choiceIndex) {
        if (!awaitingChoice()) {
            return false;
        }
        List<DialogueChoice> offered = line().choices();
        if (choiceIndex < 0 || choiceIndex >= offered.size()) {
            return false;
        }
        int reply = index + 1;
        if (reply >= lines.size()) {
            // A question on the last line has nothing written under it. Treated as an ending
            // rather than as a question nobody can get past; the validator reports it.
            endConversation();
            return false;
        }
        revealed.add(reply);
        DialogueChoice choice = offered.get(choiceIndex);
        if (speakerNames.get(reply).isBlank()) {
            speakerNames.set(reply, playerName);
        }
        voices.set(reply, choice.voice() == null ? "" : choice.voice());
        return step();
    }

    /**
     * Steps to the next line the player may see, or ends the conversation.
     *
     * <p>Two lines are skipped rather than shown: one that is still waiting behind an unanswered
     * question, and one that is the player's own words ({@link #isHidden}). Both are part of the
     * script and neither is part of the picture.
     */
    private boolean step() {
        int next = index + 1;
        while (next < lines.size() && (!revealed.contains(next) || isHidden(next))) {
            next++;
        }
        if (next >= lines.size()) {
            endConversation();
            return false;
        }
        index = next;
        enter(line(), System.nanoTime());
        applySize(line());
        return true;
    }

    /**
     * True for a line that is part of the script and not part of the picture: the player's.
     *
     * <p>A line with a {@code speaker_name} and no character has no portrait and no voice of its
     * own - it is what the <em>player</em> says, which is why it sits under a question and is what
     * that question's buttons say. The conversation never shows it: the button has just been read,
     * and the character's reply to it is what the player is waiting for. It is still a line, so the
     * question below it has somewhere to point and the script can be read start to finish.
     */
    private boolean isHidden(int at) {
        DialogueLine line = lines.get(at);
        return line.character() == null && !line.speakerName().isBlank();
    }

    /** Jumps to the end of the conversation: no closing slides, the host simply gets its call. */
    void skipAll() {
        if (isActive()) {
            finished = true;
            exiting = false;
            slides.clear();
            stage.clear();
        }
    }

    /** Ends the conversation: everyone still on stage leaves, and the host waits for them. */
    private void endConversation() {
        if (!exitSlides) {
            skipAll();
            return;
        }
        exiting = true;
        exitStartNanos = System.nanoTime();
        for (Identifier id : new ArrayList<>(stage.keySet())) {
            slides.put(id, new Slide(false, exitStartNanos));
        }
    }

    /**
     * Advances the stage's clocks: retires finished slides and drops the characters who left.
     *
     * @return true when the conversation is over for good
     */
    boolean tick(long nowNanos) {
        if (finished) {
            return true;
        }
        List<Identifier> done = new ArrayList<>();
        for (Map.Entry<Identifier, Slide> entry : slides.entrySet()) {
            if (DialogueMotion.progress(nowNanos, entry.getValue().startNanos, DialogueMotion.SLIDE_NANOS) >= 1F) {
                done.add(entry.getKey());
            }
        }
        for (Identifier id : done) {
            Slide slide = slides.remove(id);
            if (!slide.entering) {
                stage.remove(id);
            }
        }
        if (exiting && slides.isEmpty()) {
            finished = true;
        }
        return finished;
    }

    /**
     * Stages a line: arrivals slide in, departures slide out, and whoever was already there stays.
     *
     * <p>An arrival is a character who was not in {@link #stage} a moment ago; a departure is one
     * who is in it and not in the new line's stage. A character who merely moves between halves, or
     * who is already where the new line wants them, gets no slide at all - and that is also why
     * nothing happens while the speaker changes: a line with no {@code slots} stages exactly the
     * stage it already had, so the same portraits are simply still there.
     *
     * <p>Somebody already on stage is <em>updated</em> rather than replaced: their size is what the
     * conversation has made of them so far, and a new object would quietly put them back to their
     * own size (see {@link StagePortrait#baseScale}).
     */
    private void enter(DialogueLine line, long nowNanos) {
        Map<Identifier, DialogueSlot> wanted = stageOf(line);
        for (Map.Entry<Identifier, DialogueSlot> entry : wanted.entrySet()) {
            Identifier id = entry.getKey();
            StagePortrait current = stage.get(id);
            if (current != null) {
                // Already there: the line may still change their look or move them, and whatever
                // brought them here is over - a slide left running would push a settled character
                // back off the screen for as long as it lasted.
                current.slot = entry.getValue();
                current.portrait = portraitFor(line, id);
                slides.remove(id);
                continue;
            }
            DialogueCharacterDef character = characterOf(id);
            if (character == null) {
                continue;
            }
            stage.put(id, new StagePortrait(character, entry.getValue(), portraitFor(line, id)));
            slides.put(id, new Slide(true, nowNanos));
        }
        for (Identifier id : new ArrayList<>(stage.keySet())) {
            if (!wanted.containsKey(id)) {
                slides.put(id, new Slide(false, nowNanos));
            }
        }
    }

    /**
     * Makes the line's own size change, if it wrote one: a {@code scale} animation about somebody
     * on stage becomes the size they stand at from here on.
     *
     * <p>Somebody the line does not stage - an author's typo, a character who is not in the scene
     * yet - is left alone, with a line on the log rather than a portrait that changes size for a
     * beat and then changes back.
     */
    private void applySize(DialogueLine line) {
        DialogueAnimation animation = line.animation();
        if (animation == null || !animation.isScale() || animation.isNone()) {
            return;
        }
        if (animation.targetsEveryone()) {
            for (StagePortrait portrait : stage.values()) {
                portrait.baseScale = animation.scale();
            }
            return;
        }
        Identifier target = animation.hasTarget() ? targetOf(animation) : line.character();
        StagePortrait staged = target == null ? null : stage.get(target);
        if (staged == null) {
            if (target != null) {
                LOGGER.warn("Dialogue line {} asks to resize '{}', who is not on stage: nothing happens",
                        index, target);
            }
            return;
        }
        staged.baseScale = animation.scale();
    }

    /**
     * The characters this line's animation happens to, as they stand right now.
     *
     * <p>Who the one-shot half of an animation is drawn on - a shake is applied by the overlay while
     * it draws, and it needs the same answer {@link #applySize} used: the speaker when the line named
     * nobody, one character, or everybody. Empty for a line with no animation, and for a target no
     * one on stage answers to.
     */
    List<Identifier> effectTargets() {
        if (!isActive()) {
            return List.of();
        }
        DialogueLine line = line();
        DialogueAnimation animation = line.animation();
        if (animation == null || animation.isNone()) {
            return List.of();
        }
        List<Identifier> ids = new ArrayList<>();
        if (animation.targetsEveryone()) {
            for (StagePortrait portrait : stage.values()) {
                ids.add(portrait.character.id());
            }
            return ids;
        }
        Identifier target = animation.hasTarget() ? targetOf(animation) : line.character();
        if (target != null && stage.containsKey(target)) {
            ids.add(target);
        }
        return ids;
    }

    /** The character an animation's written target names, or null when it names nobody readable. */
    private static Identifier targetOf(DialogueAnimation animation) {
        return Identifier.tryParse(animation.targetName());
    }

    /** The look a character is drawn with on this line: the speaker's portrait, others keep theirs. */
    private String portraitFor(DialogueLine line, Identifier id) {
        if (id.equals(line.character())) {
            return line.portrait();
        }
        StagePortrait current = stage.get(id);
        return current == null ? "" : current.portrait;
    }

    /**
     * Who this line puts on stage: its own {@code slots}, or the stage it already had.
     *
     * <p>A line with no {@code slots} is a line that says nothing about the stage, and the only
     * thing that can mean is "nobody moves". It used to mean "the previous stage, with the speaker
     * put back in their own half" - which is invisible in a one-character conversation and wrong the
     * moment two characters share the screen: a line spoken by one of them while the other stood
     * there was read as "and the other one leaves", so 3-2 had 缠 walk off in the middle of the
     * scene and come back after 豌豆酱 was gone. Deriving a stage from a line that does not describe
     * one was the mistake; the speaker's half is the stage's business, not their {@code side}'s
     * (which is where their bubble goes).
     *
     * <p>The opening line is still staged from the speaker's side, because there is no previous
     * stage to keep (see {@link #enter}).
     */
    private Map<Identifier, DialogueSlot> stageOf(DialogueLine line) {
        Map<Identifier, DialogueSlot> wanted = new LinkedHashMap<>();
        if (!line.slots().isEmpty()) {
            for (DialogueLine.DialogueSlotEntry entry : line.slots()) {
                if (entry.character() == null || entry.slot() == DialogueSlot.UNKNOWN) {
                    continue;
                }
                if (wanted.putIfAbsent(entry.character(), entry.slot()) != null) {
                    LOGGER.warn("Dialogue stage names '{}' twice on one line: the first slot is kept",
                            entry.character());
                }
            }
            return wanted;
        }
        for (Map.Entry<Identifier, StagePortrait> entry : stage.entrySet()) {
            wanted.put(entry.getKey(), entry.getValue().slot);
        }
        if (wanted.isEmpty() && line.character() != null) {
            // Nothing on stage yet and somebody is speaking: the first line of a conversation stands
            // the speaker in their own half, which is what every conversation written before slots
            // existed relies on. A player's line ({@code character == null}) stages nobody, so it
            // leaves the stage empty rather than putting the player in it.
            wanted.put(line.character(), line.side().isRight() ? DialogueSlot.RIGHT : DialogueSlot.LEFT);
        }
        return wanted;
    }

    private static DialogueCharacterDef characterOf(Identifier id) {
        if (id == null) {
            return null;
        }
        DialogueCharacterDef character = BuiltInRegistries.DIALOGUE_CHARACTERS.get(id);
        if (character == null) {
            LOGGER.warn("Unknown dialogue character '{}': the line is shown without a portrait", id);
        }
        return character;
    }
}
