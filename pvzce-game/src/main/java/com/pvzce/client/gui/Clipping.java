package com.pvzce.client.gui;

import com.pvzce.client.PvzceClient;
import org.lwjgl.opengl.GL11;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * GUI-space clipping rectangles.
 *
 * <p>Scissoring used to be written out by hand at four call sites with two
 * different coordinate conversions: the in-game screen divided the framebuffer by
 * the exact ratio ({@code window.width() / guiWidth()}), while the seed chooser
 * multiplied by the integer {@code guiScale}. Those agree only when the window
 * size is an exact multiple of the scale, so on most windows the clipped region
 * and the clickable region disagreed by a few pixels. Only one of the four sites
 * had a {@code finally}, so an exception drawn inside a clip left
 * {@code GL_SCISSOR_TEST} enabled for the rest of the session.
 *
 * <p>{@link Clipping#push} intersects with the active rectangle and {@link Clipping#pop}
 * restores it, so nested clips behave the way a caller expects.
 */
public final class Clipping {
    private final Deque<int[]> stack = new ArrayDeque<>();
    private final PvzceClient client;

    public Clipping(PvzceClient client) {
        this.client = client;
    }

    /**
     * Clips subsequent draws to a GUI-space rectangle (logical pixels, origin
     * bottom-left). Nested calls intersect with the current clip.
     */
    public void push(float guiX, float guiY, float guiWidth, float guiHeight) {
        int framebufferWidth = Math.max(1, client.window().width());
        int framebufferHeight = Math.max(1, client.window().height());
        // The exact inverse of guiMouseX/guiMouseY, so a clip rect and a hit test
        // over the same GUI rectangle always agree.
        double scaleX = framebufferWidth / (double) Math.max(1, client.guiWidth());
        double scaleY = framebufferHeight / (double) Math.max(1, client.guiHeight());

        int x0 = (int) Math.floor(guiX * scaleX);
        int y0 = (int) Math.floor(guiY * scaleY);
        int x1 = (int) Math.ceil((guiX + guiWidth) * scaleX);
        int y1 = (int) Math.ceil((guiY + guiHeight) * scaleY);
        int[] rect = new int[]{x0, y0, Math.max(1, x1 - x0), Math.max(1, y1 - y0)};

        int[] current = stack.peek();
        if (current != null) {
            int cx0 = Math.max(current[0], rect[0]);
            int cy0 = Math.max(current[1], rect[1]);
            int cx1 = Math.min(current[0] + current[2], rect[0] + rect[2]);
            int cy1 = Math.min(current[1] + current[3], rect[1] + rect[3]);
            rect = new int[]{cx0, cy0, Math.max(1, cx1 - cx0), Math.max(1, cy1 - cy0)};
        }
        stack.push(rect);

        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(rect[0], rect[1], rect[2], rect[3]);
    }

    /**
     * Clips to a rectangle already expressed in framebuffer pixels. Used by the
     * world projection, whose coordinates are framebuffer pixels rather than GUI
     * pixels.
     */
    public void pushPixels(int pixelsX, int pixelsY, int pixelsWidth, int pixelsHeight) {
        int[] rect = new int[]{pixelsX, pixelsY, Math.max(1, pixelsWidth), Math.max(1, pixelsHeight)};
        int[] current = stack.peek();
        if (current != null) {
            int cx0 = Math.max(current[0], rect[0]);
            int cy0 = Math.max(current[1], rect[1]);
            int cx1 = Math.min(current[0] + current[2], rect[0] + rect[2]);
            int cy1 = Math.min(current[1] + current[3], rect[1] + rect[3]);
            rect = new int[]{cx0, cy0, Math.max(1, cx1 - cx0), Math.max(1, cy1 - cy0)};
        }
        stack.push(rect);
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(rect[0], rect[1], rect[2], rect[3]);
    }

    /** Removes the innermost clip and restores the enclosing one. */
    public void pop() {
        if (stack.isEmpty()) {
            return;
        }
        stack.pop();
        int[] current = stack.peek();
        if (current == null) {
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
        } else {
            GL11.glScissor(current[0], current[1], current[2], current[3]);
        }
    }

    /** Drops every clip; used when a frame ends so a stray push cannot persist. */
    public void reset() {
        stack.clear();
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
    }

    public int depth() {
        return stack.size();
    }
}
