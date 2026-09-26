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
    /** The player, as the title screen would have named them. */
    private static final String PLAYER = "莉安";

    @BeforeAll
    static void loadContent() throws Exception {
        TestContent.loadBuiltInContentAndTags();
    }

    private static DialogueLine line(Identifier character, String portrait, String text, DialogueSlot side,
                                     List<DialogueLine.DialogueSlotEntry> slots, List<DialogueChoice> choices) {
        return new DialogueLine(character, portrait, text, "", side == DialogueSlot.RIGHT
                ? DialogueLine.Side.RIGHT : DialogueLine.Side.LEFT, DialogueAnimation.NONE, slots, choices, "");
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

    /** Picking an answer speaks the line written under the question, in the player's own name. */
    @Test
    void anAnswerIsSpokenByThePlayer() {
        DialogueScript script = scriptOf(
                line(ENTANG, "confused", "嗯，你谁？", DialogueSlot.LEFT, List.of(),
                        List.of(new DialogueChoice("莉安"), new DialogueChoice("路过的人"))),
                playerLine(DialogueSlot.LEFT),
                line(ENTANG, "gentle", "明白了", DialogueSlot.LEFT, List.of(), List.of()));

        assertTrue(script.choose(1), "the answer is taken");
        assertEquals(1, script.index(), "and the reply is on screen in the same click");
        assertEquals(PLAYER, script.speakerName(), "named after the player, not after a character");
        assertTrue(script.advance(), "walking on reaches the line after the reply");
        assertEquals(2, script.index());
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

    /** Answering a question the data wrote on the last line ends the conversation instead of hanging. */
    @Test
    void aQuestionWithNothingUnderItStillEnds() {
        DialogueScript script = scriptOf(
                line(ENTANG, "confused", "你是这里的主人吗？", DialogueSlot.LEFT, List.of(),
                        List.of(new DialogueChoice("是的"))));

        assertTrue(script.awaitingChoice());
        assertFalse(script.choose(0), "there is no reply line, so the answer ends the conversation");
        assertTrue(script.isExiting());
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
                if (script.portraitIn(DialogueSlot.LEFT) == null
                        && script.portraitIn(DialogueSlot.RIGHT) == null) {
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
                        DialogueAnimation.NONE, List.of(), List.of(new DialogueChoice("")), "")));
        List<String> problems = LevelValidator.validateDialogue(
                TestLevels.copy(level).dialogue(broken).build());

        assertTrue(problems.stream().anyMatch(p -> p.contains("second character in the left")),
                "a half of the window that already has someone in it: " + problems);
        assertTrue(problems.stream().anyMatch(p -> p.contains("unknown character 'pvzce:nobody'")),
                "a character nobody registered: " + problems);
        assertTrue(problems.stream().anyMatch(p -> p.contains("names no character and no speaker_name")),
                "a line with nobody to put over the bubble: " + problems);
        assertTrue(problems.stream().anyMatch(p -> p.contains("choices on the last line")),
                "a question with no line written under it: " + problems);
        assertTrue(problems.stream().anyMatch(p -> p.contains("choices[0] is empty")),
                "an answer with no text: " + problems);
    }
}
