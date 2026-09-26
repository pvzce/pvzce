package com.pvzce.client;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.stb.STBImageWrite;

import java.nio.ByteBuffer;

/**
 * Reading the frame back and writing it as a PNG.
 *
 * <p>The one implementation, used by the F2 key and by the smoke hooks: taking a screenshot is
 * {@code glReadPixels} plus a row flip plus a PNG write, and the flip is the part that is wrong in
 * every first attempt (the framebuffer's origin is the bottom-left and a PNG's is the top-left).
 *
 * <p><b>It must run after the frame was drawn.</b> Reading in the input phase returns the previous
 * frame - which is what the smoke driver's "capture" hook exists to get right, and why the key sets
 * a flag the render path consumes rather than reading there and then.
 */
public final class FramebufferCapture {
    private FramebufferCapture() {
    }

    /**
     * Writes the current framebuffer to a PNG.
     *
     * @param path the file to write; its parent directory must exist
     * @return true when the file was written
     */
    public static boolean writePng(String path, int width, int height) {
        if (path == null || width <= 0 || height <= 0) {
            return false;
        }
        ByteBuffer pixels = BufferUtils.createByteBuffer(width * height * 4);
        GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
        ByteBuffer flipped = BufferUtils.createByteBuffer(width * height * 4);
        byte[] row = new byte[width * 4];
        for (int y = height - 1; y >= 0; y--) {
            pixels.get(width * y * 4, row, 0, width * 4);
            flipped.put(row);
        }
        flipped.flip();
        STBImageWrite.stbi_flip_vertically_on_write(false);
        return STBImageWrite.stbi_write_png(path, width, height, 4, flipped, width * 4);
    }
}
