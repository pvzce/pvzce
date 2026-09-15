package com.pvzce.client.gui.hud;

import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.LevelHint;
import com.pvzce.api.util.Identifier;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which of a level's hints fires, and when.
 *
 * <p>The level's {@code hints} block is a script, and the properties that matter are about
 * counting: a lesson fires once, an unknown resource's line never fires, and a level that
 * did not opt into refusal lines never shows one. None of that is visible in a screenshot,
 * and all of it is invisible when it is wrong - a hint that fires twice just looks like a
 * hint.
 */
class LevelHintsTest {
    @Test
    void aLessonFiresOnce() {
        HintBox box = box();
        LevelHints hints = new LevelHints(box, def(hint(LevelHint.Trigger.ON_RESOURCE,
                Optional.of(Identifier.parse("pvzce:sun")), "第一颗阳光")));

        hints.onResourceCollected("pvzce:sun");
        assertEquals("第一颗阳光", box.currentText());

        // The second sun must not re-teach: a lesson repeated on every pickup turns the
        // bottom of the screen into a metronome.
        box.clear();
        hints.onResourceCollected("pvzce:sun");
        assertFalse(box.visible(), "the lesson must not fire twice");
    }

    @Test
    void otherResourcesDoNotFireIt() {
        HintBox box = box();
        LevelHints hints = new LevelHints(box, def(hint(LevelHint.Trigger.ON_RESOURCE,
                Optional.of(Identifier.parse("pvzce:sun")), "第一颗阳光")));

        hints.onResourceCollected("pvzce:coin_silver");
        assertFalse(box.visible(), "a coin is not the resource this hint waits for");
    }

    @Test
    void aLevelThatDidNotAskForRefusalsStaysQuiet() {
        HintBox box = box();
        LevelHints hints = new LevelHints(box, def(hint(LevelHint.Trigger.ON_START,
                Optional.empty(), "点击阳光可以收集")));

        hints.onCardRefused("冷却中");
        assertFalse(box.visible(),
                "a sandbox level that wrote no on_card_refused must not be nagged");
    }

    @Test
    void aLevelThatAskedForRefusalsGetsTheBuiltInLine() {
        HintBox box = box();
        LevelHints hints = new LevelHints(box, def(
                new LevelHint(LevelHint.Trigger.ON_CARD_REFUSED, Optional.empty(), "",
                        LevelHint.DEFAULT_DURATION_TICKS)));

        hints.onCardRefused("阳光不足");
        assertEquals("阳光不足", box.currentText());
    }

    @Test
    void onStartShowsOnlyTheFirstOpeningLine() {
        // Two opening lines are a reading order, not a stack: the first one goes up and the
        // second waits for its own trigger rather than being shown over it.
        HintBox box = box();
        LevelHints hints = new LevelHints(box, def(
                hint(LevelHint.Trigger.ON_START, Optional.empty(), "第一句"),
                hint(LevelHint.Trigger.ON_START, Optional.empty(), "第二句")));

        hints.onLevelStart();
        assertEquals("第一句", box.currentText());
    }

    @Test
    void aLevelWithNoHintsSaysSo() {
        assertTrue(new LevelHints(box(), def()).isEmpty());
        assertFalse(new LevelHints(box(), def(hint(LevelHint.Trigger.ON_START,
                Optional.empty(), "x"))).isEmpty());
    }

    // ------------------------------------------------------------------
    // Construction: a LevelDef carrying only the hints this test cares about.
    // ------------------------------------------------------------------

    private static HintBox box() {
        return new HintBox(null);
    }

    private static LevelHint hint(LevelHint.Trigger trigger, Optional<Identifier> resource,
                                  String text) {
        return new LevelHint(trigger, resource, text, LevelHint.PERSISTENT);
    }

    private static LevelDef def(LevelHint... hints) {
        return new LevelDef(Identifier.parse("pvzce:test/level"), "t", "", 9, 1, java.util.Map.of(),
                List.of(), Identifier.parse("pvzce:plant_team"), java.util.Map.of(), java.util.Map.of(),
                List.of(), 1F, List.of(), java.util.Map.of(), 50, LevelDef.LevelMusicDef.DEFAULT,
                List.of(), LevelDef.UNSET_MAX_SEED_SLOTS, com.pvzce.api.content.LevelRewards.DEFAULT,
                com.pvzce.api.content.LevelUnlock.NONE, List.<com.pvzce.api.content.mechanic.TypedMechanic>of(),
                com.pvzce.api.content.LevelDialogue.EMPTY, List.of(hints));
    }
}
