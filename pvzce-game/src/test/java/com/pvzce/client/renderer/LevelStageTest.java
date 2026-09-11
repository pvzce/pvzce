package com.pvzce.client.renderer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Geometry contract for fitting level boards into the original 9x5 dirt lawn. */
class LevelStageTest {
    @Test
    void nineByFiveFillsLawnExactly() {
        LevelStage.Board board = LevelStage.board(1400, 600, 9, 5);
        assertEquals(LevelStage.LAWN_LEFT, board.x(), 0.001F);
        assertEquals(LevelStage.LAWN_WIDTH, board.width(), 0.001F);
        assertEquals(LevelStage.LAWN_HEIGHT, board.height(), 0.001F);
        assertEquals(80F, board.cellWidth(), 0.001F);
        assertEquals(100F, board.cellHeight(), 0.001F);
        assertEquals(1F, board.fit(), 0.0001F);
    }

    @Test
    void sixRowsShrinkAndLeftAlignLeavingRoadSideDirt() {
        LevelStage.Board board = LevelStage.board(1400, 600, 9, 6);
        assertEquals(5F / 6F, board.fit(), 0.0001F);
        assertEquals(LevelStage.LAWN_LEFT, board.x(), 0.001F);
        assertTrue(board.width() < LevelStage.LAWN_WIDTH);
        assertEquals(LevelStage.LAWN_HEIGHT, board.height(), 0.001F);
    }

    @Test
    void fourRowsFillWidthAndLeaveVerticalDirt() {
        LevelStage.Board board = LevelStage.board(1400, 600, 9, 4);
        assertEquals(1F, board.fit(), 0.0001F);
        assertEquals(LevelStage.LAWN_WIDTH, board.width(), 0.001F);
        assertEquals(400F, board.height(), 0.001F);
        assertEquals(70F, board.y(), 0.001F, "vertical leftover is split evenly in the inset dirt region");
    }

    @Test
    void smallBoardUpscalesUntilOneAxisFills() {
        LevelStage.Board board = LevelStage.board(1400, 600, 6, 4);
        assertEquals(1.25F, board.fit(), 0.0001F);
        assertEquals(LevelStage.LAWN_HEIGHT, board.height(), 0.001F);
        assertEquals(6F * (LevelStage.LAWN_WIDTH / 9F) * 1.25F, board.width(), 0.01F);
        assertEquals(LevelStage.LAWN_LEFT, board.x(), 0.001F, "leftover stays on the road side");
    }

    @Test
    void tallBoardFillsHeightAndLeavesRoadSideDirt() {
        LevelStage.Board board = LevelStage.board(1400, 600, 4, 9);
        assertEquals(5F / 9F, board.fit(), 0.0001F);
        assertEquals(LevelStage.LAWN_HEIGHT, board.height(), 0.001F);
        assertTrue(board.width() < LevelStage.LAWN_WIDTH);
        assertEquals(LevelStage.LAWN_LEFT, board.x(), 0.001F);
    }
}
