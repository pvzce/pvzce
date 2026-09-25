package com.pvzce.client.renderer.liquid;

import com.pvzce.api.content.LiquidDef;
import com.pvzce.api.util.Identifier;
import com.pvzce.client.PvzceClient;
import com.pvzce.client.config.PvzceClientConfig;
import com.pvzce.client.renderer.Matrix4f;
import org.lwjgl.opengl.GL13;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.List;

/**
 * Draws liquid surfaces: collects the cells once, then uploads and draws them in
 * a single pass.
 *
 * <p>The pass is deliberately independent of the gameplay camera. The board is
 * drawn in three different places - the in-game world view, the seed chooser's
 * preview and the level editor's canvas - in two different coordinate spaces
 * (world cells and GUI pixels), and all three want the same water. Taking the
 * projection and a pixels-per-cell scale as input lets one implementation serve
 * all of them, and putting the CELL coordinate in the vertex attribute rather
 * than the position keeps the shader's pattern continuous in both spaces.
 */
public final class LiquidRenderer {
    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger("PVZCE/Liquid");
    /** Feature bits for the high tier; the lower tiers are subsets. */
    private static final int ALL_FEATURES = LiquidShader.FEATURE_WAVES
            | LiquidShader.FEATURE_CAUSTICS
            | LiquidShader.FEATURE_FRESNEL
            | LiquidShader.FEATURE_SPECULAR
            | LiquidShader.FEATURE_CAUSTIC_SHEET
            | LiquidShader.FEATURE_RIPPLES
            | LiquidShader.FEATURE_SHORE;

    private static LiquidShader shader;
    private static LiquidBatch batch;
    private static boolean unavailable;
    /** Ring buffer of baked fallback frames, advanced by wall-clock time. */
    private static final float FALLBACK_FRAME_SECONDS = 0.28F;

    private LiquidRenderer() {
    }

    /** One surface to draw: what it is, where it goes and what light falls on it. */
    public record Request(
            LiquidDef liquid,
            int width,
            int height,
            LiquidGeometry.Occupancy occupancy,
            float originX,
            float originY,
            float cellWidth,
            float cellHeight,
            Matrix4f projection,
            float time,
            float tintR, float tintG, float tintB, float tintLift,
            float night,
            float lightX, float lightY, float lightR, float lightG, float lightB,
            float lightStrength
    ) {
    }

    /**
     * True when the liquid pass can run. Shader creation is lazy and its failure
     * is remembered: a driver that rejects the program must not retry - and log -
     * on every frame, and the caller falls back to the plain textured path.
     */
    public static boolean available() {
        if (!com.pvzce.client.renderer.RenderSystem.shaderEffectsEnabled()) {
            return false;
        }
        if (shader != null) {
            return true;
        }
        if (unavailable) {
            return false;
        }
        try {
            shader = new LiquidShader();
            batch = new LiquidBatch(256);
            return true;
        } catch (RuntimeException e) {
            unavailable = true;
            LOGGER.error("WATER SHADER DID NOT COMPILE - the surface is now a flat fallback"
                    + " colour with no waves, caustics or foam. This is a bug, not a settings"
                    + " problem.", e);
            return false;
        }
    }

    /** Shader-path draw; the caller has already checked {@link #available()}. */
    public static void render(PvzceClient client, Request request, LiquidRipples ripples) {
        List<LiquidCell> cells = LiquidGeometry.collect(request.width(), request.height(),
                request.occupancy(),
                request.liquid().shallowColor()[0], request.liquid().shallowColor()[1],
                request.liquid().shallowColor()[2], request.liquid().depthScale());
        if (cells.isEmpty()) {
            return;
        }
        shader.use();
        // The sprite program is the one RenderSystem knows about, so it has to be put
        // back even if the draw throws: otherwise RenderSystem's later uniform writes
        // (setShadowMode, setTextured, the day/night values) would land on THIS
        // program's locations and silently corrupt it.
        try {
            drawLiquid(client, request, cells, ripples);
        } finally {
            com.pvzce.client.renderer.RenderSystem.setShader();
            GL13.glActiveTexture(GL13.GL_TEXTURE1);
            GL13.glBindTexture(GL13.GL_TEXTURE_2D, 0);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
        }
    }

    /**
     * How hard the original's caustic sheet modulates the surface at full strength.
     *
     * <p>It is added as light, so this is the swing around the sheet's own midpoint rather than
     * a colour: at 0.55 a fully lit cell lifts the water by about a fifth and a dark lane takes
     * the same back, which is motion the eye catches on a surface this bright without turning
     * the pool into a checkerboard.
     */
    private static final float CAUSTIC_GAIN = 0.55F;

    private static void drawLiquid(PvzceClient client, Request request, List<LiquidCell> cells,
                                   LiquidRipples ripples) {
        shader.setProjection(request.projection());
        Identifier texture = request.liquid().resolvedBaseTexture();
        boolean hasTexture = safeHasTexture(client, texture);
        if (hasTexture) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            com.pvzce.client.renderer.RenderSystem.bindTexture(
                    client.textures().getOrLoad(texture).glId());
        }
        shader.setTexture(0, hasTexture);
        // The caustic sheet, on its own unit. Bound after the base texture so the active unit
        // is left where the caller expects it (unit 0) when this returns.
        Identifier causticTexture = request.liquid().caustics().texture().orElse(null);
        boolean hasCaustic = causticTexture != null && safeHasTexture(client, causticTexture)
                && request.liquid().caustics().scale() > 0F;
        if (hasCaustic) {
            GL13.glActiveTexture(GL13.GL_TEXTURE1);
            com.pvzce.client.renderer.RenderSystem.bindTexture(
                    client.textures().getOrLoad(causticTexture).glId());
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
        }
        shader.setCausticTexture(1, hasCaustic, request.liquid().caustics().scale(),
                request.liquid().caustics().scroll(), CAUSTIC_GAIN);
        shader.setBaseScale(request.liquid().baseScale());
        shader.setColors(request.liquid().shallowColor(), request.liquid().deepColor(),
                request.liquid().foam().color(), request.liquid().reflectColor(),
                request.liquid().opacity());
        shader.setSurface(request.liquid().depthScale(), request.liquid().foam().width(),
                request.liquid().wave().speed(), request.liquid().wave().amplitude(),
                request.liquid().wave().density(),
                request.liquid().causticStrength(), request.liquid().fresnel(),
                request.liquid().specular(), request.liquid().specularPower());
        shader.setTime(request.time());
        shader.setLighting(request.tintR(), request.tintG(), request.tintB(), request.tintLift(),
                request.night(), request.lightX(), request.lightY(),
                request.lightR(), request.lightG(), request.lightB(), request.lightStrength());
        shader.setFeatures(featuresFor(client.config().waterQuality()));
        if (ripples != null && ripples.count() > 0) {
            shader.setRipples(ripples.pack(), ripples.count());
        } else {
            shader.setRipples(null, 0);
        }

        batch.reset();
        batch.addAll(cells, request.originX(), request.originY(),
                request.cellWidth(), request.cellHeight());
        reportOnce(request, cells);
        batch.draw();
    }

    /**
     * Baked-frame path used when the shader is off or unavailable.
     *
     * <p>Deliberately not a second implementation of the water: it draws the
     * pre-rendered frames through the ordinary sprite pipeline, so water stays
     * visible and still animates on a machine that cannot run the shader, at the
     * cost of the parameters the definition exposes.
     */
    public static void renderFallback(PvzceClient client, Request request) {
        LiquidDef liquid = request.liquid();
        int frames = Math.max(1, liquid.staticFrames());
        int frame = (int) (request.time() / FALLBACK_FRAME_SECONDS) % frames;
        Identifier texture = liquid.staticFrameTexture(frame);
        // No baked frames shipped yet, so this currently always takes the flat path.
        //
        // It deliberately does NOT fall back to the base texture. That is what the
        // first version did, and because a per-cell copy of a 418x418 floor tile at
        // uv 0..1 looks like "the shader ran but with no surface effects", it sent a
        // whole debugging session down the wrong path when the real problem was that
        // the shader failed to compile. A flat surface is unmistakably a fallback.
        boolean hasTexture = safeHasTexture(client, texture);
        float[] color = liquid.shallowColor();
        for (int y = 0; y < request.height(); y++) {
            for (int x = 0; x < request.width(); x++) {
                if (!request.occupancy().isLiquid(x, y)) {
                    continue;
                }
                float drawX = request.originX() + x * request.cellWidth();
                float drawY = request.originY() + y * request.cellHeight();
                if (hasTexture) {
                    // Drawn FLAT: the baked frame already contains the water colour over
                    // the sea floor, the caustics and the foam, so tinting it again with
                    // the surface colour is what turned the first fallback grey. Only
                    // alpha is passed, so a level's own water can still be translucent
                    // if a definition asks for it.
                    client.drawTexture(texture, drawX, drawY, request.cellWidth(), request.cellHeight(),
                            0F, 1F, 1F, 1F, color[3]);
                } else {
                    // Deep water colour, so the fallback at least reads as water and
                    // not as a missing texture.
                    float[] deep = liquid.deepColor();
                    client.drawSolid(drawX, drawY, request.cellWidth(), request.cellHeight(),
                            0F, deep[0], deep[1], deep[2], 1F);
                }
            }
        }
    }

    /**
     * One-shot diagnostic for a reported symptom the renderer cannot reproduce
     * locally: "the middle of the water is not drawn".
     *
     * <p>It prints the board it was asked to draw, how many cells the liquid pass
     * actually collected, and the bounding box of those cells, so a batch that is
     * missing part of a body is visible in the log without a debugger. It logs once
     * per distinct signature rather than per frame.
     */
    private static void reportOnce(Request request, List<LiquidCell> cells) {
        float minX = Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        for (LiquidCell cell : cells) {
            minX = Math.min(minX, cell.cellX());
            minY = Math.min(minY, cell.cellY());
            maxX = Math.max(maxX, cell.cellX());
            maxY = Math.max(maxY, cell.cellY());
        }
        String signature = request.width() + "x" + request.height() + ":" + cells.size();
        if (signature.equals(lastReported)) {
            return;
        }
        lastReported = signature;
        LOGGER.info("liquid pass: board {}x{}, drew {} cells, cell range x={}..{} y={}..{},"
                        + " origin=({},{}) size=({}x{})",
                request.width(), request.height(), cells.size(),
                (int) minX, (int) maxX, (int) minY, (int) maxY,
                request.originX(), request.originY(), request.cellWidth(), request.cellHeight());
    }

    private static String lastReported = "";

    /**
     * Diagnostic switch that strips the water back to its base texture.
     *
     * <p>Used to measure the TEXTURE's own large-scale banding without the caustics,
     * which are a moving three-cosine web and otherwise dominate any luminance
     * profile taken across the surface. Set {@code -Dpvzce.waterTextureOnly=true} and
     * capture a frame to see exactly what the tile contributes.
     */
    private static final boolean TEXTURE_ONLY =
            Boolean.getBoolean("pvzce.waterTextureOnly");

    /** Maps the quality setting onto the shader's feature bits. */
    public static int featuresFor(int quality) {
        if (TEXTURE_ONLY) {
            return LiquidShader.FEATURE_SHORE;
        }
        return switch (PvzceClientConfig.WaterQuality.clamp(quality)) {
            // Low: a flat, still surface with shoreline foam. The foam stays in
            // because without it the water reads as a slab of colour. Deliberately the one
            // tier with no time term at all - it is what "low" means here.
            case PvzceClientConfig.WaterQuality.LOW -> LiquidShader.FEATURE_SHORE;
            // Medium: motion and glitter, no procedural caustics, no sky reflection. The
            // original's caustic sheet stays: it is the cheapest of the moving terms (one
            // texture read, no noise) and it is the one that actually shows on this surface,
            // so dropping it here is what used to leave the middle tier as still as the low one.
            case PvzceClientConfig.WaterQuality.MEDIUM -> ALL_FEATURES
                    & ~(LiquidShader.FEATURE_CAUSTICS | LiquidShader.FEATURE_FRESNEL);
            default -> ALL_FEATURES;
        };
    }

    /** True when a texture can be resolved without triggering a load-and-throw. */
    private static boolean safeHasTexture(PvzceClient client, Identifier id) {
        try {
            return client.hasTexture(id);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Drops the compiled program, e.g. when the GL context is replaced. */
    public static void invalidate() {
        if (shader != null) {
            shader.close();
            shader = null;
        }
        batch = null;
        unavailable = false;
    }
}
