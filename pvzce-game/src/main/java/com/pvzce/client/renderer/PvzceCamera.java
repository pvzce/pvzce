package com.pvzce.client.renderer;

import com.pvzce.common.level.WorldPosition;
import com.pvzce.common.level.SceneBoard;

/**
 * Locked side-view orthographic camera over the original PvZ lawn background.
 *
 * <p>The camera viewport covers the whole scaled 1400x600 background image.
 * World coordinates are board cells: (0,0) is the bottom-left plantable cell
 * and one unit equals one cell. The projection maps the board into the
 * background's 9x5 dirt lawn while house/road remain addressable outside the
 * board (zombies enter from the right road before stepping onto the lawn).
 * Normal stages preserve the background's aspect ratio. Permanent toolbars
 * instead fit the whole background between them, with the grid following its
 * two axes; the world renderer preserves plant and zombie proportions.</p>
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
    private final int bottomInset;
    private final int topInset;
    private SceneBoard scene;
    private String surface = SceneBoard.DEFAULT_SURFACE;
    public PvzceCamera scene(SceneBoard scene, String surface) {
        this.scene = scene; this.surface = surface; return this;
    }
    public float surfaceHeight(float x, float y) { return scene == null ? 0F : scene.elevationAt(surface, x, y); }
    public float screenY(WorldPosition position) { return screenY(position.projectedY()); }

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
        this(screenWidth, screenHeight, columns, rows, geometry, panX, 0);
    }

    /** Leaves permanent HUD space below the stage without covering a plantable cell. */
    public PvzceCamera(int screenWidth, int screenHeight, int columns, int rows,
                       LevelStage.Geometry geometry, float panX, int bottomInset) {
        this(screenWidth, screenHeight, columns, rows, geometry, panX, bottomInset, 0);
    }

    /** Leaves independent toolbar space above and below the entire playable stage. */
    public PvzceCamera(int screenWidth, int screenHeight, int columns, int rows,
                       LevelStage.Geometry geometry, float panX, int bottomInset, int topInset) {
        this.bottomInset = Math.max(0, Math.min(screenHeight - 1, bottomInset));
        this.topInset = Math.max(0, Math.min(screenHeight - this.bottomInset - 1, topInset));
        this.screenWidth = screenWidth;
        this.screenHeight = screenHeight;
        this.columns = Math.max(1, columns);
        this.rows = Math.max(1, rows);
        this.geometry = geometry == null ? LevelStage.YARD : geometry;
        LevelStage.Stage area = this.bottomInset + this.topInset == 0 ? LevelStage.cover(screenWidth, screenHeight)
                : LevelStage.fill(screenWidth, screenHeight - this.bottomInset - this.topInset);
        this.stage = new LevelStage.Stage(area.x(), area.y() + this.bottomInset, area.width(), area.height(), area.scale());
        LevelStage.Board lawn = LevelStage.board(this.columns, this.rows, this.geometry, area);
        this.board = new LevelStage.Board(lawn.x(), lawn.y() + this.bottomInset, lawn.width(), lawn.height(),
                lawn.cellWidth(), lawn.cellHeight(), lawn.fit());
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
        return new PvzceCamera(screenWidth, screenHeight, columns, rows, geometry, panX, bottomInset, topInset).scene(scene, surface);
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
     * <p>Use the vertical scale, as entity art preserves its proportions even when
     * a permanent toolbar stage fills a wider rectangle.
     */
    public float boardScale() {
        return unitY / Math.max(0.0001F, geometry.cellHeight());
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
        return screenY(y + 0.5F + surfaceHeight(x + 0.5F, y + 0.5F));
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
        float x = worldX(mouseX, mouseY), projected = worldY(mouseX, mouseY);
        if (scene == null) return (int) Math.floor(projected);
        int column = (int) Math.floor(x);
        for (int row = 0; row < rows; row++) {
            if (!scene.exists(surface, column, row)) continue;
            float bottom = row + surfaceHeight(x, row);
            float top = row + 1F + surfaceHeight(x, Math.nextDown(row + 1F));
            if (projected >= Math.min(bottom, top) && projected < Math.max(bottom, top)) return row;
        }
        return -1;
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
        int x = cellX(mouseX, mouseY), y = cellY(mouseX, mouseY);
        return x >= 0 && x < columns && y >= 0 && y < rows;

    }

    /** True when a window pixel maps to a cell inside the board. */
    public boolean cellInBoard(double mouseX, double mouseY) {
        return inBoard(mouseX, mouseY);
    }
}
