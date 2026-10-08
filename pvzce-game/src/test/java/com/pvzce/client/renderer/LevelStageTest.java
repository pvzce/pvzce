package com.pvzce.client.renderer;

import com.pvzce.api.util.Identifier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Geometry contract for fitting level boards into the original 1400x600 stages. */
class LevelStageTest {
    @Test
    void permanentWorkshopLeavesEveryLawnCellVisibleAndClickable() {
        var camera = new PvzceCamera(1920, 1080, 9, 5, LevelStage.YARD, 0F, 336);
        assertTrue(camera.screenY(0F) >= 336);
        assertTrue(camera.screenY(5F) <= 1080);
        for (int x = 0; x < 9; x++) for (int y = 0; y < 5; y++) {
            float px = camera.screenX(x + 0.5F), py = 1080 - camera.cellScreenY(x, y);
            assertTrue(px >= 0 && px <= 1920);
            assertTrue(camera.inBoard(px, py));
            assertEquals(x, camera.cellX(px, py));
            assertEquals(y, camera.cellY(px, py));
        }
        assertEquals(camera.screenY(0F), camera.panned(1F).screenY(0F));
    }
    @Test
    void roofPickingReachesEveryCellIncludingTheRaisedTopRow() {
        var camera = new PvzceCamera(1920, 1080, 9, 5, LevelStage.ROOF, 0F);
        for (int x = 0; x < 9; x++) for (int y = 0; y < 5; y++) {
            float px = camera.screenX(x + 0.5F), py = 1080 - camera.cellScreenY(x, y);
            assertTrue(camera.inBoard(px, py));
            assertEquals(x, camera.cellX(px, py));
            assertEquals(y, camera.cellY(px, py));
        }
    }
    /** The front lawn's own numbers, quoted from the docs rather than read twice. */
    private static final float LAWN_LEFT = 256F;
    private static final float LAWN_WIDTH = 720F;
    private static final float LAWN_HEIGHT = 500F;

    @Test
    void nineByFiveFillsLawnExactly() {
        LevelStage.Board board = LevelStage.board(1400, 600, 9, 5);
        assertEquals(LAWN_LEFT, board.x(), 0.001F);
        assertEquals(LAWN_WIDTH, board.width(), 0.001F);
        assertEquals(LAWN_HEIGHT, board.height(), 0.001F);
        assertEquals(80F, board.cellWidth(), 0.001F);
        assertEquals(100F, board.cellHeight(), 0.001F);
        assertEquals(1F, board.fit(), 0.0001F);
    }

    @Test
    void sixRowsShrinkAndLeftAlignLeavingRoadSideDirt() {
        LevelStage.Board board = LevelStage.board(1400, 600, 9, 6);
        assertEquals(5F / 6F, board.fit(), 0.0001F);
        assertEquals(LAWN_LEFT, board.x(), 0.001F);
        assertTrue(board.width() < LAWN_WIDTH);
        assertEquals(LAWN_HEIGHT, board.height(), 0.001F);
    }

    @Test
    void fourRowsFillWidthAndLeaveVerticalDirt() {
        LevelStage.Board board = LevelStage.board(1400, 600, 9, 4);
        assertEquals(1F, board.fit(), 0.0001F);
        assertEquals(LAWN_WIDTH, board.width(), 0.001F);
        assertEquals(400F, board.height(), 0.001F);
        assertEquals(70F, board.y(), 0.001F, "vertical leftover is split evenly in the inset dirt region");
    }

    @Test
    void smallBoardUpscalesUntilOneAxisFills() {
        LevelStage.Board board = LevelStage.board(1400, 600, 6, 4);
        assertEquals(1.25F, board.fit(), 0.0001F);
        assertEquals(LAWN_HEIGHT, board.height(), 0.001F);
        assertEquals(6F * 80F * 1.25F, board.width(), 0.01F);
        assertEquals(LAWN_LEFT, board.x(), 0.001F, "leftover stays on the road side");
    }

    @Test
    void tallBoardFillsHeightAndLeavesRoadSideDirt() {
        LevelStage.Board board = LevelStage.board(1400, 600, 4, 9);
        assertEquals(5F / 9F, board.fit(), 0.0001F);
        assertEquals(LAWN_HEIGHT, board.height(), 0.001F);
        assertTrue(board.width() < LAWN_WIDTH);
        assertEquals(LAWN_LEFT, board.x(), 0.001F);
    }

    /**
     * The pool's board is the original's backyard grid, not the front lawn's.
     *
     * <p>Six 85px lanes - the measured row positions of the original's backyard are y=80,
     * 165, 250, 335, 420, 505 - so a 9x6 board fills the pool stage exactly instead of
     * being shrunk into a lawn area built for five 100px rows.
     */
    @Test
    void poolBoardUsesTheBackyardsOwnSixLanes() {
        LevelStage.Board board = LevelStage.board(1400, 600, 9, 6, LevelStage.POOL);
        assertEquals(1F, board.fit(), 0.0001F);
        assertEquals(LAWN_LEFT, board.x(), 0.001F);
        assertEquals(720F, board.width(), 0.001F);
        assertEquals(510F, board.height(), 0.001F);
        assertEquals(80F, board.cellWidth(), 0.001F);
        assertEquals(85F, board.cellHeight(), 0.001F);
        // The board ends at image y=590, so its bottom edge sits 10px above the canvas.
        assertEquals(10F, board.y(), 0.001F);
    }

    /**
     * The pool's water is drawn on the backdrop's basin, not on its own water cells.
     *
     * <p>"background3" leaves a flat place-holder at x 253..961, y 295..433; the two water
     * rows are rows 2 and 3, which span y 250..420 in the board's own pixels. The stage's
     * liquid frame is what moves one onto the other, so this test is the contract: rows
     * 2..4 in the frame must land on the basin, in the reference image's own coordinates.
     */
    @Test
    void poolWaterLandsOnTheBackdropsBasin() {
        LevelStage.Board board = LevelStage.board(1400, 600, 9, 6, LevelStage.POOL);
        LevelStage.LiquidFrame frame = LevelStage.POOL.liquid();

        assertEquals(253F, screenToImageX(board, frame.x(0F)), 1F);
        assertEquals(961F, screenToImageX(board, frame.x(9F)), 1F);
        assertEquals(433F, screenToImageY(board, frame.y(2F)), 1F);
        assertEquals(295F, screenToImageY(board, frame.y(4F)), 1F);

        // And the water rows in the plain cell grid, which the basin does NOT follow -
        // the whole reason the frame exists.
        assertEquals(420F, screenToImageY(board, 2F), 1F);
        assertEquals(250F, screenToImageY(board, 4F), 1F);
        assertTrue(frame.shifted());
        assertFalse(LevelStage.YARD.liquid().shifted());
    }

    @Test
    void backdropSelectsTheStage() {
        assertSame(LevelStage.POOL, LevelStage.geometryFor(Identifier.parse("pvzce:textures/gui/screen/level/background3")));
        assertSame(LevelStage.POOL, LevelStage.geometryFor(Identifier.parse("pvzce:textures/gui/screen/level/background4")));
        assertSame(LevelStage.YARD, LevelStage.geometryFor(Identifier.parse("pvzce:textures/gui/screen/level/background1")));
        assertSame(LevelStage.YARD, LevelStage.geometryFor((Identifier) null));
    }

    /** Screen x (bottom-up pixels) back to reference-image x (top-down pixels). */
    private static float screenToImageX(LevelStage.Board board, float worldX) {
        return board.x() + worldX * board.cellWidth();
    }

    private static float screenToImageY(LevelStage.Board board, float worldY) {
        return 600F - (board.y() + worldY * board.cellHeight());
    }
}
