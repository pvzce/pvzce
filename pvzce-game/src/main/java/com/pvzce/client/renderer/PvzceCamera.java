package com.pvzce.client.renderer;

/**
 * Locked side-view orthographic camera over the original PvZ lawn background.
 *
 * <p>The camera viewport covers the whole scaled 1400x600 background image.
 * World coordinates are board cells: (0,0) is the bottom-left plantable cell
 * and one unit equals one cell. The projection maps the board into the
 * background's 9x5 dirt lawn while house/road remain addressable outside the
 * board (zombies enter from the right road before stepping onto the lawn).
 * Both axes use the board's native 80x100 cell ratio, so a board of any size
 * is only ever scaled uniformly; leftover space is background on the right or
 * above/below.</p>
 */
public final class PvzceCamera {
    private final int screenWidth;
    private final int screenHeight;
    private final int columns;
    private final int rows;
    private final LevelStage.Geometry geometry;
    private final LevelStage.Stage stage;
    private final LevelStage.Board board;
    private final float unitX;
    private final float unitY;
    private final LevelStage.LiquidFrame liquidFrame;
    private final int viewportX;
    private final int viewportY;
    private final int viewportWidth;
    private final int viewportHeight;
    private final float worldLeft;
    private final float worldRight;
    private final float worldBottom;
    private final float worldTop;
    private final Matrix4f projection;
    /**
     * Extra horizontal look, in world cells, toward the house.
     *
     * <p>The camera is otherwise locked: the board always sits in the lawn region of the
     * backdrop. The end of a level is the one moment the game wants to look at the house
     * instead - a zombie reaching it eats, and the original turns to show that - so this is
     * a look-at offset rather than a free camera. Positive values move the view left, which
     * is also the direction the backdrop slides on screen.
     */
    private final float panX;

    public PvzceCamera(int screenWidth, int screenHeight, int columns, int rows) {
        this(screenWidth, screenHeight, columns, rows, LevelStage.YARD, 0F);
    }

    public PvzceCamera(int screenWidth, int screenHeight, int columns, int rows, float panX) {
        this(screenWidth, screenHeight, columns, rows, LevelStage.YARD, panX);
    }

    /**
     * The camera for one level's board, drawn on its own stage.
     *
     * <p>The stage is an argument rather than a constant because the pool's board is not
     * the front lawn's: six 85px lanes instead of five 100px ones, and a water surface the
     * backdrop puts somewhere other than the water cells. See {@link LevelStage.Geometry}.
     */
    public PvzceCamera(int screenWidth, int screenHeight, int columns, int rows,
                       LevelStage.Geometry geometry, float panX) {
        this.screenWidth = screenWidth;
        this.screenHeight = screenHeight;
        this.columns = Math.max(1, columns);
        this.rows = Math.max(1, rows);
        this.geometry = geometry == null ? LevelStage.YARD : geometry;
        this.stage = LevelStage.cover(screenWidth, screenHeight);
        this.board = LevelStage.board(screenWidth, screenHeight, this.columns, this.rows, this.geometry);
        this.unitX = board.cellWidth();
        this.unitY = board.cellHeight();
        this.liquidFrame = this.geometry.liquid();
        this.viewportX = Math.round(stage.x());
        this.viewportY = Math.round(stage.y());
        this.viewportWidth = Math.max(1, Math.round(stage.width()));
        this.viewportHeight = Math.max(1, Math.round(stage.height()));
        this.panX = panX;

        this.worldLeft = (viewportX - board.x()) / unitX - panX;
        this.worldRight = worldLeft + viewportWidth / unitX;
        this.worldBottom = (viewportY - board.y()) / unitY;
        this.worldTop = worldBottom + viewportHeight / unitY;
        this.projection = Matrix4f.ortho(worldLeft, worldRight, worldBottom, worldTop, -10F, 10F);
    }

    /** The stage this board is drawn on. */
    public LevelStage.Geometry geometry() {
        return geometry;
    }

    /**
     * Where this stage's liquid layer goes, in world cells.
     *
     * <p>World cells because that is the space the board is drawn in (the projection maps
     * them), and what {@link com.pvzce.client.renderer.SceneTileRenderer} hands the liquid
     * pass. {@link LevelStage.LiquidFrame#CELL} means "the water cells are the surface",
     * which is every stage but the pool.
     */
    public LevelStage.LiquidFrame liquidFrame() {
        return liquidFrame;
    }

    /** The same camera looking {@code panX} cells further toward the house. */
    public PvzceCamera panned(float panX) {
        return new PvzceCamera(screenWidth, screenHeight, columns, rows, geometry, panX);
    }

    /** How far this camera is looking toward the house, in cells. */
    public float panX() {
        return panX;
    }

    /**
     * How much bigger this board is drawn than the backdrop's own pixels.
     *
     * <p>{@link LevelStage.Geometry}'s numbers are the 1400x600 reference image's pixels: the
     * pool's cell is 80x85 of them. A window is almost never that size, so the board is scaled to
     * fit the stage's plantable area and one world cell becomes 144 screen pixels at 1920x1080.
     * Anything sized in the backdrop's own pixels - the fog's cloud tiles, whose spacing is the
     * original's own - multiplies by this to stay the size the art was drawn at.
     *
     * <p>One number for both axes: the board is scaled uniformly (see {@link LevelStage#board}),
     * so the two can never disagree.
     */
    public float boardScale() {
        return unitX / Math.max(0.0001F, geometry.cellWidth());
    }

    /**
     * The furthest this camera can look toward the house before the backdrop's own left
     * edge would come onto the screen.
     *
     * <p>There is no art beyond the backdrop's edges, so panning past this shows the clear
     * colour where the house should be. Anything that pans (the defeat move) asks this
     * instead of picking a number that happens to look right at one window size.
     */
    public float maxPanX() {
        return Math.max(0F, -stage.x() / unitX);
    }

    public Matrix4f projection() {
        return projection;
    }

    public int viewportX() {
        return viewportX;
    }

    public int viewportY() {
        return viewportY;
    }

    public int viewportWidth() {
        return viewportWidth;
    }

    public int viewportHeight() {
        return viewportHeight;
    }

    /** Horizontal screen pixels per world cell. */
    public float unitX() {
        return unitX;
    }

    /** Vertical screen pixels per world cell. */
    public float unitY() {
        return unitY;
    }

    /** Legacy accessor retained for debug/UI code that only needs one scale. */
    public float unit() {
        return unitX;
    }

    /** World-space rectangle the background stage covers in the current viewport. */
    public float worldLeft() {
        return worldLeft;
    }

    public float worldRight() {
        return worldRight;
    }

    public float worldBottom() {
        return worldBottom;
    }

    public float worldTop() {
        return worldTop;
    }

    public float worldWidth() {
        return worldRight - worldLeft;
    }

    public float worldHeight() {
        return worldTop - worldBottom;
    }

    /** Screen-space (framebuffer pixels, bottom-left origin) x for a world cell x. */
    public float screenX(float worldX) {
        return board.x() + (worldX + panX) * unitX;
    }

    /** Screen-space (framebuffer pixels, bottom-left origin) y for a world cell y. */
    public float screenY(float worldY) {
        return board.y() + worldY * unitY;
    }

    /** Centre of a board cell, including the roof's rise, in framebuffer pixels. */
    public float cellScreenY(int x, int y) {
        return screenY(y + 0.5F + ("roof".equals(geometry.name())
                ? com.pvzce.api.content.SceneElementDef.roofHeightAt(x + 0.5F) : 0F));
    }

    public float worldX(double mouseX, double mouseY) {
        return (float) ((mouseX - board.x()) / unitX) - panX;
    }

    public float worldY(double mouseX, double mouseY) {
        return (float) ((screenHeight - mouseY - board.y()) / unitY);
    }

    /** Returns the board cell for a window pixel, or -1 when outside. */
    public int cellX(double mouseX, double mouseY) {
        return (int) Math.floor(worldX(mouseX, mouseY));
    }

    public int cellY(double mouseX, double mouseY) {
        float height = "roof".equals(geometry.name())
                ? com.pvzce.api.content.SceneElementDef.roofHeightAt(worldX(mouseX, mouseY)) : 0F;
        return (int) Math.floor(worldY(mouseX, mouseY) - height);
    }

    /**
     * True when a window pixel is inside the board rectangle.
     *
     * <p>The board rect is in bottom-up framebuffer pixels (like every other method
     * here, and like {@link #worldY}), but the incoming {@code mouseY} is a raw
     * top-down GLFW cursor position. Comparing them directly accepted the board's
     * mirror image about the screen's vertical centre: at 1920x1080 the bottom
     * ~108px of the lawn - most of the front row - could be neither highlighted nor
     * clicked, and a strip above the board was accepted instead.
     */
    public boolean inBoard(double mouseX, double mouseY) {
        if ("roof".equals(geometry.name())) {
            int x = cellX(mouseX, mouseY), y = cellY(mouseX, mouseY);
            return x >= 0 && x < columns && y >= 0 && y < rows;
        }
        double flippedY = screenHeight - mouseY;
        float left = board.x() + panX * unitX;
        return mouseX >= left && mouseX < left + board.width()
                && flippedY >= board.y() && flippedY < board.y() + board.height();
    }

    /** True when a window pixel maps to a cell inside the board. */
    public boolean cellInBoard(double mouseX, double mouseY) {
        return cellX(mouseX, mouseY) >= 0 && cellY(mouseX, mouseY) >= 0;
    }
}
