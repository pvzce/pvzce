package com.pvzce.client.gui.components;

import com.pvzce.api.content.DialogueAnimation;
import com.pvzce.api.content.DialogueChoice;
import com.pvzce.api.content.DialogueEffect;
import com.pvzce.api.content.DialogueLine;
import com.pvzce.api.content.DialogueSlot;
import com.pvzce.api.content.LevelDialogue;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.tag.TestContent;
import com.pvzce.server.level.LevelValidator;
import com.pvzce.testutil.TestLevels;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The stage a conversation builds, and what a click means on it.
 *
 * <p>{@link DialogueOverlay} is a texture, a bubble and a modal's input handling, and none of that
 * can be asserted; who is standing on screen after a line, and which line a button reaches, can be,
 * and that is where the mistakes are: a character who never walks off, a question answered by
 * clicking anywhere, a second arrival that slides in again on every line because the stage was
 * rebuilt from scratch.
 *
 * <p>Real character definitions are used rather than stubs, because half of what is asserted is
 * which id ends up in which half of the window, and a made-up id cannot be resolved at all.
 */
class DialogueStageTest {
    private static final Identifier ENTANG = Identifier.withDefaultNamespace("entang");
    private static final Identifier PEA = Identifier.withDefaultNamespace("pea_chan");
    /** The two who share the screen in the fog: the shrunken one and the one who lights it up. */
    private static final Identifier PURWHITE = Identifier.withDefaultNamespace("purwhite");
    private static final Identifier LANTINA = Identifier.withDefaultNamespace("lantina");
    /** The player, as the title screen would have named them. */
    private static final String PLAYER = "莉安";

    @BeforeAll
    static void loadContent() throws Exception {
        TestContent.loadBuiltInContentAndTags();
    }

    private static DialogueLine line(Identifier character, String portrait, String text, DialogueSlot side,
                                     List<DialogueLine.DialogueSlotEntry> slots, List<DialogueChoice> choices) {
        return line(character, portrait, text, side, slots, choices, DialogueAnimation.NONE);
    }

    private static DialogueLine line(Identifier character, String portrait, String text, DialogueSlot side,
                                     List<DialogueLine.DialogueSlotEntry> slots, List<DialogueChoice> choices,
                                     DialogueAnimation animation) {
        return new DialogueLine(character, portrait, text, "", side == DialogueSlot.RIGHT
                ? DialogueLine.Side.RIGHT : DialogueLine.Side.LEFT, animation, slots, choices, "");
    }

    /** The player's own line: no character, no portrait, and the name they gave at the title screen. */
    private static DialogueLine playerLine(DialogueSlot side) {
        return new DialogueLine(null, "", "${user_name}", "", side == DialogueSlot.RIGHT
                ? DialogueLine.Side.RIGHT : DialogueLine.Side.LEFT, DialogueAnimation.NONE,
                List.of(), List.of(), "${user_name}");
    }

    private static DialogueLine.DialogueSlotEntry at(DialogueSlot slot, Identifier character) {
        return new DialogueLine.DialogueSlotEntry(character, slot);
    }

    private static DialogueScript scriptOf(DialogueLine... lines) {
        return DialogueScript.of(new LevelDialogue(List.of(lines)), PLAYER);
    }

    /** A one-character conversation is staged exactly as it always was: the speaker, their half. */
    @Test
    void aSingleSpeakerStandsInTheirOwnHalf() {
        DialogueScript script = scriptOf(
                line(ENTANG, "confused", "嗯，你谁？", DialogueSlot.LEFT, List.of(), List.of()));

        assertEquals(1, script.portraits().size());
        DialogueScript.StagePortrait staged = script.portraits().get(0);
        assertEquals(ENTANG, staged.character.id());
        assertEquals(DialogueSlot.LEFT, staged.slot);
        assertEquals("缠", script.speakerName(), "the name over the bubble is the character's");
    }

    /**
     * A line that names a second character walks them on from their own half, and keeps the one who
     * was already there - the two things the old shape of a line could not say.
     */
    @Test
    void aSecondCharacterWalksOnAndTheFirstStays() {
        DialogueScript script = scriptOf(
                line(ENTANG, "bored", "又见面了", DialogueSlot.LEFT, List.of(), List.of()),
                line(PEA, "fierce", "杂鱼你怎么跑这里来了！", DialogueSlot.RIGHT,
                        List.of(at(DialogueSlot.LEFT, ENTANG), at(DialogueSlot.RIGHT, PEA)), List.of()));

        assertTrue(script.advance(), "the second line is reached by a click");
        assertEquals(2, script.portraits().size(), "both characters are on stage");

        DialogueScript.Slide arriving = script.slideOf(PEA);
        assertNotNull(arriving, "the new arrival slides in");
        assertTrue(arriving.entering);
        assertNull(script.slideOf(ENTANG), "and the character who was already there does not move");
        assertEquals(ENTANG, script.portraitIn(DialogueSlot.LEFT).character.id());
        assertEquals(PEA, script.portraitIn(DialogueSlot.RIGHT).character.id());

        // Once the slide is over the character is simply there: no timer left to run.
        script.tick(System.nanoTime() + DialogueMotion.SLIDE_NANOS * 2);
        assertNull(script.slideOf(PEA));
        assertEquals(2, script.portraits().size());
    }

    /**
     * A line with no {@code slots} leaves the stage alone - it never walks anybody off.
     *
     * <p>This is the bug 3-2 shipped with: only the lines that changed the stage named it, and every
     * other line re-derived the stage as "the previous one, plus the speaker in their own half". In a
     * one-character conversation the two answers agree; with two characters on stage the second one
     * was read as absent, so 缠 walked off in the middle of the scene and walked back on when the
     * next line named her again.
     */
    @Test
    void aLineWithNoSlotsLeavesTheStageAlone() {
        DialogueScript script = scriptOf(
                line(ENTANG, "bored", "又见面了", DialogueSlot.LEFT, List.of(), List.of()),
                line(PEA, "fierce", "杂鱼你怎么跑这里来了！", DialogueSlot.RIGHT,
                        List.of(at(DialogueSlot.LEFT, ENTANG), at(DialogueSlot.RIGHT, PEA)), List.of()),
                line(PEA, "fierce", "坏蛋，怎么不告...", DialogueSlot.RIGHT, List.of(), List.of()),
                line(PEA, "scared", "诶，你是...?", DialogueSlot.RIGHT, List.of(), List.of()));

        assertTrue(script.advance());
        assertTrue(script.advance(), "the line after the arrival says nothing about the stage");
        assertEquals(2, script.portraits().size(), "so both characters are still standing there");
        assertNull(script.slideOf(ENTANG), "and nobody is walking anywhere");
        assertEquals(ENTANG, script.portraitIn(DialogueSlot.LEFT).character.id());
        assertTrue(script.advance());
        assertEquals(2, script.portraits().size(), "the same holds for every line after it");
        assertNull(script.slideOf(ENTANG));
    }

    /** A character the next line leaves out walks off, and is gone once their slide has played. */
    @Test
    void aCharacterTheNextLineLeavesOutWalksOff() {
        DialogueScript script = scriptOf(
                line(PEA, "fierce", "杂鱼你怎么跑这里来了！", DialogueSlot.RIGHT,
                        List.of(at(DialogueSlot.LEFT, ENTANG), at(DialogueSlot.RIGHT, PEA)), List.of()),
                line(PEA, "embrassed", "", DialogueSlot.RIGHT,
                        List.of(at(DialogueSlot.RIGHT, PEA)), List.of()));

        assertTrue(script.advance());
        DialogueScript.Slide leaving = script.slideOf(ENTANG);
        assertNotNull(leaving, "the character left out of the line leaves");
        assertFalse(leaving.entering, "which is a departure, not an arrival");
        // The portrait is still drawn while it slides: the stage drops them when the slide is over.
        assertEquals(2, script.portraits().size());
        script.tick(System.nanoTime() + DialogueMotion.SLIDE_NANOS * 2);
        assertEquals(1, script.portraits().size());
        assertEquals(PEA, script.portraits().get(0).character.id());
    }

    /**
     * A {@code scale} line changes who it names, and keeps them that size afterwards.
     *
     * <p>The whole point of writing the size on a line is 4-2: 兰提娜 walks in, says something, and
     * 紫夜白 is small from that beat on. So the animation moves the listener rather than the speaker,
     * and what it leaves behind is a <em>standing</em> size - every line after it, including the ones
     * that say nothing about size at all, draws her the same way.
     */
    @Test
    void aScaleAnimationResizesTheCharacterItNames() {
        DialogueLine.DialogueSlotEntry left = at(DialogueSlot.LEFT, PURWHITE);
        DialogueLine.DialogueSlotEntry right = at(DialogueSlot.RIGHT, LANTINA);
        DialogueAnimation shrink = new DialogueAnimation(DialogueAnimation.TYPE_SCALE, 1F, 0.33F,
                PURWHITE.toString());
        DialogueScript script = scriptOf(
                line(LANTINA, "confused", "诶？这个，好小一只", DialogueSlot.RIGHT,
                        List.of(left, right), List.of(), shrink),
                line(PURWHITE, "fierce", "不要说咱小啦", DialogueSlot.LEFT,
                        List.of(left, right), List.of(), DialogueAnimation.NONE),
                line(LANTINA, "smile", "知道啦知道啦", DialogueSlot.RIGHT,
                        List.of(left, right), List.of(), DialogueAnimation.NONE));

        assertEquals(0.33F, script.portraitIn(DialogueSlot.LEFT).baseScale, 0.0001F,
                "the character the animation names is the one who shrinks");
        assertEquals(1F, script.portraitIn(DialogueSlot.RIGHT).baseScale, 0.0001F,
                "and the one who spoke is left alone");

        assertTrue(script.advance());
        assertEquals(0.33F, script.portraitIn(DialogueSlot.LEFT).baseScale, 0.0001F,
                "the size is hers from here on, not a beat on one line");
        assertTrue(script.advance());
        assertEquals(0.33F, script.portraitIn(DialogueSlot.LEFT).baseScale, 0.0001F,
                "including on a line with no animation at all");
        assertFalse(script.effectTargets().contains(PURWHITE),
                "and the one-shot half of the animation is over with its line");
    }

    /** An animation that names nobody is the speaker's, exactly as every one written before this was. */
    @Test
    void anAnimationWithNoTargetIsTheSpeakers() {
        DialogueLine.DialogueSlotEntry left = at(DialogueSlot.LEFT, PURWHITE);
        DialogueLine.DialogueSlotEntry right = at(DialogueSlot.RIGHT, LANTINA);
        DialogueAnimation shake = new DialogueAnimation(DialogueAnimation.TYPE_SHAKE, 1F, 1F);
        DialogueScript script = scriptOf(
                line(LANTINA, "panic", "唔唔！好大的雾！", DialogueSlot.RIGHT,
                        List.of(left, right), List.of(), shake));

        assertEquals(List.of(LANTINA), script.effectTargets(), "the speaker shakes");
        assertEquals(1F, script.portraitIn(DialogueSlot.LEFT).baseScale, 0.0001F,
                "and the listener keeps their size: a shake is not a resize");
    }

    /** {@code "all"} is the whole picture, which is what a scene-wide beat needs. */
    @Test
    void anAnimationCanBeAboutEveryoneOnStage() {
        DialogueLine.DialogueSlotEntry left = at(DialogueSlot.LEFT, PURWHITE);
        DialogueLine.DialogueSlotEntry right = at(DialogueSlot.RIGHT, LANTINA);
        DialogueAnimation everybody = new DialogueAnimation(DialogueAnimation.TYPE_SCALE, 1F, 1.1F,
                DialogueAnimation.TARGET_ALL);
        DialogueScript script = scriptOf(
                line(LANTINA, "glowing", "我可以，发！光！", DialogueSlot.RIGHT,
                        List.of(left, right), List.of(), everybody));

        assertEquals(1.1F, script.portraitIn(DialogueSlot.LEFT).baseScale, 0.0001F);
        assertEquals(1.1F, script.portraitIn(DialogueSlot.RIGHT).baseScale, 0.0001F);
        assertEquals(2, script.effectTargets().size(), "both of them are on stage");
    }

    /** A question is not answered by a click: the conversation waits, and only a button moves it. */
    @Test
    void aQuestionWaitsForAnAnswer() {

        DialogueScript script = scriptOf(
                line(ENTANG, "confused", "嗯，你谁？", DialogueSlot.LEFT, List.of(),
                        List.of(new DialogueChoice("莉安"), new DialogueChoice("路过的人"))),
                playerLine(DialogueSlot.LEFT));

        assertTrue(script.awaitingChoice(), "the first line asks");
        assertEquals(2, script.choices().size());
        assertFalse(script.advance(), "a click does not answer it");
        assertEquals(0, script.index(), "so the conversation has not moved");
        assertFalse(script.choose(7), "and there is no eighth answer");
        assertEquals(0, script.index());
    }

    /**
     * Picking an answer goes straight to the character's next line: the player's own line is part of
     * the script and never part of the picture.
     */
    @Test
    void anAnswerSkipsThePlayersOwnLine() {
        DialogueScript script = scriptOf(
                line(ENTANG, "confused", "嗯，你谁？", DialogueSlot.LEFT, List.of(),
                        List.of(new DialogueChoice("莉安"), new DialogueChoice("路过的人"))),
                playerLine(DialogueSlot.LEFT),
                line(ENTANG, "gentle", "明白了", DialogueSlot.LEFT, List.of(), List.of()));

        assertTrue(script.choose(1), "the answer is taken");
        assertEquals(2, script.index(), "and the character answers in the same click");
        assertEquals("缠", script.speakerName(), "the speaker is the character, not the player");
        assertFalse(script.advance(), "and the character's line is the last one");
        assertTrue(script.isExiting(), "so the conversation walks its portraits off");
    }

    /** A question with nothing written under it (or nothing after the answer) still ends. */
    @Test
    void aQuestionWithNothingAfterItStillEnds() {
        DialogueScript bare = scriptOf(
                line(ENTANG, "confused", "你是这里的主人吗？", DialogueSlot.LEFT, List.of(),
                        List.of(new DialogueChoice("是的"))));
        assertTrue(bare.awaitingChoice());
        assertFalse(bare.choose(0), "there is no answer line to reveal, so the answer ends it");
        assertTrue(bare.isExiting());

        DialogueScript truncated = scriptOf(
                line(ENTANG, "confused", "你是这里的主人吗？", DialogueSlot.LEFT, List.of(),
                        List.of(new DialogueChoice("是的"))),
                playerLine(DialogueSlot.LEFT));
        assertTrue(truncated.awaitingChoice());
        assertFalse(truncated.choose(0), "nothing follows the answer, so there is nothing to play");
    }

    /** The closing slides are walked through before the host is told the conversation is over. */
    @Test
    void theLastLineLeavesTheStageEmptyBeforeItEnds() {
        DialogueScript script = scriptOf(
                line(ENTANG, "gentle", "明白了", DialogueSlot.LEFT, List.of(), List.of()));

        assertFalse(script.advance(), "the last click ends the conversation rather than moving it");
        assertTrue(script.isExiting());
        assertFalse(script.tick(System.nanoTime()), "the portraits are still on their way out");
        assertFalse(script.portraits().isEmpty());
        assertTrue(script.tick(System.nanoTime() + DialogueMotion.SLIDE_NANOS * 2));
        assertTrue(script.portraits().isEmpty(), "and the stage is clear when it is over");
        assertFalse(script.isActive());
    }

    /** With {@code "exit": "none"} the conversation ends at once, with no slide to wait out. */
    @Test
    void aCutEndingEndsImmediately() {
        DialogueScript script = DialogueScript.of(new LevelDialogue(
                List.of(line(ENTANG, "gentle", "明白了", DialogueSlot.LEFT, List.of(), List.of())),
                DialogueEffect.SLIDE, DialogueEffect.NONE), PLAYER);

        assertFalse(script.advance());
        assertTrue(script.tick(System.nanoTime()), "no closing slide, so nothing to wait for");
        assertFalse(script.isActive());
    }

    /** Every built-in conversation plays to its end with somebody on stage the whole way. */
    @Test
    void everyShippedConversationStagesSomething() {
        List<String> problems = new ArrayList<>();
        for (Identifier id : BuiltInRegistries.LEVELS.keySet()) {
            var def = BuiltInRegistries.LEVELS.get(id);
            if (def == null || def.dialogue() == null || def.dialogue().isEmpty()) {
                continue;
            }
            DialogueScript script = DialogueScript.of(def.dialogue(), PLAYER);
            if (script.portraits().isEmpty()) {
                problems.add(id + ": nothing on stage for the opening line");
                continue;
            }
            int guard = 0;
            while (script.isActive() && guard++ < 500) {
                if (script.portraits().isEmpty()) {
                    problems.add(id + ": nobody on stage at line " + script.index());
                    break;
                }
                // Take the first answer whenever the line asks, the way a player would; the calls
                // that only end the conversation return false and leave the stage to walk off.
                // The clock is moved far enough past every slide the script just started, because
                // the test drives it by hand and the frame loop it stands in for would have taken
                // a third of a second.
                if (!script.choose(0)) {
                    script.advance();
                }
                script.tick(System.nanoTime() + DialogueMotion.SLIDE_NANOS * 2);
            }
            if (guard >= 500) {
                problems.add(id + ": the conversation never ends");
            }
        }
        assertTrue(problems.isEmpty(), "conversations that stage nothing:\n" + String.join("\n", problems));
    }

    /**
     * Nobody leaves the stage while they still have something to say.
     *
     * <p>The rule the reader actually cares about, and the one 3-2 broke: a line that says nothing
     * about the stage re-derived one, which silently moved 缠 out of the way and back. A character
     * who speaks later has to be standing where they were, whatever the lines in between say - so a
     * missing {@code slots} can never quietly empty or rearrange half the window.
     */
    @Test
    void aCharacterDoesNotMoveOrLeaveWhileTheyStillHaveLines() {
        List<String> problems = new ArrayList<>();
        for (Identifier id : BuiltInRegistries.LEVELS.keySet()) {
            var def = BuiltInRegistries.LEVELS.get(id);
            if (def == null || def.dialogue() == null || def.dialogue().isEmpty()) {
                continue;
            }
            List<com.pvzce.api.content.DialogueLine> lines = def.dialogue().lines();
            DialogueScript script = DialogueScript.of(def.dialogue(), PLAYER);
            java.util.Map<Identifier, DialogueSlot> previous = new java.util.HashMap<>();
            for (int at = 0; at < lines.size(); at++) {
                java.util.Set<Identifier> onStage = new java.util.HashSet<>();
                java.util.Map<Identifier, DialogueSlot> where = new java.util.HashMap<>();
                for (DialogueScript.StagePortrait staged : script.portraits()) {
                    onStage.add(staged.character.id());
                    where.put(staged.character.id(), staged.slot);
                }
                // A line that says nothing about the stage has to leave every half of the window
                // exactly as it was - not merely keep the same cast. 3-2's bug was a character
                // silently changing halves, which is invisible to a "who is on stage" check.
                if (at > 0 && lines.get(at).slots().isEmpty()) {
                    for (java.util.Map.Entry<Identifier, DialogueSlot> entry : previous.entrySet()) {
                        if (where.get(entry.getKey()) != entry.getValue()) {
                            problems.add(id + ": line " + at + " says nothing about the stage but moved "
                                    + entry.getKey() + " from " + entry.getValue() + " to "
                                    + where.get(entry.getKey()));
                        }
                    }
                }
                for (int later = at + 1; later < lines.size(); later++) {
                    Identifier speaker = lines.get(later).character();
                    if (speaker == null) {
                        continue;
                    }
                    // Somebody who has not spoken yet is on their way in, not on their way out: a
                    // character walks on at their first line (the arrival is staged by it).
                    boolean alreadySpoke = false;
                    for (int earlier = 0; earlier <= at; earlier++) {
                        alreadySpoke |= speaker.equals(lines.get(earlier).character());
                    }
                    if (alreadySpoke && !onStage.contains(speaker)) {
                        problems.add(id + ": " + speaker + " is off stage at line " + at
                                + " but speaks again at line " + later);
                        break;
                    }
                }
                previous.clear();
                previous.putAll(where);
                if (!script.choose(0)) {
                    script.advance();
                }
            }
        }
        assertTrue(problems.isEmpty(), "characters who move or leave while they still have lines:\n"
                + String.join("\n", problems));
    }

    /** A line the player speaks: no character, and the conversation never draws it. */
    private static boolean isPlayerLine(com.pvzce.api.content.DialogueLine line) {
        return line.character() == null;
    }

    /**
     * The validator names the stages the script cannot build, so a hand-written conversation that
     * drops a character or asks a question nobody can answer is a report rather than a surprise.
     */
    @Test
    void theValidatorNamesAStageItCannotBuild() {
        var level = BuiltInRegistries.LEVELS.get(Identifier.withDefaultNamespace("yard/adventure/3_2"));
        assertNotNull(level, "3-2 is the level this conversation was written for");
        assertTrue(LevelValidator.validateDialogue(level).isEmpty(),
                "and the conversation as shipped is clean: " + LevelValidator.validateDialogue(level));

        LevelDialogue broken = new LevelDialogue(List.of(
                new DialogueLine(ENTANG, "bored", "又见面了", "", DialogueLine.Side.LEFT,
                        DialogueAnimation.NONE,
                        List.of(at(DialogueSlot.LEFT, ENTANG), at(DialogueSlot.LEFT, PEA),
                                at(DialogueSlot.RIGHT, Identifier.withDefaultNamespace("nobody"))),
                        List.of(), ""),
                new DialogueLine(null, "", "嗯", "", DialogueLine.Side.LEFT,
                        DialogueAnimation.NONE, List.of(), List.of(new DialogueChoice("")), ""),
                // A question whose answer is written but leads nowhere: pressing it would end the
                // conversation on the spot, which is not what the author wrote a question for.
                new DialogueLine(ENTANG, "gentle", "那你是？", "", DialogueLine.Side.LEFT,
                        DialogueAnimation.NONE, List.of(), List.of(new DialogueChoice("路人")), ""),
                new DialogueLine(null, "", "", "", DialogueLine.Side.LEFT,
                        DialogueAnimation.NONE, List.of(), List.of(), "${user_name}")));
        List<String> problems = LevelValidator.validateDialogue(
                TestLevels.copy(level).dialogue(broken).build());

        assertTrue(problems.stream().anyMatch(p -> p.contains("second character in the left")),
                "a half of the window that already has someone in it: " + problems);
        assertTrue(problems.stream().anyMatch(p -> p.contains("unknown character 'pvzce:nobody'")),
                "a character nobody registered: " + problems);
        assertTrue(problems.stream().anyMatch(p -> p.contains("names no character and no speaker_name")),
                "a line with nobody to put over the bubble: " + problems);
        assertTrue(problems.stream().anyMatch(p -> p.contains("nothing follows the player's answer")),
                "a question whose answer leads nowhere: " + problems);
        assertTrue(problems.stream().anyMatch(p -> p.contains("choices[0] is empty")),
                "an answer with no text: " + problems);
    }
}
