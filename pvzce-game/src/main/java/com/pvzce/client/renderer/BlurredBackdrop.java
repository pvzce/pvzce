package com.pvzce.client.renderer;

import com.pvzce.api.util.Identifier;
import com.pvzce.client.renderer.texture.Texture;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;

/**
 * The frame a screen was opened over, kept as a small texture and drawn back magnified - which is
 * how it is blurred.
 *
 * <p>There is no post-processing pass in this renderer and this does not add one: the blur is the
 * bilinear magnification of a frame that was copied at a fraction of its size. One
 * {@code glBlitFramebuffer} down into an FBO, one textured quad back out, no shader round trip and
 * no second render of the screen underneath - so a settings page opened over a level shows that
 * level, softly, instead of a menu background that has nothing to do with where the player was.
 *
 * <p><strong>When the capture happens matters.</strong> It has to be before the incoming screen
 * draws, while the back buffer still holds the frame the player was looking at; that is what
 * {@code PvzceClient.openScreen} does for a screen that asks for a blurred backdrop. A screen that
 * asks for one without anything underneath it (the first screen of a session) gets {@code false}
 * from {@link #render} and falls back to its own background.
 *
 * <p>A resize between the capture and the draw is harmless: the texture is magnified to whatever
 * the window is now, so the backdrop is simply stretched, and the next capture uses the new size.
 */
public final class BlurredBackdrop implements AutoCloseable {
    /**
     * How much smaller the copy is than the framebuffer. Six is enough that a magnified copy hides
     * the glyph-sized detail a menu's text would otherwise leave behind, and small enough that the
     * blit stays a rounding error in the frame's cost.
     */
    private static final int DOWNSCALE = 6;

    private int framebuffer;
    private int texture;
    private int copyWidth;
    private int copyHeight;
    /** True once a frame has been captured; cleared by release. */
    private boolean captured;
    private Texture handle;

    /**
     * Copies the current back buffer into the backdrop at {@code 1 / }{@link #DOWNSCALE} of its
     * size. Safe to call every time a screen opens; it reallocates only when the size changed.
     */
    public void capture(int width, int height) {
        if (width <= 0 || height <= 0) {
            return;
        }
        int wantedWidth = Math.max(1, width / DOWNSCALE);
        int wantedHeight = Math.max(1, height / DOWNSCALE);
        if (framebuffer == 0 || wantedWidth != copyWidth || wantedHeight != copyHeight) {
            release();
            create(wantedWidth, wantedHeight);
        }
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, 0);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, framebuffer);
        // GL_LINEAR is the cheap half of the blur: the downscale samples instead of averaging, so
        // it already loses the fine detail, and the magnification below smears what is left.
        GL30.glBlitFramebuffer(0, 0, width, height, 0, 0, copyWidth, copyHeight,
                GL11.GL_COLOR_BUFFER_BIT, GL11.GL_LINEAR);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        captured = true;
    }

    /** True when a frame is held and can be drawn. */
    public boolean hasFrame() {
        return captured && texture != 0;
    }

    /**
     * Draws the held frame over the whole GUI, magnified, with a flat tint over it.
     *
     * <p>The tint is not decoration: a blurred game view is still busy enough to fight text, so
     * every caller dims it. Returns false - drawing nothing - when no frame is held, which is how a
     * screen knows to fall back to its own background.
     */
    public boolean render(int guiWidth, int guiHeight,
                          float tintR, float tintG, float tintB, float tintA) {
        if (!hasFrame() || guiWidth <= 0 || guiHeight <= 0) {
            return false;
        }
        if (handle == null) {
            handle = new Texture(Identifier.withDefaultNamespace("backdrop/blurred"),
                    texture, copyWidth, copyHeight);
        }
        // The copy came from the default framebuffer, whose first row is its bottom one - the same
        // orientation a PNG gets from `stbi_set_flip_vertically_on_load` - so v = 0 is the bottom of
        // the picture and the quad is drawn the ordinary way up.
        SpriteRenderer.texturedRegion(handle, 0F, 0F, 1F, 1F,
                0F, 0F, guiWidth, guiHeight, -1.5F, 1F, 1F, 1F, 1F);
        SpriteRenderer.solid(0F, 0F, guiWidth, guiHeight, -1.4F, tintR, tintG, tintB, tintA);
        return true;
    }

    private void create(int width, int height) {
        framebuffer = GL30.glGenFramebuffers();
        texture = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        // The binding cache has to hear about this: it is what stops a later bind of the previously
        // bound texture from being skipped as redundant and leaving this one sampled instead.
        RenderSystem.noteTextureBound(texture);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, width, height, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                GL11.GL_TEXTURE_2D, texture, 0);
        if (GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE) {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
            release();
            throw new IllegalStateException("Backdrop framebuffer is incomplete");
        }
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        copyWidth = width;
        copyHeight = height;
    }

    private void release() {
        if (framebuffer != 0) {
            GL30.glDeleteFramebuffers(framebuffer);
            framebuffer = 0;
        }
        if (texture != 0) {
            GL11.glDeleteTextures(texture);
            // Whatever the facade thinks is bound may be this id; forget it so the next bind is a
            // real call rather than a skipped one onto a deleted texture.
            RenderSystem.noteTextureBound(0);
            texture = 0;
        }
        handle = null;
        copyWidth = 0;
        copyHeight = 0;
        captured = false;
    }

    @Override
    public void close() {
        release();
    }
}
