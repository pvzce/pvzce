package com.pvzce.client.renderer.liquid;

import com.pvzce.common.level.SceneBoard;
import com.pvzce.api.content.LiquidDef;
import com.pvzce.api.content.SceneElementDef;
import com.pvzce.api.util.Identifier;
import com.pvzce.client.PvzceClient;
import com.pvzce.client.renderer.Matrix4f;
import com.pvzce.client.renderer.RenderSystem;
import com.pvzce.client.renderer.SceneTileRenderer;
import com.pvzce.common.core.BuiltInRegistries;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The single place that answers "what should this scene element be drawn as?", and
 * the entry point every board renderer goes through to draw liquid.
 *
 * <p>Mirrors {@link com.pvzce.client.renderer.EntityTextures}: content id to
 * presentation, resolved once. Three call sites need this answer - the in-game
 * board, the seed chooser's preview and the editor's canvas - and they must agree,
 * so neither the resolution nor the request assembly is repeated per call site.
 *
 * <p>Results are cached by scene id because a board asks for the same few ids
 * thousands of times per frame and a registry lookup per cell is pure waste. The
 * cache is cleared on reload, since that is when the definitions can change.
 */
public final class LiquidTextures {
    private static final Map<String, Optional<LiquidDef>> CACHE = new HashMap<>();

    /** Neutral daylight, for the boards that are drawn outside a running level. */
    private static final float[] DAY_ENVIRONMENT = {1F, 1F, 1F, 0F, 0F, 1F, 1F, 1F, 0.35F};

    private LiquidTextures() {
    }

    /** Resolves a scene element id to the liquid it should be drawn as. */
    public static Optional<LiquidDef> liquidFor(String sceneId) {
        if (sceneId == null || sceneId.isEmpty()) {
            return Optional.empty();
        }
        return CACHE.computeIfAbsent(sceneId, LiquidTextures::resolve);
    }

    /** Liquid foundation named by either a liquid tile or a standing object's underlay. */
    public static String liquidSceneId(String sceneId) {
        if (liquidFor(sceneId).isPresent()) return sceneId;
        Identifier underlay = com.pvzce.client.renderer.EntityTextures.sceneUnderlay(sceneId);
        return underlay != null && liquidFor(underlay.toString()).isPresent() ? underlay.toString() : null;
    }

    private static Optional<LiquidDef> resolve(String sceneId) {
        Identifier id = Identifier.tryParse(sceneId);
        if (id == null) {
            return Optional.empty();
        }
        SceneElementDef element = BuiltInRegistries.SCENE_ELEMENTS.get(id);
        if (element == null || element.liquid().isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(BuiltInRegistries.LIQUIDS.get(element.liquid().get()));
    }

    /** Must be called on every resource/data reload; definitions may have changed. */
    public static void invalidate() {
        CACHE.clear();
    }

    /**
     * Draws one liquid layer of the running level's board.
     *
     * <p>Cells are in world units, so the caller passes one cell as {@code 1x1}
     * and the board origin. Lighting comes from the client's resolved day/night
     * state, which is what keeps the water the same colour as the grass at dusk.
     *
     * @param sceneId the scene element whose cells this is, so neighbouring cells
     *                of the same element join into one continuous body
     */
    public static void renderWorld(PvzceClient client, LiquidDef liquid, String sceneId,
                                   int width, int height, SceneTileRenderer.SceneSource scene) {
        renderWorld(client, liquid, sceneId, width, height, scene,
                com.pvzce.client.renderer.LevelStage.LiquidFrame.CELL);
    }

    /**
     * As above, with the stage's own frame for the surface.
     *
     * <p>{@code frame} is in world cells and is {@code CELL} for every stage whose water
     * cells <em>are</em> its surface. The pool is the one that is not: its backdrop paints
     * the basin two thirds of a lane below its own water rows, so the surface is drawn on a
     * frame the stage declares. See {@code LevelStage.LiquidFrame}.
     */
    public static void renderWorld(PvzceClient client, LiquidDef liquid, String sceneId,
                                   int width, int height, SceneTileRenderer.SceneSource scene,
                                   com.pvzce.client.renderer.LevelStage.LiquidFrame frame) {
        com.pvzce.client.renderer.LevelStage.LiquidFrame surface =
                frame == null ? com.pvzce.client.renderer.LevelStage.LiquidFrame.CELL : frame;
        draw(client, liquid, sceneId, width, height, scene,
                surface.originX(), surface.originY(), surface.cellWidth(), surface.cellHeight(),
                RenderSystem.currentProjection(),
                client.worldTintR(), client.worldTintG(), client.worldTintB(),
                client.worldTintLift(), client.worldNightBlend(),
                client.worldLightX(), client.worldLightY(),
                client.worldLightR(), client.worldLightG(), client.worldLightB(),
                client.worldLightStrength());
    }

    /** Runtime liquids use the same sampled profile as simulation, particles and picking. */
    public static void renderSurface(PvzceClient client, LiquidDef liquid, String sceneId, String surface) {
        var board = client.level().sceneBoard();
        var frame = SceneBoard.DEFAULT_SURFACE.equals(surface)
                ? client.camera().liquidFrame() : com.pvzce.client.renderer.LevelStage.LiquidFrame.CELL;
        LiquidRenderer.Request request = new LiquidRenderer.Request(liquid, board.width(), board.height(),
                (x, y) -> { var cell = board.cell(surface, x, y); return cell != null && sceneId.equals(cell.base().toString()); },
                frame.originX(), 0F, frame.cellWidth(), 1F, RenderSystem.currentProjection(), client.renderTimeSeconds(),
                client.worldTintR(), client.worldTintG(), client.worldTintB(), client.worldTintLift(), client.worldNightBlend(),
                client.worldLightX(), client.worldLightY(), client.worldLightR(), client.worldLightG(), client.worldLightB(),
                client.worldLightStrength());
        LiquidBatch.Elevation elevation = (x, y) -> board.elevationAt(surface, x, y);
        if (LiquidRenderer.available()) LiquidRenderer.render(client, request, client.liquidRipples(), elevation);
        else LiquidRenderer.renderFallback(client, request, elevation);
    }

    /**
     * Draws a board seen through a GUI rectangle, as the seed chooser preview does.
     *
     * <p>That board is not the running level, so there is no day/night state to
     * read: it uses neutral daylight and a light placed above the middle of the
     * board, which is what the chooser's own still lighting looks like.
     *
     */
    public static void renderGuiBoard(PvzceClient client, LiquidDef liquid, String sceneId,
                                      int width, int height, SceneTileRenderer.SceneSource scene,
                                      float originX, float originY,
                                      float pixelsPerCell, float pixelsPerCellY,
                                      Matrix4f projection) {
        renderGuiBoard(client, liquid, sceneId, width, height, scene, originX, originY,
                pixelsPerCell, pixelsPerCellY, projection,
                com.pvzce.client.renderer.LevelStage.LiquidFrame.CELL);
    }

    /**
     * As above, with the stage's world-cell frame scaled onto the board's own rectangle.
     *
     * <p>{@code stageFrame} is in <strong>world cells</strong> - the value a camera returns and
     * nothing else - and this method is the one place that turns it into the GUI pixels
     * {@code originX}/{@code pixelsPerCell} describe. {@code scaledTo} is linear, not idempotent,
     * so a caller that pre-scales the frame hands this method a squared one: the batch then lands
     * at a cell size proportional to the board rather than to the stage, and on the seed chooser's
     * small board that is several thousand pixels per cell, i.e. off screen. The preview lost its
     * water exactly that way; see {@link SceneTileRenderer#renderBoard}.
     */
    public static void renderGuiBoard(PvzceClient client, LiquidDef liquid, String sceneId,
                                      int width, int height, SceneTileRenderer.SceneSource scene,
                                      float originX, float originY,
                                      float pixelsPerCell, float pixelsPerCellY,
                                      Matrix4f projection,
                                      com.pvzce.client.renderer.LevelStage.LiquidFrame stageFrame) {
        com.pvzce.client.renderer.LevelStage.LiquidFrame cellFrame =
                stageFrame == null
                        ? com.pvzce.client.renderer.LevelStage.LiquidFrame.CELL
                        : stageFrame.scaledTo(originX, originY, pixelsPerCell, pixelsPerCellY);
        draw(client, liquid, sceneId, width, height, scene,
                cellFrame.originX(), cellFrame.originY(), cellFrame.cellWidth(), cellFrame.cellHeight(),
                projection,
                DAY_ENVIRONMENT[0], DAY_ENVIRONMENT[1], DAY_ENVIRONMENT[2], DAY_ENVIRONMENT[3],
                DAY_ENVIRONMENT[4],
                width / 2F, height * 2F, DAY_ENVIRONMENT[5], DAY_ENVIRONMENT[6],
                DAY_ENVIRONMENT[7], DAY_ENVIRONMENT[8]);
    }

    private static void draw(PvzceClient client, LiquidDef liquid, String sceneId,
                             int width, int height, SceneTileRenderer.SceneSource scene,
                             float originX, float originY, float cellWidth, float cellHeight,
                             Matrix4f projection,
                             float tintR, float tintG, float tintB, float tintLift, float night,
                             float lightX, float lightY, float lightR, float lightG, float lightB,
                             float lightStrength) {
        LiquidGeometry.Occupancy occupancy = (x, y) -> sceneId.equals(liquidSceneId(scene.sceneAt(x, y)));
        LiquidRenderer.Request request = new LiquidRenderer.Request(
                liquid, width, height, occupancy, originX, originY, cellWidth, cellHeight,
                projection, client.renderTimeSeconds(),
                tintR, tintG, tintB, tintLift, night,
                lightX, lightY, lightR, lightG, lightB, lightStrength);
        if (LiquidRenderer.available()) {
            LiquidRenderer.render(client, request, client.liquidRipples());
        } else {
            LiquidRenderer.renderFallback(client, request);
        }
    }
}
