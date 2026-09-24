package com.pvzce.client.renderer.font;

import com.pvzce.api.util.Identifier;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import java.nio.ByteBuffer;

/**
 * One 2048x2048 glyph atlas page on the GPU: the texture and the uploads into it.
 *
 * <p>The texels themselves - the shelf packer, the coverage buffer, and the row order
 * that keeps a glyph upright - belong to {@link GlyphAtlas}, which needs no GL and is
 * therefore testable. This class is the half that cannot exist without a context:
 * it owns the texture, uploads the cells the packer hands back, and can read the
 * result back next to the CPU copy.
 *
 * <p>What GL is told is "here is a rectangle of rows, {@code GL_UNPACK_ROW_LENGTH} is
 * the page width": the rows travel in upload order, which is the image flipped, so
 * the rectangle lands mirrored inside the texture exactly where {@link Slot}'s v
 * expects it. See {@link GlyphAtlas} for why the atlas is stored that way.
 */
final class GlyphPage {
    static final int SIZE = GlyphAtlas.SIZE;
    static final int GUTTER = GlyphAtlas.GUTTER;

    private final Identifier id;
    private final GlyphAtlas atlas = new GlyphAtlas();
    private final int glId;
    private boolean closed;
    /** Lazily built once; a glyph draw must not allocate one handle per quad. */
    private com.pvzce.client.renderer.texture.Texture handle;

    GlyphPage(Identifier id) {
        this.id = id;
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
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
        // The page starts as the (transparent) CPU buffer rather than undefined contents:
        // see {@link GlyphAtlas#wholePage}.
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, SIZE, SIZE, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, atlas.wholePage());
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
     * Reserves a cell for a glyph, copies its ink into the page and uploads the
     * touched rectangle.
     *
     * @return the cell, or null when the page is too full to hold it
     */
    Slot insert(TtfFace.Raster raster) {
        if (closed) {
            return null;
        }
        GlyphAtlas.Cell cell = atlas.insert(raster);
        if (cell == null) {
            return null;
        }
        upload(cell);
        return new Slot(this, cell.x(), cell.y(), cell.width(), cell.height());
    }

    void close() {
        if (!closed) {
            closed = true;
            GL11.glDeleteTextures(glId);
        }
    }

    /** A rectangle inside one page, in texels of the image before it was flipped. */
    record Slot(GlyphPage page, int x, int y, int width, int height) {
        float u0() {
            return x / (float) SIZE;
        }

        float u1() {
            return (x + width) / (float) SIZE;
        }

        /** Bottom edge in texture space; the page is uploaded flipped, as PNGs are. */
        float v0() {
            return 1F - (y + height) / (float) SIZE;
        }

        float v1() {
            return 1F - y / (float) SIZE;
        }
    }

    /** Diagnostic: how full the page is, as a fraction of its height. */
    float occupancy() {
        return atlas.occupancy();
    }

    /**
     * Writes the CPU copy of the page to a PNG. The atlas is uploaded raw rather than
     * decoded from a file, so when glyphs come out wrong there is otherwise nothing to
     * look at - {@code -Dpvzce.dumpFontAtlas=<dir>} calls this on the first frame that
     * draws text.
     */
    void dump(java.nio.file.Path path) throws java.io.IOException {
        atlas.writePng(path);
    }

    /**
     * Reads the texture back from the GPU and writes it as a PNG, next to the CPU
     * copy. When the two disagree, the upload is at fault rather than the rasteriser.
     */
    void dumpFromGl(java.nio.file.Path path) throws java.io.IOException {
        ByteBuffer readBack = BufferUtils.createByteBuffer(SIZE * SIZE * 4);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, glId);
        com.pvzce.client.renderer.RenderSystem.noteTextureBound(glId);
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
        GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, readBack);
        // glGetTexImage hands the rows back in the same order they were uploaded
        // (t=0 first), which is what the writer maps out of.
        GlyphAtlas.writePng(path, readBack);
    }

    /**
     * Uploads one cell.
     *
     * <p>The cell is a rectangle of the page, so the upload reads the page buffer with
     * its own row length instead of a repacked copy: the buffer is positioned at the
     * cell's first row and GL is told to stride by the page width.
     */
    private void upload(GlyphAtlas.Cell cell) {
        int cellWidth = cell.width() + GUTTER;
        int cellHeight = cell.height() + GUTTER;
        int row = atlas.uploadRow(cell);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, glId);
        com.pvzce.client.renderer.RenderSystem.noteTextureBound(glId);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
        GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, SIZE);
        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, cell.x(), row, cellWidth, cellHeight,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, atlas.uploadSlice(cell));
        GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
    }
}
