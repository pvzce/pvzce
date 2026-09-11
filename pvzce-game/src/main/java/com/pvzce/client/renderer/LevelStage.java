package com.pvzce.client.renderer;

import com.pvzce.api.util.Identifier;

/**
 * Shared geometry for the original PvZ lawn background.
 *
 * <p>The 1400x600 reference image is divided into three parts: house on the
 * left, a 9x5 dirt lawn in the middle, and the road on the right. The lawn
 * region is the only place a level board may occupy. A board is scaled
 * uniformly (so its cells keep the background's native cell aspect ratio) and
 * exactly one axis fills the lawn; the other axis keeps background visible as
 * bare-dirt margin. The board is anchored to the house side horizontally and
 * centered vertically, so leftover space appears on the right or above/below.
 * The standard 9x5 board fills the dirt region exactly.</p>
 */
public final class LevelStage {
    public static final Identifier BACKGROUND_TEXTURE =
            Identifier.withDefaultNamespace("textures/gui/screen/level/background1unsodded");

    public static final float IMAGE_WIDTH = 1400F;
    public static final float IMAGE_HEIGHT = 600F;

    /** Lawn region in reference-image pixels, measured from the top-left corner. */
    public static final float LAWN_LEFT = 256F;
    /** The playable dirt starts below the decorative upper border. */
    public static final float LAWN_TOP = 80F;
    /** Nine 80px columns; the road-side curb starts at about x=976. */
    public static final float LAWN_WIDTH = 720F;
    /** Five 100px rows; the bright bottom border starts below this. */
    public static final float LAWN_HEIGHT = 500F;

    public static final int BOARD_COLUMNS = 9;
    public static final int BOARD_ROWS = 5;

    private LevelStage() {
    }

    /** The scaled reference image on screen; x/y are the bottom-left corner in screen pixels. */
    public record Stage(float x, float y, float width, float height, float scale) {
        public float imageX(float imageX) {
            return x + imageX * scale;
        }

        /** Converts reference-image Y (top-down) to screen Y (bottom-up). */
        public float imageY(float imageY) {
            return y + (IMAGE_HEIGHT - imageY) * scale;
        }
    }

    /**
     * The screen rectangle the level board occupies. {@code x/y} are the
     * bottom-left corner and {@code cellWidth/cellHeight} are the pixel size
     * of one board cell.
     */
    public record Board(float x, float y, float width, float height,
                        float cellWidth, float cellHeight, float fit) {
    }

    /** Cover-scales the reference image to the screen, preserving aspect ratio. */
    public static Stage cover(int screenWidth, int screenHeight) {
        float scale = Math.max(screenWidth / IMAGE_WIDTH, screenHeight / IMAGE_HEIGHT);
        float width = IMAGE_WIDTH * scale;
        float height = IMAGE_HEIGHT * scale;
        return new Stage((screenWidth - width) / 2F, (screenHeight - height) / 2F, width, height, scale);
    }

    /**
     * Fits a board into the 9x5 lawn area. The board is scaled uniformly so
     * exactly one axis (width or height) fills the lawn; the other axis leaves
     * bare-dirt background visible. Horizontal leftovers stay on the road side,
     * vertical leftovers are split evenly. 9x5 fills both axes.
     */
    public static Board board(int screenWidth, int screenHeight, int columns, int rows) {
        int safeColumns = Math.max(1, columns);
        int safeRows = Math.max(1, rows);
        Stage stage = cover(screenWidth, screenHeight);

        // Contain-fit: one axis fills exactly, the other keeps its natural
        // margin. No per-axis correction is applied, so cells keep the
        // background art's native aspect ratio.
        float fit = Math.min(BOARD_COLUMNS / (float) safeColumns,
                BOARD_ROWS / (float) safeRows);
        float cellWidth = LAWN_WIDTH / BOARD_COLUMNS * fit * stage.scale();
        float cellHeight = LAWN_HEIGHT / BOARD_ROWS * fit * stage.scale();

        float boardWidth = safeColumns * cellWidth;
        float boardHeight = safeRows * cellHeight;

        float lawnScreenX = stage.imageX(LAWN_LEFT);
        float lawnScreenBottom = stage.imageY(LAWN_TOP + LAWN_HEIGHT);
        float boardX = lawnScreenX; // anchor to the house side
        float boardY = lawnScreenBottom + (LAWN_HEIGHT * stage.scale() - boardHeight) / 2F;

        return new Board(boardX, boardY, boardWidth, boardHeight, cellWidth, cellHeight, fit);
    }
}
