package com.pvzce.client.renderer;

import com.pvzce.api.content.SurfaceProfile;
import com.pvzce.common.level.SceneBoard;
import com.pvzce.api.util.Identifier;
import com.pvzce.client.PvzceClient;
import com.pvzce.client.SceneShifts;
import com.pvzce.client.SceneVisibility;
import com.pvzce.client.renderer.liquid.LiquidTextures;
import com.pvzce.common.util.MathUtil;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders scene elements as repeated 6x6-cell texture layers.
 *
 * <p>The board is split into 6x6 blocks anchored at (0,0). A block fully
 * covered by one element is drawn as one full 6x6 texture; mixed blocks draw
 * each occupied cell as the corresponding 1/36 UV crop of that element's
 * 6x6 texture. Layers are drawn in element encounter order, so a cell
 * replaced by another element hides the layer below it at exactly that cell
 * (matching the layer/mask model used by the level editor). Non-grass/ground
 * elements keep their old 1x1 textures for now.</p>
 *
 * <p>Every scene crop fills exactly one board cell ({@link #cellQuad}), stretched
 * if the source art's cell aspect differs. A board cell is 80x100 pixels, so a
 * square atlas cell is stretched vertically; keeping the art square instead would
 * make the drawn pattern wider than the grid and desynchronise it from the
 * placement highlight and the hit test.</p>
 */
public final class SceneTileRenderer {
    public static final int TILE_CELLS = 6;

    /**
     * The board's terrain, one cell at a time.
     *
     * <p>{@code null} means "draw nothing here" - a cell whose element the level hides - which
     * is why this is not simply a texture lookup per cell.
     */
    @FunctionalInterface
    public interface SceneSource {
        String sceneAt(int x, int y);
    }

    /**
     * Where a cell's element is out of place right now, for the renderer.
     *
     * <p>Supplied by the caller rather than read from a level: the editor canvas and the seed
     * chooser draw boards that nothing is happening to, and {@link #NONE} is what they pass.
     */
    @FunctionalInterface
    public interface SceneShiftSource {
        /** The shift at a cell, or {@code null} when the element is drawn in place. */
        SceneShifts.Shift at(int x, int y);

        /** For a board that is not moving. */
        SceneShiftSource NONE = (x, y) -> null;
    }

    private SceneTileRenderer() {
    }

    public static void render(PvzceClient client, int width, int height, SceneSource scene,
                              float xScale, float grassMargin) {
        render(client, width, height, scene, xScale, grassMargin, SceneShiftSource.NONE,
                SceneVisibility.NONE, LevelStage.LiquidFrame.CELL);
    }

    public static void render(PvzceClient client, int width, int height, SceneSource scene,
                              float xScale, float grassMargin, SceneShiftSource shifts) {
        render(client, width, height, scene, xScale, grassMargin, shifts, SceneVisibility.NONE,
                LevelStage.LiquidFrame.CELL);
    }

    public static void render(PvzceClient client, int width, int height, SceneSource scene,
                              float xScale, float grassMargin, SceneShiftSource shifts,
                              SceneVisibility visibility) {
        render(client, width, height, scene, xScale, grassMargin, shifts, visibility,
                LevelStage.LiquidFrame.CELL);
    }

    /**
     * Draws the board, with the stage's own frame for its liquid layer.
     *
     * <p>{@code liquidFrame} is in world cells and moves the surface away from the cell
     * grid; {@link LevelStage.LiquidFrame#CELL} is "the water cells are the surface", which
     * is every stage but the pool. See {@code LevelStage.POOL}.
     */
    public static void render(PvzceClient client, int width, int height, SceneSource scene,
                              float xScale, float grassMargin, SceneShiftSource shifts,
                              SceneVisibility visibility, LevelStage.LiquidFrame liquidFrame) {
        float scaleX = Math.max(0.0001F, xScale);
        float margin = Math.max(0F, grassMargin);
        if (margin > 0F) {
            renderGrassMargin(client, width, height, margin);
        }
        Map<String, List<Cell>> layers = collectLayers(width, height, scene, shifts, visibility);

        int blocksX = MathUtil.ceilDiv(Math.max(1, width), TILE_CELLS);
        int blocksY = MathUtil.ceilDiv(Math.max(1, height), TILE_CELLS);

        for (Map.Entry<String, List<Cell>> layer : layers.entrySet()) {
            String sceneId = layer.getKey();
            // A liquid layer is not drawn tile by tile at all: it is one batched
            // pass that needs the whole body at once, so it is pulled out here and
            // drawn in the layer's own position. See renderLiquidLayer.
            if (renderLiquidLayer(client, sceneId, scene, width, height, liquidFrame)) {
                continue;
            }
            boolean tiled = isTiled(sceneId);
            Identifier texture = textureFor(client, sceneId);

            Map<Integer, List<Cell>> cellsByBlock = new LinkedHashMap<>();
            for (Cell cell : layer.getValue()) {
                int blockX = Math.floorDiv(cell.x(), TILE_CELLS);
                int blockY = Math.floorDiv(cell.y(), TILE_CELLS);
                cellsByBlock.computeIfAbsent(blockY * blocksX + blockX, ignored -> new ArrayList<>()).add(cell);
            }

            for (int blockY = 0; blockY < blocksY; blockY++) {
                for (int blockX = 0; blockX < blocksX; blockX++) {
                    int visible = blockVisibleCells(width, height, blockX, blockY);
                    if (visible <= 0) {
                        continue;
                    }
                    List<Cell> cells = cellsByBlock.get(blockY * blocksX + blockX);
                    boolean fullBlock = (blockX + 1) * TILE_CELLS <= width
                            && (blockY + 1) * TILE_CELLS <= height;
                    if (tiled && fullBlock && cells != null && cells.size() == TILE_CELLS * TILE_CELLS) {
                        // One 6x6 texture over six cells: exactly the same mapping as
                        // six per-cell crops. The old "uniform" version drew this 7.5
                        // cells wide and offset by -0.75, so a plain lawn (fast path)
                        // and a mixed lawn (per-cell path) rendered 0.625 cells apart.
                        CellQuad quad = blockQuad(blockX, blockY);
                        client.drawTexture(texture, quad.x(), quad.y(), quad.width(), quad.height(),
                                0F, 1F, 1F, 1F, 1F);
                    } else if (cells != null) {
                        for (Cell cell : cells) {
                            drawCell(client, sceneId, texture, cell.x(), cell.y(), visibility);
                        }
                    }
                }
            }
        }
        drawShiftedCells(client, width, height, scene, shifts);
    }

    /**
     * Paints every element that is not in its cell, over the finished board.
     *
     * <p>Last, so a tombstone coming up through the tile it is replacing covers it - and the
     * tile is still there underneath, which is what {@link #collectLayers} put down for it.
     */
    private static void drawShiftedCells(PvzceClient client, int width, int height,
                                        SceneSource scene, SceneShiftSource shifts) {
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                SceneShifts.Shift shift = shifts.at(x, y);
                if (shift != null) {
                    drawShiftedCell(client, scene.sceneAt(x, y), x, y, shift.sink());
                }
            }
        }
    }

    /**
     * Draws one element that is out of its cell.
     *
     * <p>The tile's top slice, standing on the cell's bottom edge: that is the same picture as
     * the whole tile drawn {@code sink} cells lower with everything under the lawn cut off, and
     * it needs neither a clip rectangle nor the camera one would have to be built from. A
     * tombstone coming up is a large {@code sink} shrinking to nothing; one being eaten from the
     * top down is the same number growing.
     */
    private static void drawShiftedCell(PvzceClient client, String sceneId, int cellX, int cellY,
                                        float sink) {
        float shown = Math.max(0.01F, Math.min(1F, 1F - sink));
        Identifier texture = textureFor(client, sceneId);
        if (isTiled(sceneId)) {
            float step = 1F / TILE_CELLS;
            float v1 = (Math.floorMod(cellY, TILE_CELLS) + 1) * step;
            client.drawTextureRegion(texture, 0F, v1 - shown * step, 1F, v1,
                    cellX, cellY, 1F, shown, 0.05F, 1F, 1F, 1F, 1F);
            return;
        }
        client.drawTextureRegion(texture, 0F, 1F - shown, 1F, 1F,
                cellX, cellY, 1F, shown, 0.05F, 1F, 1F, 1F, 1F);
    }

    /**
     * Draws one scene layer as a liquid body when its element is one.
     *
     * <p>The test reads the element definition rather than a texture lookup, so a
     * liquid is decided by content - the {@code liquid} field of the scene element -
     * and not by which file happens to exist. Returns false for a normal layer,
     * which the caller then draws cell by cell as before.
     */
    private static boolean renderLiquidLayer(PvzceClient client, String sceneId, SceneSource scene,
                                             int width, int height, LevelStage.LiquidFrame liquidFrame) {
        var liquid = LiquidTextures.liquidFor(sceneId);
        if (liquid.isEmpty()) {
            return false;
        }
        LiquidTextures.renderWorld(client, liquid.get(), sceneId, width, height, scene, liquidFrame);
        return true;
    }

    /**
     * Renders a board directly into a GUI/screen rectangle. Used by the seed
     * chooser so the lawn is already visible behind the camera pan, before
     * {@code LevelInitS2C} switches to the real gameplay screen.
     *
     * <p>Liquid cells go through the SAME liquid pass as the in-game board, in one
     * batch per liquid rather than one drawing call per cell. The batch carries the
     * cell coordinate in its vertex attribute, so drawing in GUI pixels instead of
     * world units changes nothing about the pattern - which is why one implementation
     * can serve both. Skipping this is what left the chooser showing the old
     * per-cell placeholder texture next to a shader-rendered board in play.
     */
    public static void renderBoard(PvzceClient client, int width, int height, SceneSource scene,
                                   float originX, float originY, float cellWidth, float cellHeight) {
        renderBoard(client, width, height, scene, originX, originY, cellWidth, cellHeight,
                SceneVisibility.NONE, LevelStage.LiquidFrame.CELL);
    }

    public static void renderBoard(PvzceClient client, int width, int height, SceneSource scene,
                                   float originX, float originY, float cellWidth, float cellHeight,
                                   SceneVisibility visibility) {
        renderBoard(client, width, height, scene, originX, originY, cellWidth, cellHeight,
                visibility, LevelStage.LiquidFrame.CELL);
    }

    /**
     * Draws a board into a GUI rectangle, on the stage the level will be played on.
     *
     * <p>{@code liquidFrame} is the stage's frame in <strong>world cells</strong> - the same
     * value {@code PvzceCamera.liquidFrame()} hands the in-game board - because
     * {@link LiquidTextures#renderGuiBoard} is where it is scaled onto the GUI pixels this
     * method is drawing into. A caller that scales it first gets a squared frame and no
     * visible water at all (see {@link LiquidTextures#renderGuiBoard}'s own note).
     */
    public static void renderBoard(PvzceClient client, int width, int height, SceneSource scene,
                                   float originX, float originY, float cellWidth, float cellHeight,
                                   SceneVisibility visibility, LevelStage.LiquidFrame liquidFrame) {
        // Water goes under standing objects, including in the seed chooser/editor.
        java.util.Set<String> liquids = new java.util.LinkedHashSet<>();
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            String id = LiquidTextures.liquidSceneId(scene.sceneAt(x, y));
            if (id != null && !visibility.hides(id)) liquids.add(id);
        }
        for (String id : liquids) LiquidTextures.renderGuiBoard(client,
                LiquidTextures.liquidFor(id).orElseThrow(), id, width, height, scene,
                originX, originY, cellWidth, cellHeight, RenderSystem.currentProjection(), liquidFrame);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                String sceneId = scene.sceneAt(x, y);
                if (sceneId == null || visibility.hides(sceneId)) {
                    continue;
                }
                if (isLiquid(sceneId)) {
                    continue;
                }
                Identifier texture = textureFor(client, sceneId);
                // The same size and underlay rules as the in-game board: a crater is a decal
                // here too, and a preview that squashed one into its cell would be showing a
                // board the player never gets.
                float[] art = EntityTextures.sceneSize(sceneId);
                float drawW = art[0] * cellWidth;
                float drawH = art[1] * cellHeight;
                float drawX = originX + (x + 0.5F) * cellWidth - drawW / 2F;
                float drawY = originY + (y + 0.5F) * cellHeight - drawH / 2F;
                Identifier underlay = EntityTextures.sceneUnderlay(sceneId);
                if (underlay != null && !isLiquid(underlay.toString()) && !visibility.hides(underlay.toString())) {
                    client.drawTexture(textureFor(client, underlay.toString()),
                            originX + x * cellWidth, originY + y * cellHeight,
                            cellWidth, cellHeight, 0F, 1F, 1F, 1F, 1F);
                }
                if (isTiled(sceneId)) {
                    // Same cell-filling rule as the in-game board, so the chooser's
                    // preview and the real lawn cannot disagree.
                    int tx = Math.floorMod(x, TILE_CELLS);
                    int ty = Math.floorMod(y, TILE_CELLS);
                    float step = 1F / TILE_CELLS;
                    client.drawTextureRegion(texture, tx * step, ty * step, (tx + 1) * step, (ty + 1) * step,
                            originX + x * cellWidth, originY + y * cellHeight, cellWidth, cellHeight,
                            0F, 1F, 1F, 1F, 1F);
                } else {
                    client.drawTexture(texture, drawX, drawY, drawW, drawH, 0F, 1F, 1F, 1F, 1F);
                }
            }
        }
    }

    /** True when a scene element is a liquid and therefore not a flat tile. */
    private static boolean isLiquid(String sceneId) {
        return LiquidTextures.liquidFor(sceneId).isPresent();
    }

    /** Paints a small grass ring outside the playable board so edge tiles/actors do not look cut off. */
    private static void renderGrassMargin(PvzceClient client, int width, int height, float margin) {
        Identifier texture = textureFor(client, "pvzce:grass");
        int minX = (int) Math.floor(-margin);
        int maxX = (int) Math.ceil(width + margin) - 1;
        int minY = (int) Math.floor(-margin);
        int maxY = (int) Math.ceil(height + margin) - 1;
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                if (x >= 0 && x < width && y >= 0 && y < height) {
                    continue;
                }
                drawCell(client, "pvzce:grass", texture, x, y, SceneVisibility.NONE);
            }
        }
    }

    private static Map<String, List<Cell>> collectLayers(int width, int height, SceneSource scene,
                                                        SceneShiftSource shifts,
                                                        SceneVisibility visibility) {
        Map<String, List<Cell>> layers = new LinkedHashMap<>();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                SceneShifts.Shift shift = shifts.at(x, y);
                // A cell that is coming up out of the lawn contributes what it is *replacing*
                // to this pass: the new element is painted over it by drawShiftedCells once
                // the rest of the board is down.
                String sceneId = shift != null ? shift.under() : scene.sceneAt(x, y);
                if (sceneId == null || visibility.hides(sceneId)) {
                    // Hidden by the level: the backdrop is the picture in this cell. See
                    // SceneVisibility - a level whose backdrop already has the lawn says so
                    // with `hidden_scene_elements`.
                    continue;
                }
                String liquid = LiquidTextures.liquidSceneId(sceneId);
                if (liquid != null && !liquid.equals(sceneId) && !visibility.hides(liquid))
                    layers.computeIfAbsent(liquid, ignored -> new ArrayList<>()).add(new Cell(x, y));
                layers.computeIfAbsent(sceneId, ignored -> new ArrayList<>()).add(new Cell(x, y));
            }
        }
        return layers;
    }

    private static void drawCell(PvzceClient client, String sceneId, Identifier texture, int x, int y,
                                 SceneVisibility visibility) {
        CellQuad quad = artQuad(sceneId, x, y);
        if (isTiled(sceneId)) {
            int tx = Math.floorMod(x, TILE_CELLS);
            int ty = Math.floorMod(y, TILE_CELLS);
            float step = 1F / TILE_CELLS;
            client.drawTextureRegion(texture, tx * step, ty * step, (tx + 1) * step, (ty + 1) * step,
                    quad.x(), quad.y(), quad.width(), quad.height(), 0.05F, 1F, 1F, 1F, 1F);
        } else {
            drawUnderlay(client, sceneId, x, y, visibility);
            // Pre-6x6 elements: a full 1x1 texture still lives at textures/scene/<path>.
            client.drawTexture(texture, quad.x(), quad.y(), quad.width(), quad.height(),
                    0.05F, 1F, 1F, 1F, 1F);
        }
    }

    /**
     * Draws the tile an element sits on, if it has one and the level draws it.
     *
     * <p>Skipping a hidden underlay is what keeps a hidden lawn hidden: a level that does not
     * draw its grass does not want it back under every tombstone and crater either.
     */
    private static void drawUnderlay(PvzceClient client, String sceneId, int x, int y,
                                     SceneVisibility visibility) {
        Identifier underlay = EntityTextures.sceneUnderlay(sceneId);
        if (underlay == null || isLiquid(underlay.toString()) || visibility.hides(underlay.toString())) {
            return;
        }
        String underlayId = underlay.toString();
        Identifier texture = textureFor(client, underlayId);
        CellQuad cell = cellQuad(x, y);
        if (isTiled(underlayId)) {
            int tx = Math.floorMod(x, TILE_CELLS);
            int ty = Math.floorMod(y, TILE_CELLS);
            float step = 1F / TILE_CELLS;
            client.drawTextureRegion(texture, tx * step, ty * step, (tx + 1) * step, (ty + 1) * step,
                    cell.x(), cell.y(), cell.width(), cell.height(), 0.04F, 1F, 1F, 1F, 1F);
            return;
        }
        client.drawTexture(texture, cell.x(), cell.y(), cell.width(), cell.height(),
                0.04F, 1F, 1F, 1F, 1F);
    }

    /**
     * Where one scene element's art is drawn, in world cells.
     *
     * <p>One cell for everything drawn by the convention. An element that declares a size of
     * its own is centred on its cell instead: the original's crater is a decal wider and
     * shorter than a cell, and stretching it to the cell is exactly the distortion the art was
     * made to avoid.
     */
    private static CellQuad artQuad(String sceneId, int x, int y) {
        float[] size = EntityTextures.sceneSize(sceneId);
        if (size[0] == 1F && size[1] == 1F) {
            return cellQuad(x, y);
        }
        return new CellQuad(x + 0.5F - size[0] / 2F, y + 0.5F - size[1] / 2F, size[0], size[1]);
    }

    /**
     * Where one scene cell's texture crop is drawn, in world cells.
     *
     * <p>Always exactly one board cell. This is the invariant the placement highlight
     * and the hit test rely on, and the one the renderer used to break: grass art is a
     * square 6x6 atlas (1254x1254, so 209x209 per cell) while a board cell is 80x100
     * pixels, so the crop was drawn at 1.25 cells wide and centred to keep the art
     * square. The drawn grass pattern was therefore 25% wider than the logical grid -
     * its seams sat 0.125 cells to the left of every cell boundary - and the yellow
     * highlight landed visibly off the tile under the cursor. Stretching the crop to
     * the cell is the only self-consistent choice: the logical grid cannot move (the
     * lawn is exactly 9x80 by 5x100 pixels), so the art has to fit it.
     */
    public record CellQuad(float x, float y, float width, float height) {
    }

    /** The quad for one board cell; its texture crop is the matching 1/6 x 1/6 slice. */
    public static CellQuad cellQuad(int cellX, int cellY) {
        return new CellQuad(cellX, cellY, 1F, 1F);
    }

    /** The quad for a full 6x6 block, tiling exactly onto the cells it covers. */
    public static CellQuad blockQuad(int blockX, int blockY) {
        return new CellQuad(blockX * (float) TILE_CELLS, blockY * (float) TILE_CELLS,
                TILE_CELLS, TILE_CELLS);
    }

    private static int blockVisibleCells(int width, int height, int blockX, int blockY) {
        int x0 = blockX * TILE_CELLS;
        int y0 = blockY * TILE_CELLS;
        int x1 = Math.min(width, x0 + TILE_CELLS);
        int y1 = Math.min(height, y0 + TILE_CELLS);
        return Math.max(0, x1 - x0) * Math.max(0, y1 - y0);
    }

    private static boolean isTiled(String sceneId) {
        return "pvzce:grass".equals(sceneId) || "pvzce:ground".equals(sceneId);
    }

    /** Delegates so the tile renderer and the editor canvas cannot diverge. */
    public static Identifier sceneTexture(String sceneId) {
        return com.pvzce.client.renderer.EntityTextures.forScene(sceneId);
    }

    /** Draws a surface in its own world frame, after lower-surface entities and before its occupants. */
    public static void renderSurface(PvzceClient client, String surface) {
        var board = client.level().sceneBoard();
        boolean ground = SceneBoard.DEFAULT_SURFACE.equals(surface);
        float alpha = client.level().activeSurface().equals(surface) ? 1F : 0.35F;
        java.util.Set<String> liquids = new java.util.LinkedHashSet<>();
        for (var entry : board.snapshot()) {
            if (!entry.surfaceId().equals(surface)) continue;
            String id = entry.baseId();
            if (!client.level().sceneVisibility().hides(id) && LiquidTextures.liquidFor(id).isPresent()) liquids.add(id);
        }
        for (String id : liquids) LiquidTextures.renderSurface(client, LiquidTextures.liquidFor(id).orElseThrow(), id, surface);
        for (var entry : board.snapshot()) {
            if (!entry.surfaceId().equals(surface)) continue;
            var cell = board.cell(surface, entry.x(), entry.y());
            if (ground && cell.profile().equals(SurfaceProfile.FLAT)
                    && (cell.overlay() == null || LiquidTextures.liquidFor(cell.base().toString()).isEmpty())) continue;
            String id = cell.element().toString();
            var element = board.get(surface, entry.x(), entry.y());
            if (element == null || element.isLiquid() || client.level().sceneVisibility().hides(id)) continue;
            if (cell.overlay() != null && !client.level().sceneVisibility().hides(cell.base().toString()))
                drawSurfaceCell(client, surface, cell, cell.base().toString(), entry.x(), entry.y(), alpha, 0F);
            var shift = ground ? client.level().sceneShifts().at(entry.x(), entry.y()) : null;
            drawSurfaceCell(client, surface, cell, id, entry.x(), entry.y(), alpha, shift == null ? 0F : shift.sink());
        }
    }

    private static void drawSurfaceCell(PvzceClient client, String surface,
                                        SceneBoard.Cell cell, String id, int cx, int cy, float alpha, float sink) {
        var board = client.level().sceneBoard();
        var element = com.pvzce.common.core.BuiltInRegistries.SCENE_ELEMENTS.get(Identifier.parse(id));
        if (element == null || element.isLiquid()) return;
        Identifier texture = textureFor(client, id);
        var sprite = client.textures().getOrLoad(texture);
        float tw = sprite == null ? 1F : sprite.width(), th = sprite == null ? 1F : sprite.height();
        CellQuad quad = artQuad(id, cx, cy);
        float shown = Math.max(.01F, Math.min(1F, 1F - sink));
        float x = quad.x(), y = quad.y(), right = x + quad.width(), top = y + quad.height() * shown;
        // Decals and standing objects translate with their support; tiled foundations bend with it.
        float z = board.elevationAt(surface, cx + 0.5F, cy + 0.5F);
        float bl = y + (element.overlay() ? z : cell.profile().at(x, y));
        float br = y + (element.overlay() ? z : cell.profile().at(right, y));
        float tr = top + (element.overlay() ? z : cell.profile().at(right, top));
        float tl = top + (element.overlay() ? z : cell.profile().at(x, top));
        float u = 0F, v = th * (1F - shown), uw = tw, vh = th * shown;
        if (isTiled(id)) {
            uw = tw / TILE_CELLS; vh = th / TILE_CELLS;
            u = Math.floorMod(cx, TILE_CELLS) * uw;
            v = Math.floorMod(cy, TILE_CELLS) * vh;
        }
        // Scene regions use bottom-left normalized UVs, like drawTextureRegion. The quad
        // API instead accepts top-left pixels; preserve the same orientation and top slice
        // when a grave sinks, then let drawTextureQuad perform the upload conversion once.
        float pixelBottom = th - v, pixelTop = th - (v + vh);
        client.drawTextureQuad(texture, x, bl, right, br, right, tr, x, tl,
                u, pixelBottom, u + uw, pixelBottom, u + uw, pixelTop, u, pixelTop,
                0.05F, 1F, 1F, 1F, alpha);
    }

    /**
     * The texture for a scene element, in the variant the level's sky calls for.
     *
     * <p>{@code client.level()} is the mirror, which is also what draws the board: the same
     * clock that tints the lawn decides which crater art belongs on it. A board rendered
     * outside a running level (the editor's canvas) has no night in it and gets the day art.
     */
    private static Identifier textureFor(PvzceClient client, String sceneId) {
        return EntityTextures.forScene(sceneId,
                client != null && client.level().isNightAt(client.level().smoothDayTicks()));
    }

    private record Cell(int x, int y) {
    }
}
