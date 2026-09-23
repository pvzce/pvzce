package com.pvzce.client.renderer.font;

import com.pvzce.api.util.Identifier;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;

import java.nio.ByteBuffer;

/**
 * One 2048x2048 R8 glyph atlas page and its shelf packer.
 *
 * <p>Alpha only: a glyph bitmap is coverage, and the colour comes from the vertex,
 * so storing RGB would be three quarters waste. R8 is also exactly what
 * stb_truetype hands back, which means an insertion is one buffer copy and one
 * {@code glTexSubImage2D} with no conversion pass in between.
 *
 * <p>Packing is shelves (rows) rather than a general bin packer: every glyph of a
 * given pixel size is about the same height, so shelves waste almost nothing and
 * an insertion is O(1). Each glyph gets a {@link #GUTTER}-pixel transparent
 * margin so the outline pass - which samples up to two pixels away - cannot read
 * a neighbouring glyph.
 */
final class GlyphPage {
    static final int SIZE = 2048;
    /**
     * Blank pixels reserved on the right and bottom of every glyph. Two is the
     * widest outline the API accepts, and linear filtering reads half a texel
     * beyond that again, so the gutter is the difference between a clean outline
     * and a faint ghost of the glyph next door.
     */
    static final int GUTTER = 2;

    private final Identifier id;
    private final int glId;
    private final ByteBuffer pixels;
    /** Next free x in the current shelf, and the shelf's top edge and height. */
    private int cursorX;
    private int cursorY;
    private int shelfHeight;
    private boolean closed;
    /** Lazily built once; a glyph draw must not allocate one handle per quad. */
    private com.pvzce.client.renderer.texture.Texture handle;

    GlyphPage(Identifier id) {
        this.id = id;
        this.pixels = BufferUtils.createByteBuffer(SIZE * SIZE * 4);
        glId = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, glId);
        // The binding cache has to hear about this: it is what stops a later
        // RenderSystem.bindTexture of the previously bound texture from being skipped
        // as redundant and leaving this page sampled instead.
        com.pvzce.client.renderer.RenderSystem.noteTextureBound(glId);
        // GL_LINEAR for both: the glyph was rasterised at the size it is drawn at, so
        // filtering only softens the edge by half a texel. GL_LINEAR (not NEAREST) also
        // keeps a scaled or non-integer-positioned line from aliasing.
        // NEAREST is selectable as a diagnostic: a sampling artefact that disappears under
        // NEAREST is a filtering problem, not a data problem.
        int filter = Boolean.getBoolean("pvzce.fontNearest") ? GL11.GL_NEAREST : GL11.GL_LINEAR;
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, filter);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, filter);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_BASE_LEVEL, 0);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, 0);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, SIZE, SIZE, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
    }

    Identifier id() {
        return id;
    }

    int glId() {
        return glId;
    }

    /**
     * A handle for the sprite pipeline, created once per page. The texture has no
     * size to report - it is uploaded raw rather than decoded from a PNG - so the
     * extent is the page size and the UVs come from the {@link Slot}.
     */
    com.pvzce.client.renderer.texture.Texture texture() {
        if (handle == null) {
            handle = new com.pvzce.client.renderer.texture.Texture(id, glId, SIZE, SIZE);
        }
        return handle;
    }

    boolean closed() {
        return closed;
    }

    /**
     * Reserves a cell for a glyph and copies its ink into it.
     *
     * @return the cell, or null when the page is too full to hold it
     */
    Slot insert(TtfFace.Raster raster) {
        if (closed) {
            return null;
        }
        int cellWidth = raster.width() + GUTTER;
        int cellHeight = raster.height() + GUTTER;
        if (cellWidth > SIZE || cellHeight > SIZE) {
            // A glyph taller or wider than a whole page: refuse it rather than
            // growing pages forever. At the sizes the UI asks for this cannot happen.
            return null;
        }
        if (cursorX + cellWidth > SIZE) {
            cursorY += shelfHeight;
            cursorX = 0;
            shelfHeight = 0;
        }
        if (cursorY + cellHeight > SIZE) {
            return null;
        }
        int x = cursorX;
        int y = cursorY;
        cursorX += cellWidth;
        shelfHeight = Math.max(shelfHeight, cellHeight);

        // Clear the reserved cell first, gutter included. The outline pass samples
        // those pixels, so they have to be transparent no matter what glyph used to
        // live here before a page was recycled.
        for (int row = 0; row < cellHeight; row++) {
            int rowStart = (y + row) * SIZE + x;
            for (int column = 0; column < cellWidth; column++) {
                pixels.put((rowStart + column) * 4, (byte) 0);
            }
        }
        copyInk(raster, x, y);

        GL11.glBindTexture(GL11.GL_TEXTURE_2D, glId);
        com.pvzce.client.renderer.RenderSystem.noteTextureBound(glId);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
        // The cell is a rectangle of the page, so the upload reads the page buffer
        // with its own row length instead of a repacked copy: the buffer is
        // positioned at the cell's first pixel and GL is told to stride by the page
        // width. `slice()` keeps the original buffer's position untouched.
        GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, SIZE);
        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, x, y, cellWidth, cellHeight,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE,
                pixels.slice((y * SIZE + x) * 4, cellWidth * cellHeight * 4));
        GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
        return new Slot(this, x, y, raster.width(), raster.height());
    }

    void close() {
        if (!closed) {
            closed = true;
            GL11.glDeleteTextures(glId);
        }
    }

    /** A rectangle inside one page, in texels. */
    record Slot(GlyphPage page, int x, int y, int width, int height) {
        float u0() {
            return x / (float) SIZE;
        }

        float u1() {
            return (x + width) / (float) SIZE;
        }

        /** Bottom edge in texture space; the page buffer is uploaded top-down. */
        float v0() {
            return 1F - (y + height) / (float) SIZE;
        }

        float v1() {
            return 1F - y / (float) SIZE;
        }
    }

    /**
     * Copies stb's bitmap into the page. stb's rows are tightly packed
     * ({@code gbm.stride = gbm.w}) and it is vertically flipped relative to the
     * page buffer's upload direction, which is why the row index is mirrored.
     */
    private void copyInk(TtfFace.Raster raster, int x, int y) {
        byte[] source = raster.pixels();
        if (source == null) {
            return;
        }
        int width = raster.width();
        int height = raster.height();
        for (int row = 0; row < height; row++) {
            // stb's first row is the TOP of the ink and the page's first row is the top
            // of the texture, so rows go in the same order. The page texture is sampled
            // with v0 < v1 (see Slot), i.e. texture row 0 is v = 1, which is the top -
            // STBImage is told to flip PNGs for exactly this reason.
            int destination = ((y + row) * SIZE + x) * 4;
            int sourceRow = row * width;
            for (int column = 0; column < width; column++) {
                int coverage = source[sourceRow + column] & 0xFF;
                int at = destination + column * 4;
                pixels.put(at, (byte) coverage);
                pixels.put(at + 1, (byte) coverage);
                pixels.put(at + 2, (byte) coverage);
                pixels.put(at + 3, (byte) 0xFF);
            }
        }
    }

    /** Diagnostic: how full the page is, as a fraction of its height. */
    float occupancy() {
        return (cursorY + shelfHeight) / (float) SIZE;
    }

    /**
     * Writes the page to a PNG. The atlas is uploaded raw rather than decoded from a
     * file, so when glyphs come out wrong there is otherwise nothing to look at -
     * {@code -Dpvzce.dumpFontAtlas=<dir>} calls this on the first frame that draws text.
     */
    /**
     * Reads the texture back from the GPU and writes it as a PNG, next to the CPU
     * copy. When the two disagree, the upload is at fault rather than the rasteriser.
     */
    void dumpFromGl(java.nio.file.Path path) throws java.io.IOException {
        java.nio.ByteBuffer readBack = org.lwjgl.BufferUtils.createByteBuffer(SIZE * SIZE * 4);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, glId);
        com.pvzce.client.renderer.RenderSystem.noteTextureBound(glId);
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
        GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, readBack);
        java.awt.image.BufferedImage image =
                new java.awt.image.BufferedImage(SIZE, SIZE, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                int coverage = readBack.get((y * SIZE + x) * 4) & 0xFF;
                image.setRGB(x, y, (coverage << 24) | 0x00FFFFFF);
            }
        }
        javax.imageio.ImageIO.write(image, "png", path.toFile());
    }

    void dump(java.nio.file.Path path) throws java.io.IOException {
        java.awt.image.BufferedImage image =
                new java.awt.image.BufferedImage(SIZE, SIZE, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                int coverage = pixels.get((y * SIZE + x) * 4) & 0xFF;
                image.setRGB(x, y, (coverage << 24) | 0x00FFFFFF);
            }
        }
        javax.imageio.ImageIO.write(image, "png", path.toFile());
    }
}
