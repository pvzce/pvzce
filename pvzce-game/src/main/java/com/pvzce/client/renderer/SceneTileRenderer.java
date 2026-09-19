package com.pvzce.client.renderer;

import com.pvzce.api.util.Identifier;
import com.pvzce.client.PvzceClient;
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

    @FunctionalInterface
    public interface SceneSource {
        String sceneAt(int x, int y);
    }

    private SceneTileRenderer() {
    }

    public static void render(PvzceClient client, int width, int height, SceneSource scene,
                              float xScale, float grassMargin) {
        float scaleX = Math.max(0.0001F, xScale);
        float margin = Math.max(0F, grassMargin);
        if (margin > 0F) {
            renderGrassMargin(client, width, height, margin);
        }
        Map<String, List<Cell>> layers = collectLayers(width, height, scene);
        int blocksX = MathUtil.ceilDiv(Math.max(1, width), TILE_CELLS);
        int blocksY = MathUtil.ceilDiv(Math.max(1, height), TILE_CELLS);

        for (Map.Entry<String, List<Cell>> layer : layers.entrySet()) {
            String sceneId = layer.getKey();
            // A liquid layer is not drawn tile by tile at all: it is one batched
            // pass that needs the whole body at once, so it is pulled out here and
            // drawn in the layer's own position. See renderLiquidLayer.
            if (renderLiquidLayer(client, sceneId, scene, width, height)) {
                continue;
            }
            boolean tiled = isTiled(sceneId);
            Identifier texture = textureFor(sceneId);

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
                            drawCell(client, sceneId, texture, cell.x(), cell.y());
                        }
                    }
                }
            }
        }
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
                                             int width, int height) {
        var liquid = LiquidTextures.liquidFor(sceneId);
        if (liquid.isEmpty()) {
            return false;
        }
        LiquidTextures.renderWorld(client, liquid.get(), sceneId, width, height, scene);
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
        // Collected first, drawn after: a liquid body has to be batched as a whole,
        // and the flat tiles must go down before it so the water covers them. One
        // entry per liquid, so a board with two liquids keeps them separate.
        Map<String, List<Cell>> pending = new LinkedHashMap<>();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                String sceneId = scene.sceneAt(x, y);
                if (isLiquid(sceneId)) {
                    pending.computeIfAbsent(sceneId, ignored -> new ArrayList<>()).add(new Cell(x, y));
                    continue;
                }
                Identifier texture = textureFor(sceneId);
                float drawX = originX + x * cellWidth;
                float drawY = originY + y * cellHeight;
                if (isTiled(sceneId)) {
                    // Same cell-filling rule as the in-game board, so the chooser's
                    // preview and the real lawn cannot disagree.
                    int tx = Math.floorMod(x, TILE_CELLS);
                    int ty = Math.floorMod(y, TILE_CELLS);
                    float step = 1F / TILE_CELLS;
                    client.drawTextureRegion(texture, tx * step, ty * step, (tx + 1) * step, (ty + 1) * step,
                            drawX, drawY, cellWidth, cellHeight, 0F, 1F, 1F, 1F, 1F);
                } else {
                    client.drawTexture(texture, drawX, drawY, cellWidth, cellHeight, 0F, 1F, 1F, 1F, 1F);
                }
            }
        }
        for (Map.Entry<String, List<Cell>> layer : pending.entrySet()) {
            var liquid = LiquidTextures.liquidFor(layer.getKey());
            if (liquid.isEmpty()) {
                continue;
            }
            LiquidTextures.renderGuiBoard(client, liquid.get(), layer.getKey(), width, height, scene,
                    originX, originY, cellWidth, cellHeight,
                    com.pvzce.client.renderer.RenderSystem.currentProjection());
        }
    }

    /** True when a scene element is a liquid and therefore not a flat tile. */
    private static boolean isLiquid(String sceneId) {
        return LiquidTextures.liquidFor(sceneId).isPresent();
    }

    /** Paints a small grass ring outside the playable board so edge tiles/actors do not look cut off. */
    private static void renderGrassMargin(PvzceClient client, int width, int height, float margin) {
        Identifier texture = textureFor("pvzce:grass");
        int minX = (int) Math.floor(-margin);
        int maxX = (int) Math.ceil(width + margin) - 1;
        int minY = (int) Math.floor(-margin);
        int maxY = (int) Math.ceil(height + margin) - 1;
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                if (x >= 0 && x < width && y >= 0 && y < height) {
                    continue;
                }
                drawCell(client, "pvzce:grass", texture, x, y);
            }
        }
    }

    private static Map<String, List<Cell>> collectLayers(int width, int height, SceneSource scene) {
        Map<String, List<Cell>> layers = new LinkedHashMap<>();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                String sceneId = scene.sceneAt(x, y);
                layers.computeIfAbsent(sceneId, ignored -> new ArrayList<>()).add(new Cell(x, y));
            }
        }
        return layers;
    }

    private static void drawCell(PvzceClient client, String sceneId, Identifier texture, int x, int y) {
        CellQuad quad = cellQuad(x, y);
        if (isTiled(sceneId)) {
            int tx = Math.floorMod(x, TILE_CELLS);
            int ty = Math.floorMod(y, TILE_CELLS);
            float step = 1F / TILE_CELLS;
            client.drawTextureRegion(texture, tx * step, ty * step, (tx + 1) * step, (ty + 1) * step,
                    quad.x(), quad.y(), quad.width(), quad.height(), 0.05F, 1F, 1F, 1F, 1F);
        } else {
            // Pre-6x6 elements: a full 1x1 texture still lives at textures/scene/<path>.
            client.drawTexture(texture, quad.x(), quad.y(), quad.width(), quad.height(),
                    0.05F, 1F, 1F, 1F, 1F);
        }
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

    private static Identifier textureFor(String sceneId) {
        return sceneTexture(sceneId);
    }

    private record Cell(int x, int y) {
    }
}
