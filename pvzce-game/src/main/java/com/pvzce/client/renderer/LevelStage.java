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

    /** The front lawn's geometry, which every backdrop that is not a pool uses. */
    public static final Geometry YARD = new Geometry("yard",
            256F, 80F, 720F, 500F, 80F, 100F, LiquidFrame.CELL);

    /**
     * The pool's geometry: the original's backyard board.
     *
     * <p>Six lanes of 85px instead of five of 100 - the original's own numbers, from the
     * measured plant/zombie row positions of the backyard (rows at y=80, 165, 250, 335,
     * 420, 505). The board keeps the front lawn's top edge (y=80) and ends at y=590, which
     * is where "background3" stops drawing grass.
     *
     * <p>Its water is drawn lower than its own two water rows, because the backdrop's
     * basin is: "background3"/"background4" leave a flat place-holder rectangle at
     * x 253..961, y 295..433, while the two water rows span y 250..420. The original draws
     * the pool surface as its own sprite there - the zombie's feet land at y=300 and y=385,
     * inside the basin - so the liquid pass gets its own frame in world cells rather than
     * being tied to the cell grid. See {@link LiquidFrame}.
     *
     * <p>The frame's numbers are that rectangle converted into the board's own units, where
     * world y=0 is the BOTTOM row and one cell is 85px: the basin's y 433 is world
     * (590-433)/85 = 1.847 and 295 is 3.471, so two 69px lanes start at
     * 1.847 - 2*(138/170) = 0.2235 and are 138/170 = 0.8118 cells tall each; x 253..961 is
     * world -0.0375..8.8125, so cells are 708/720 = 0.9833 wide.
     */
    public static final Geometry POOL = new Geometry("pool",
            256F, 80F, 720F, 510F, 80F, 85F, new LiquidFrame(-0.0375F, 0.2235294F, 0.9833333F, 0.8117647F));

    public static final int BOARD_COLUMNS = 9;
    public static final int BOARD_ROWS = 5;

    private LevelStage() {
    }

    /**
     * Where a board's liquid layer goes, in world cells.
     *
     * <p>A cell of water is normally its own cell: origin 0, size 1x1, and the surface is
     * exactly the union of the water cells. That is what {@link #CELL} says and what every
     * stage said before the pool existed.
     *
     * <p>The pool is the exception, and the reason this type exists at all. Its backdrop
     * paints the basin at a place that is not its own lane band, so a stage whose
     * place-holder rectangle sits elsewhere declares the frame the water grid is drawn in:
     * water cell {@code (x, y)} is drawn at {@code origin + (x, y) * cell}. The frame is an
     * affine map, so cells stay uniform and the shader is untouched - and because ripples
     * live in the same space, {@link #x(float)} / {@link #y(float)} is what maps a splash at
     * a zombie's feet onto the surface it disturbs.
     */
    public record LiquidFrame(float originX, float originY, float cellWidth, float cellHeight) {
        /** The ordinary case: liquid cells are board cells. */
        public static final LiquidFrame CELL = new LiquidFrame(0F, 0F, 1F, 1F);

        public LiquidFrame {
            cellWidth = cellWidth == 0F ? 1F : cellWidth;
            cellHeight = cellHeight == 0F ? 1F : cellHeight;
        }

        /** True when this frame moves the surface away from the cell grid. */
        public boolean shifted() {
            return originX != 0F || originY != 0F || cellWidth != 1F || cellHeight != 1F;
        }

        /** Where a grid coordinate lands on the surface, in world cells. */
        public float x(float gridX) {
            return originX + gridX * cellWidth;
        }

        public float y(float gridY) {
            return originY + gridY * cellHeight;
        }

        /** The same frame expressed in the pixels of a board drawn at {@code board}'s scale. */
        public LiquidFrame scaledTo(Board board) {
            return new LiquidFrame(
                    board.x() + originX * board.cellWidth(),
                    board.y() + originY * board.cellHeight(),
                    cellWidth * board.cellWidth(),
                    cellHeight * board.cellHeight());
        }

        /** The same frame in the pixels of a board drawn at an arbitrary cell size. */
        public LiquidFrame scaledTo(float boardX, float boardY, float boardCellWidth, float boardCellHeight) {
            return new LiquidFrame(
                    boardX + originX * boardCellWidth,
                    boardY + originY * boardCellHeight,
                    cellWidth * boardCellWidth,
                    cellHeight * boardCellHeight);
        }
    }

    /**
     * One backdrop's board geometry, in reference-image pixels.
     *
     * <p>The original draws every stage on the same 1400x600 canvas, but not with the same
     * grid: the front lawn is 9x5 cells of 80x100, the backyard is 9x6 of 80x85, and the
     * roof is 9x5 of 80x85 (unused so far). A backdrop therefore selects a geometry, and
     * {@link #geometryFor} is the one place that decision is made.
     *
     * @param name       the stage's name, for diagnostics and tests
     * @param lawnLeft   left edge of the plantable region, in reference-image pixels
     * @param lawnTop    top edge of the plantable region; the board's first row starts here
     * @param lawnWidth  how wide the plantable region is
     * @param lawnHeight how tall it is - 9x6 pool cells are 510px, 9x5 lawn cells 500px
     * @param cellWidth  one cell's width in the backdrop's own pixels
     * @param cellHeight one cell's height
     * @param liquid     where this stage's liquid layer goes; see {@link LiquidFrame}
     */
    public record Geometry(String name, float lawnLeft, float lawnTop, float lawnWidth,
                           float lawnHeight, float cellWidth, float cellHeight, LiquidFrame liquid) {
        public Geometry {
            liquid = liquid == null ? LiquidFrame.CELL : liquid;
        }
    }

    /**
     * Which geometry a backdrop is drawn with.
     *
     * <p>Keyed by the backdrop file name rather than by a level field on purpose: the
     * geometry is a property of the picture (the place-holder rectangle the water goes in,
     * the bands the lawn is drawn in), and a level already names its picture. A backdrop
     * this build does not know - every mod's, and the built-in yard default - gets the front
     * lawn's geometry, which is what a backdrop-less level has always been drawn with.
     */
    public static Geometry geometryFor(Identifier background) {
        if (background == null) {
            return YARD;
        }
        String path = background.path();
        if (path.contains("background3") || path.contains("background4")) {
            return POOL;
        }
        return YARD;
    }

    /** The geometry of a level's backdrop, given the texture id the level carries. */
    public static Geometry geometryFor(java.util.Optional<Identifier> background) {
        return background == null ? YARD : geometryFor(background.orElse(null));
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

    /**
     * Fits a board into the front lawn's 9x5 area; see
     * {@link #board(int, int, int, int, Geometry)}.
     */
    public static Board board(int screenWidth, int screenHeight, int columns, int rows) {
        return board(screenWidth, screenHeight, columns, rows, YARD);
    }

    /**
     * Fits a board into a stage's plantable area. The board is scaled uniformly so
     * exactly one axis (width or height) fills that area; the other axis leaves
     * bare-dirt background visible. Horizontal leftovers stay on the road side,
     * vertical leftovers are split evenly. A board of the stage's own size fills
     * both axes - 9x5 on the front lawn, 9x6 in the pool.
     */
    public static Board board(int screenWidth, int screenHeight, int columns, int rows,
                              Geometry geometry) {
        int safeColumns = Math.max(1, columns);
        int safeRows = Math.max(1, rows);
        Geometry stage = geometry == null ? YARD : geometry;
        Stage cover = cover(screenWidth, screenHeight);

        // Contain-fit: one axis fills exactly, the other keeps its natural
        // margin. No per-axis correction is applied, so cells keep the
        // background art's native aspect ratio.
        float fit = Math.min(stage.lawnWidth() / (safeColumns * stage.cellWidth()),
                stage.lawnHeight() / (safeRows * stage.cellHeight()));
        float cellWidth = stage.cellWidth() * fit * cover.scale();
        float cellHeight = stage.cellHeight() * fit * cover.scale();

        float boardWidth = safeColumns * cellWidth;
        float boardHeight = safeRows * cellHeight;

        float lawnScreenX = cover.imageX(stage.lawnLeft());
        float lawnScreenBottom = cover.imageY(stage.lawnTop() + stage.lawnHeight());
        float boardX = lawnScreenX; // anchor to the house side
        float boardY = lawnScreenBottom + (stage.lawnHeight() * cover.scale() - boardHeight) / 2F;

        return new Board(boardX, boardY, boardWidth, boardHeight, cellWidth, cellHeight, fit);
    }

    /** Cover-scales the reference image to the screen, preserving aspect ratio. */
    public static Stage cover(int screenWidth, int screenHeight) {
        float scale = Math.max(screenWidth / IMAGE_WIDTH, screenHeight / IMAGE_HEIGHT);
        float width = IMAGE_WIDTH * scale;
        float height = IMAGE_HEIGHT * scale;
        return new Stage((screenWidth - width) / 2F, (screenHeight - height) / 2F, width, height, scale);
    }
}
