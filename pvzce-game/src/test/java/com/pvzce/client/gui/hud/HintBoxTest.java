package com.pvzce.client.gui.hud;

import com.pvzce.api.content.LevelHint;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rules the bottom hint box follows: one line at a time, and a refusal outranks a
 * tutorial.
 *
 * <p>Headless on purpose - the box only needs a client for its font and window, and
 * neither is touched by the parts that decide <em>what</em> is on screen. That is exactly
 * the split this test pins: the timing and the precedence are decisions, and the drawing is
 * a consequence of them.
 */
class HintBoxTest {
    @Test
    void aRefusalReplacesATutorialLine() {
        HintBox box = new HintBox(null);
        box.show(persistent("你需要收集阳光种植植物"));
        assertTrue(box.visible(), "the tutorial line should be up");

        box.refuse("冷却中");
        assertTrue(box.currentText().equals("冷却中"),
                "a refusal answers what the player just did: " + box.currentText());
    }

    @Test
    void aTutorialLineDoesNotReplaceALiveRefusal() {
        HintBox box = new HintBox(null);
        box.refuse("阳光不足");

        // The player asked a question and got an answer; a lesson landing on top of it
        // would be answering something else.
        box.show(persistent("点击阳光可以收集"));
        assertTrue(box.currentText().equals("阳光不足"),
                "the refusal must survive a tutorial line: " + box.currentText());
    }

    @Test
    void aTutorialLineReplacesAnotherTutorialLine() {
        HintBox box = new HintBox(null);
        box.show(persistent("第一句"));
        box.show(persistent("第二句"));
        assertTrue(box.currentText().equals("第二句"),
                "a newer lesson is the lesson: " + box.currentText());
    }

    @Test
    void aRefusalIsNotPermanent() {
        // Refusals are repeated: the player may click the same card again, and the box has
        // to be able to say the same thing again from the top.
        HintBox box = new HintBox(null);
        box.refuse("冷却中");
        box.refuse("阳光不足");
        assertTrue(box.currentText().equals("阳光不足"));
    }

    @Test
    void hidingFadesRatherThanCuts() {
        HintBox box = new HintBox(null);
        box.show(persistent("点击阳光可以收集"));
        box.hide();
        // Still up, at a lower opacity: the line is on its way out, not gone in one frame.
        assertTrue(box.visible(), "a hidden line fades out rather than vanishing");
        assertTrue(box.alpha() < 1F, "and it is fading: alpha=" + box.alpha());
        assertFalse(box.currentText().isEmpty());
    }

    @Test
    void anEmptyHintShowsNothing() {
        HintBox box = new HintBox(null);
        box.show(new LevelHint(LevelHint.Trigger.ON_START, Optional.empty(), "   ",
                LevelHint.DEFAULT_DURATION_TICKS));
        assertFalse(box.visible(), "a hint with no text must not bring the box up");
    }

    private static LevelHint persistent(String text) {
        return new LevelHint(LevelHint.Trigger.ON_START, Optional.empty(), text,
                LevelHint.PERSISTENT);
    }
}
