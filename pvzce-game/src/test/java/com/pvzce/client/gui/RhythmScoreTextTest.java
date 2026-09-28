package com.pvzce.client.gui;

import com.pvzce.common.network.packet.GameStateS2C;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The run report's two lines: what the page after a rhythm level prints.
 *
 * <p>Pure text, so it is pinned here rather than through a screenshot: the numbers are the server's
 * (see {@code RhythmMechanic.Score}) and the only thing this side decides is how they read.
 */
class RhythmScoreTextTest {
    /** A level with no chart reports nothing, and the screens draw no block at all. */
    @Test
    void aLevelWithoutAChartHasNoLines() {
        assertEquals(List.of(), RhythmScoreText.lines(null));
        assertEquals(List.of(), RhythmScoreText.lines(GameStateS2C.RhythmScore.NONE));
    }

    /** The four verdicts on one line, the streak and the jalapenos on the next. */
    @Test
    void aRunReadsAsItsVerdictsAndItsStreak() {
        List<String> lines = RhythmScoreText.lines(
                new GameStateS2C.RhythmScore(true, 214, 31, 4, 3, 249, 88, 2));
        assertEquals(2, lines.size(), "two lines: what was hit, and how well it was held");
        assertTrue(lines.get(0).contains("PERFECT 214"), lines.get(0));
        assertTrue(lines.get(0).contains("MISS 3"), lines.get(0));
        assertTrue(lines.get(1).contains("249"), lines.get(1));
        assertTrue(lines.get(1).contains("88"), lines.get(1));
        assertTrue(lines.get(1).contains("2"), lines.get(1));
    }

    /** Judged counts every note the run was graded on, which is what the verdicts add up to. */
    @Test
    void judgedIsEveryNoteTheRunWasGradedOn() {
        assertEquals(252, new GameStateS2C.RhythmScore(true, 214, 31, 4, 3, 0, 0, 0).judged());
        assertEquals(0, GameStateS2C.RhythmScore.NONE.judged());
    }
}
