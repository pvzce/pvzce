package com.pvzce.client.renderer;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;

/** Static GL state facade (MC RenderSystem-shaped, self-written). */
public final class RenderSystem {
    private static ShaderProgram shader;
    private static Matrix4f projection = Matrix4f.identity();
    private static int boundTexture;
    private static boolean shaderEffectsEnabled = true;

    private RenderSystem() {
    }

    public static void init() {
        shader = new ShaderProgram();
        GL20.glEnable(GL11.GL_BLEND);
        GL20.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL20.glDisable(GL11.GL_DEPTH_TEST);
        GL20.glDisable(GL11.GL_CULL_FACE);
    }

    /**
     * Additive blending, for light rather than for paint.
     *
     * <p>The original blends its glows this way, and drawing them with ordinary alpha
     * is visibly wrong: a soft radial gradient becomes a flat opaque disc that hides
     * what is underneath (the coin's glow was cut from the art entirely because of
     * this). Anything that turns this on must turn it back off - {@link #blendNormal}
     * is the counterpart, and {@code PvzceClient}'s frame loop resets it as a backstop.
     */
    public static void blendAdditive() {
        GL20.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);
    }

    /** Ordinary source-over alpha blending; the state {@link #init()} starts in. */
    public static void blendNormal() {
        GL20.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
    }

    /**
     * Whether shader-driven effects may run.
     *
     * <p>Set from the client config once per world view. The liquid pass reads it
     * so a driver that cannot compile its program does not get asked every frame.
     */
    public static void setShaderEffectsEnabled(boolean enabled) {
        shaderEffectsEnabled = enabled;
    }

    public static boolean shaderEffectsEnabled() {
        return shaderEffectsEnabled;
    }

    public static ShaderProgram shader() {
        return shader;
    }

    public static void setShader() {
        shader.use();
    }

    public static void setProjectionMatrix(Matrix4f matrix) {
        projection = matrix;
        shader.use();
        shader.setProjection(matrix);
    }

    /**
     * The projection currently in effect.
     *
     * <p>A pass that draws with its own program (the liquid pass) has to apply the
     * same projection the sprite pass is using, and the projection is set in three
     * different places - the world view, the GUI view and the overlay views. Reading
     * it back here is what lets one liquid implementation serve all of them instead
     * of each call site re-deriving the matrix.
     */
    public static Matrix4f currentProjection() {
        return projection;
    }

    public static void bindTexture(int textureId) {
        if (boundTexture != textureId) {
            GL20.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
            boundTexture = textureId;
        }
    }

    /**
     * Records a texture binding that happened outside {@link #bindTexture}.
     *
     * <p>{@code bindTexture} skips the GL call when the id matches its cache, so any
     * code that binds directly must report it here; otherwise the cache goes stale and
     * a later bind of the previously-cached texture is skipped, leaving the wrong
     * texture bound.
     */
    public static void noteTextureBound(int textureId) {
        boundTexture = textureId;
    }

    public static void setTextured(boolean textured) {
        shader.setTextured(textured);
        if (!textured) {
            GL20.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            boundTexture = 0;
        }
    }

    public static void setShadowMode(boolean enabled) {
        shader.setShadowMode(enabled);
    }

    public static void setShadowColor(float r, float g, float b, float a) {
        shader.setShadowColor(r, g, b, a);
    }

    public static void setTimeOfDay(float tintR, float tintG, float tintB, float tintLift,
                                    float sunX, float sunY, float sunRadius,
                                    float sunR, float sunG, float sunB, float sunStrength) {
        shader.setTimeOfDay(tintR, tintG, tintB, tintLift, sunX, sunY, sunRadius, sunR, sunG, sunB, sunStrength);
    }

    /** Sets one world-space entity light (sun drop glow). */
    public static void setPointLight(int index, float x, float y, float radius,
                                     float r, float g, float b, float strength) {
        shader.setPointLight(index, x, y, radius, r, g, b, strength);
    }

    public static void clearPointLights() {
        shader.clearPointLights();
    }

    public static void setGuiShader() {
        shader.setTimeOfDay(1F, 1F, 1F, 0F, 0F, 0F, 0F, 1F, 1F, 1F, 0F);
        shader.clearPointLights();
        shader.setShadowMode(false);
    }

    /**
     * The same, but keeps whatever tint is active.
     *
     * <p>For a sub-view drawn <em>inside</em> a lit board - the seed chooser's zombie preview
     * sits in the night lawn it previews, so it has to keep the night tint. Its own world has
     * nothing to do with the board's pixels, so the caller also drops the glow rather than
     * letting a sun that was mapped for the board land somewhere in a four-unit-tall world.
     */
    public static void setOverlayShader() {
        shader.clearPointLights();
        shader.setShadowMode(false);
    }

    public static void clear(float r, float g, float b, float a) {
        GL20.glClearColor(r, g, b, a);
        GL20.glClear(GL11.GL_COLOR_BUFFER_BIT);
    }

    public static void viewport(int x, int y, int width, int height) {
        GL20.glViewport(x, y, width, height);
    }

    public static void checkGlError(String where) {
        int error = GL20.glGetError();
        if (error != GL11.GL_NO_ERROR) {
            System.err.println("[GL] " + where + ": 0x" + Integer.toHexString(error));
        }
    }
}
