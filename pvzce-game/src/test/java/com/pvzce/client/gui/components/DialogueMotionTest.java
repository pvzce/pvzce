package com.pvzce.client.gui.components;

import com.pvzce.api.content.DialogueAnimation;
import com.pvzce.api.content.DialogueEffect;
import com.pvzce.api.content.DialogueLine;
import com.pvzce.api.content.DialogueSlot;
import com.pvzce.api.content.LevelDialogue;
import com.pvzce.api.util.Identifier;
import com.mojang.serialization.JsonOps;
import org.junit.jupiter.api.Test;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The staging maths behind a conversation's slides, shake and size.
 *
 * <p>Extracted from {@code DialogueOverlay} on purpose: the overlay is a texture, a bubble and
 * a modal's input handling, and none of that can be asserted. Where the portrait is at a given
 * nanosecond can be, and it is where the mistakes live - an offset that does not reach zero, a
 * shake that stops mid-swing, a size that snaps instead of easing.
 */
class DialogueMotionTest {
    private static final long START = 1_000_000_000L;

    /** A slide starts a window away from the place it ends, on the speaker's own side. */
    @Test
    void aSlideTravelsFromTheSpeakersOwnEdge() {
        float guiW = 400F;
        assertEquals(-guiW, DialogueMotion.slideOffsetX(0F, false, true, true, guiW), 0.01F,
                "a left speaker starts a window to the left");
        assertEquals(guiW, DialogueMotion.slideOffsetX(0F, false, false, true, guiW), 0.01F,
                "a right speaker starts a window to the right");
        assertEquals(0F, DialogueMotion.slideOffsetX(1F, false, true, true, guiW), 0.01F,
                "and both end in place");
        assertEquals(0F, DialogueMotion.slideOffsetX(0.5F, true, true, true, guiW), 0.01F,
                "a centred speaker never moves sideways");
    }

    /** Leaving is the same journey backwards, so the exit offset grows where the entry shrank. */
    @Test
    void leavingRetracesTheSameJourney() {
        float guiW = 400F;
        assertEquals(0F, DialogueMotion.slideOffsetX(0F, false, true, false, guiW), 0.01F,
                "the exit starts in place");
        assertEquals(-guiW * 0.5F, DialogueMotion.slideOffsetX(0.5F, false, true, false, guiW), 0.01F);
        assertEquals(-guiW, DialogueMotion.slideOffsetX(1F, false, true, false, guiW), 0.01F,
                "and ends a window off screen");
    }

    /** A centred speaker has no side, so they come up from below and sink back down. */
    @Test
    void theCentreSpeakerComesUpFromBelow() {
        float guiH = 300F;
        assertEquals(-guiH, DialogueMotion.slideOffsetY(0F, true, true, guiH), 0.01F,
                "below the window, rising");
        assertEquals(0F, DialogueMotion.slideOffsetY(1F, true, true, guiH), 0.01F);
        assertEquals(-guiH, DialogueMotion.slideOffsetY(1F, true, false, guiH), 0.01F,
                "and back down when leaving");
        assertEquals(0F, DialogueMotion.slideOffsetY(0.4F, false, true, guiH), 0.01F,
                "a side speaker does not move vertically");
    }

    /** The slide is eased and bounded, so a long frame cannot overshoot the stage. */
    @Test
    void slideProgressIsEasedAndClamped() {
        assertEquals(0F, DialogueMotion.slideProgress(START, START), 0.0001F);
        assertEquals(1F, DialogueMotion.slideProgress(START + DialogueMotion.SLIDE_NANOS, START), 0.0001F);
        assertEquals(1F, DialogueMotion.slideProgress(START + DialogueMotion.SLIDE_NANOS * 4, START), 0.0001F,
                "a late frame is still the end of the slide");
        assertEquals(0F, DialogueMotion.slideProgress(START - 1, START), 0.0001F,
                "and a frame from before it started is still the beginning");

        float quarter = DialogueMotion.slideProgress(START + DialogueMotion.SLIDE_NANOS / 4, START);
        assertTrue(quarter > 0F && quarter < 0.5F,
                "eased in: a quarter of the time is less than a quarter of the distance: " + quarter);
    }

    /** The shake moves, settles, and is exactly over when its timer runs out. */
    @Test
    void theShakeIsDampedAndEndsAtRest() {
        float guiW = 400F;
        assertEquals(0F, DialogueMotion.shakeOffset(START, START, guiW, 1F), 0.0001F,
                "it starts at rest");
        assertEquals(0F, DialogueMotion.shakeOffset(START + DialogueMotion.SHAKE_NANOS, START, guiW, 1F),
                0.0001F, "and stops at rest, so the portrait does not jump when it ends");
        assertEquals(0F, DialogueMotion.shakeOffset(START + DialogueMotion.SHAKE_NANOS / 3, START, guiW, 0F),
                0.0001F, "an amount of 0 does not shake at all");

        float peak = 0F;
        for (int i = 1; i < 100; i++) {
            long at = START + DialogueMotion.SHAKE_NANOS * i / 100;
            peak = Math.max(peak, Math.abs(DialogueMotion.shakeOffset(at, START, guiW, 1F)));
        }
        assertTrue(peak > 1F && peak < guiW * DialogueMotion.SHAKE_AMPLITUDE_RATIO * 1.01F,
                "the shake is visible but stays inside its amplitude: " + peak);
        // A louder line shakes proportionally more.
        float doubled = Math.abs(DialogueMotion.shakeOffset(
                START + DialogueMotion.SHAKE_NANOS / 8, START, guiW, 2F));
        float single = Math.abs(DialogueMotion.shakeOffset(
                START + DialogueMotion.SHAKE_NANOS / 8, START, guiW, 1F));
        assertEquals(single * 2F, doubled, 0.001F);
    }

    /** A size change is interpolated from where the previous line left it. */
    @Test
    void aSizeChangeIsInterpolatedAndCanAlsoBeInstant() {
        assertEquals(1F, DialogueMotion.scaleAt(1F, 1.25F, START, START, true), 0.0001F);
        assertEquals(1.25F, DialogueMotion.scaleAt(1F, 1.25F,
                START + DialogueMotion.SCALE_NANOS, START, true), 0.0001F);
        assertEquals(1.25F, DialogueMotion.scaleAt(1F, 1.25F,
                START + DialogueMotion.SCALE_NANOS * 3, START, true), 0.0001F, "clamped at the end");
        assertEquals(1.25F, DialogueMotion.scaleAt(0.5F, 1.25F, START, START, false), 0.0001F,
                "a line that does not animate its size is simply drawn at its own");
    }

    /**
     * A line may stretch its own animation, and the frame loop is what stretches with it.
     *
     * <p>4-2 is the case: 兰提娜 walks in over 0.35s while 紫夜白 shrinks beside her. At the built-in
     * quarter of a second the shrink would be over before the walk is - a cut rather than a height
     * difference - so the line says {@code "duration": 0.7}. A line that says nothing keeps the
     * built-in length, which is every animation written before this field existed.
     */
    @Test
    void anAnimationCanTakeAsLongAsTheLineSays() {
        long stretched = new DialogueAnimation(DialogueAnimation.TYPE_SCALE, 1F, 0.33F,
                "pvzce:purwhite", 0.7F).durationMillis();
        assertEquals(700L, stretched);
        assertEquals(0L, new DialogueAnimation(DialogueAnimation.TYPE_SCALE, 1F, 0.33F).durationMillis(),
                "an unwritten duration is the kind's own length, not instant");
        assertEquals(DialogueAnimation.MAX_DURATION,
                new DialogueAnimation(DialogueAnimation.TYPE_SCALE, 1F, 0.33F, "", 99F).duration(), 0.0001F);

        long stretchedNanos = stretched * 1_000_000L;
        long halfway = START + stretchedNanos / 2;
        assertEquals(0.33F, DialogueMotion.scaleAt(1F, 0.33F, halfway, START, true), 0.0001F,
                "the built-in length is long over halfway through 0.7s");
        float soFar = DialogueMotion.scaleAt(1F, 0.33F, halfway, START, true, stretched);
        assertTrue(soFar > 0.33F && soFar < 1F,
                "the stretched one is still on its way at that moment, and was " + soFar);
        assertEquals(0.33F,
                DialogueMotion.scaleAt(1F, 0.33F, START + stretchedNanos, START, true, stretched),
                0.0001F, "and it does arrive");
    }

    /** The content rules the overlay reads: what a line without an animation means. */
    @Test
    void theAnimationDefaultsAreTheQuietOnes() {
        assertTrue(DialogueAnimation.NONE.isNone());
        assertEquals(1F, DialogueAnimation.NONE.targetScale(), 0.0001F,
                "a line with no animation leaves the portrait at its layout size");
        assertTrue(DialogueAnimation.NONE.isKnown());

        DialogueAnimation scale = new DialogueAnimation(DialogueAnimation.TYPE_SCALE, 1F, 1.3F);
        assertTrue(scale.isScale());
        assertEquals(1.3F, scale.targetScale(), 0.0001F);
        // The ceiling is a property of the staging: past it the portrait leaves the window.
        assertEquals(DialogueAnimation.MAX_SCALE,
                new DialogueAnimation(DialogueAnimation.TYPE_SCALE, 1F, 99F).scale(), 0.0001F);
        assertEquals(DialogueAnimation.MAX_AMOUNT,
                new DialogueAnimation(DialogueAnimation.TYPE_SHAKE, 99F, 1F).amount(), 0.0001F);
        assertFalse(new DialogueAnimation("sparkle", 1F, 1F).isKnown(),
                "a kind this version does not draw is reported, not drawn as something else");
    }

    /** The JSON shapes: an unwritten effect slides, an unwritten animation is none. */
    @Test
    void theCodecsDefaultToTheStagedConversation() {
        LevelDialogue parsed = LevelDialogue.CODEC.parse(JsonOps.INSTANCE,
                com.google.gson.JsonParser.parseString("""
                        { "lines": [ { "character": "pvzce:pea_chan", "text": "嗨" } ] }
                        """)).getOrThrow();
        assertEquals(DialogueEffect.SLIDE, parsed.enter());
        assertEquals(DialogueEffect.SLIDE, parsed.exit());
        assertEquals(DialogueAnimation.TYPE_NONE, parsed.lines().get(0).animation().type());

        LevelDialogue quiet = LevelDialogue.CODEC.parse(JsonOps.INSTANCE,
                com.google.gson.JsonParser.parseString("""
                        { "enter": "none", "exit": "none",
                          "lines": [ { "character": "pvzce:pea_chan", "text": "嗨",
                                       "animation": { "type": "shake", "amount": 2 } } ] }
                        """)).getOrThrow();
        assertFalse(quiet.enter().slides());
        assertFalse(quiet.exit().slides());
        assertTrue(quiet.lines().get(0).animation().isShake());
        assertEquals(2F, quiet.lines().get(0).animation().amount(), 0.0001F);

        // A typo is a value the validator reports and the overlay ignores, not a failed level.
        LevelDialogue typo = LevelDialogue.CODEC.parse(JsonOps.INSTANCE,
                com.google.gson.JsonParser.parseString("""
                        { "enter": "silde", "lines": [ { "character": "pvzce:pea_chan", "text": "嗨" } ] }
                        """)).getOrThrow();
        assertEquals(DialogueEffect.UNKNOWN, typo.enter());
        assertTrue(typo.enter().slides(), "a typo must not silently remove the staging");
        assertEquals(DialogueEffect.UNKNOWN, DialogueEffect.parse(null), "and so is a missing value");
        assertTrue(DialogueEffect.UNKNOWN.slides());
    }

    /** A line written before animations existed keeps its five fields and gains a sixth. */
    @Test
    void aLineWrittenWithoutAnAnimationStillDecodes() {
        DialogueLine line = DialogueLine.CODEC.parse(JsonOps.INSTANCE,
                com.google.gson.JsonParser.parseString("""
                        { "character": "pvzce:pea_chan", "portrait": "smile", "text": "嗨",
                          "voice": "pvzce:sfx/ui/tap", "side": "right" }
                        """)).getOrThrow();
        assertEquals(DialogueAnimation.TYPE_NONE, line.animation().type());
        assertEquals("pvzce:pea_chan", line.character().toString());
        assertEquals(DialogueLine.Side.RIGHT, line.side(), "and the rest of the line is untouched");
        assertTrue(line.slots().isEmpty(), "no stage means just the speaker");
        assertFalse(line.hasChoices(), "and no answers means click to continue");
        assertEquals("", line.speakerName(), "and the name over the bubble is the character's");

        // The five-argument constructor the old callers use still means "no animation".
        DialogueLine shortForm = new DialogueLine(Identifier.withDefaultNamespace("pea_chan"),
                "smile", "嗨", "", DialogueLine.Side.LEFT);
        assertTrue(shortForm.animation().isNone());
    }

    /**
     * The three fields a line gained for a second character and for the player's own answer.
     *
     * <p>{@code slots} is who is on stage, {@code choices} is the question, and {@code speaker_name}
     * is a line nobody in the registry speaks - the player's. Decoded on their own line, since the
     * three answer different questions and a conversation may use any of them.
     */
    @Test
    void theStageAndTheAnswersDecodeFromTheLine() {
        DialogueLine line = DialogueLine.CODEC.parse(JsonOps.INSTANCE,
                com.google.gson.JsonParser.parseString("""
                        { "character": "pvzce:entang", "portrait": "confused", "text": "嗯，你谁？",
                          "side": "left",
                          "slots": [ { "slot": "left", "character": "pvzce:entang" },
                                     { "slot": "right", "character": "pvzce:pea_chan" } ],
                          "choices": [ { "text": "莉安" }, { "text": "路过的人" } ] }
                        """)).getOrThrow();
        assertEquals(2, line.slots().size());
        assertEquals(DialogueSlot.RIGHT, line.slots().get(1).slot());
        assertEquals("pvzce:pea_chan", line.slots().get(1).character().toString());
        assertTrue(line.hasChoices());
        assertEquals("莉安", line.choices().get(0).text());
        assertEquals("", line.choices().get(0).voice(), "an answer without a clip of its own");

        DialogueLine player = DialogueLine.CODEC.parse(JsonOps.INSTANCE,
                com.google.gson.JsonParser.parseString("""
                        { "text": "${user_name}", "speaker_name": "${user_name}" }
                        """)).getOrThrow();
        assertNull(player.character(), "a line with no character is the player's");
        assertEquals("${user_name}", player.speakerName());
        assertTrue(player.slots().isEmpty());

        // All three places a character can stand, spelled as the level files spell them.
        assertEquals(DialogueSlot.CENTER, DialogueSlot.parse("middle"));
        assertEquals(DialogueSlot.CENTER, DialogueSlot.parse("centre"));
        assertTrue(DialogueSlot.CENTER.isCenter() && !DialogueSlot.CENTER.isLeft());

        // A misspelt half of the window decodes into UNKNOWN rather than failing the level; the
        // validator is what names it, and the stage is what skips it.
        DialogueLine typo = DialogueLine.CODEC.parse(JsonOps.INSTANCE,
                com.google.gson.JsonParser.parseString("""
                        { "character": "pvzce:entang", "text": "嗯",
                          "slots": [ { "slot": "midle", "character": "pvzce:pea_chan" } ] }
                        """)).getOrThrow();
        assertEquals(DialogueSlot.UNKNOWN, typo.slots().get(0).slot());
        assertEquals(DialogueSlot.UNKNOWN, DialogueSlot.parse(null), "a missing slot is no slot");
    }
}
