package com.pvzce.client.renderer.font;

import org.lwjgl.BufferUtils;

import java.nio.ByteBuffer;

/**
 * The CPU half of a glyph atlas page: the coverage buffer and the shelf packer that
 * fills it.
 *
 * <p>Deliberately free of GL, so "which texel does this glyph occupy, and is its ink
 * the right way up" is answerable in a unit test - {@link GlyphPage} owns the
 * texture and uploads what this class wrote.
 *
 * <h2>Two row orders, one place they meet</h2>
 * Everything here is written and read in <em>image</em> coordinates: {@code (0, 0)}
 * is the top-left texel, the way the packed glyphs are meant to look and the way the
 * diagnostics are dumped. The buffer itself is in <em>upload</em> order, which is
 * flipped: GL reads the first row of the data it is handed as t=0, i.e. the bottom
 * of the texture, so the image's top row has to be written last. Every accessor goes
 * through {@link #dataRow(int)}, so the two cannot drift apart.
 *
 * <p>Image order is also what the rest of the renderer expects:
 * {@code TextureManager} loads PNGs with {@code stbi_set_flip_vertically_on_load},
 * so a texture's v=1 is the top of its image, and {@link GlyphPage.Slot} derives its
 * v from that same rule - a glyph atlas uploaded top-down would be sampled mirrored,
 * which is exactly the bug this class exists to prevent.
 *
 * <h2>Coverage lives in every channel</h2>
 * A glyph's coverage is written to all four channels of its texels - white RGB plus
 * alpha - because the sprite shader multiplies the sample by the vertex colour and
 * takes its opacity from the sample's alpha, while the text-effect shader branch
 * reads the coverage back out on its own. All four channels carrying the same number
 * is what makes both paths agree; see {@link #blit}.
 */
final class GlyphAtlas {
    /** Page edge, in texels. */
    static final int SIZE = 2048;
    /**
     * Blank pixels reserved around every glyph. Two is the widest outline the API
     * accepts, and linear filtering reads half a texel beyond that again, so the
     * gutter is the difference between a clean outline and a faint ghost of the
     * glyph next door.
     */
    static final int GUTTER = 2;

    /** One glyph's ink rectangle, in image coordinates. */
    record Cell(int x, int y, int width, int height) {
    }

    private final ByteBuffer pixels = BufferUtils.createByteBuffer(SIZE * SIZE * 4);
    /**
     * Next free x in the current shelf, the shelf's top edge, and the tallest cell in
     * it. The page starts one gutter in from both edges: the outline pass samples up
     * to {@link #GUTTER} texels away, and a glyph pushed against row 0 or column 0
     * would be sampled with GL_CLAMP_TO_EDGE, i.e. against itself, so its outline
     * would be missing on that side. Every later cell gets its left and top margin
     * from the previous cell's gutter instead.
     */
    private int cursorX = GUTTER;
    private int cursorY = GUTTER;
    private int shelfHeight;

    /**
     * Reserves a cell for a glyph and copies its ink into it.
     *
     * @return the cell, or null when the page is too full to hold it
     */
    Cell insert(TtfFace.Raster raster) {
        int cellWidth = raster.width() + GUTTER;
        int cellHeight = raster.height() + GUTTER;
        if (cellWidth > SIZE || cellHeight > SIZE) {
            // A glyph taller or wider than a whole page: refuse it rather than growing
            // pages forever. At the sizes the UI asks for this cannot happen.
            return null;
        }
        if (cursorX + cellWidth > SIZE) {
            cursorY += shelfHeight;
            cursorX = GUTTER;
            shelfHeight = 0;
        }
        if (cursorY + cellHeight > SIZE) {
            return null;
        }
        Cell cell = new Cell(cursorX, cursorY, raster.width(), raster.height());
        cursorX += cellWidth;
        shelfHeight = Math.max(shelfHeight, cellHeight);
        blit(raster, cell);
        return cell;
    }

    /** How full the page is, as a fraction of its height; for diagnostics. */
    float occupancy() {
        return (cursorY + shelfHeight) / (float) SIZE;
    }

    /** Coverage at an image-space texel, 0..255; for tests and diagnostics. */
    int coverage(int imageX, int imageY) {
        return pixels.get((dataRow(imageY) * SIZE + imageX) * 4 + 3) & 0xFF;
    }

    /**
     * The red channel at an image-space texel. White wherever a glyph put ink and 0 in
     * the gutter, which is what makes an atlas page usable by the ordinary sprite
     * path: it multiplies this by the vertex colour.
     */
    int red(int imageX, int imageY) {
        return pixels.get((dataRow(imageY) * SIZE + imageX) * 4) & 0xFF;
    }

    /**
     * The buffer row a cell's upload has to start at: the row holding the cell's
     * <em>last</em> image row, because the rows GL is handed run upwards through the
     * buffer while the image runs down the page.
     */
    int uploadRow(Cell cell) {
        return dataRow(cell.y() + cell.height() + GUTTER - 1);
    }

    /**
     * The window onto the buffer a cell's {@code glTexSubImage2D} has to be handed:
     * it starts at the cell's {@link #uploadRow upload row} and is as long as GL will
     * actually read with {@code GL_UNPACK_ROW_LENGTH} set, i.e. {@code cellHeight}
     * rows of {@code SIZE} texels rather than {@code cellWidth * cellHeight}. Sizing a
     * slice by the cell alone is what makes GL read past the end of the buffer.
     */
    ByteBuffer uploadSlice(Cell cell) {
        int cellWidth = cell.width() + GUTTER;
        int cellHeight = cell.height() + GUTTER;
        int offset = (uploadRow(cell) * SIZE + cell.x()) * 4;
        int length = ((cellHeight - 1) * SIZE + cellWidth) * 4;
        return pixels.slice(offset, length);
    }

    /** Writes the page to a PNG in image orientation, coverage as alpha. */
    void writePng(java.nio.file.Path path) throws java.io.IOException {
        writePng(path, pixels);
    }

    /**
     * The whole page in upload order, for the one upload that initialises a texture.
     *
     * <p>A page is never only written cell by cell: the margin the first shelf starts in,
     * and the far edges a clamped sample can reach, are never written again - and they are
     * exactly what the outline pass reads around the first glyphs. Creating the texture
     * with a null pointer would leave those texels undefined; handing over the (zeroed)
     * buffer also means the CPU copy and a GPU readback start out identical, which is the
     * comparison the dumps exist for.
     */
    ByteBuffer wholePage() {
        return pixels.duplicate();
    }

    /**
     * Writes a page-sized coverage buffer to a PNG in image orientation. Both the CPU
     * copy and a {@code glGetTexImage} readback are in upload order (row 0 is t=0), so
     * both go through the same row mapping as every other reader.
     */
    static void writePng(java.nio.file.Path path, ByteBuffer source) throws java.io.IOException {
        java.awt.image.BufferedImage image =
                new java.awt.image.BufferedImage(SIZE, SIZE, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < SIZE; y++) {
            int row = dataRow(y);
            for (int x = 0; x < SIZE; x++) {
                int coverage = source.get((row * SIZE + x) * 4 + 3) & 0xFF;
                image.setRGB(x, y, (coverage << 24) | 0x00FFFFFF);
            }
        }
        javax.imageio.ImageIO.write(image, "png", path.toFile());
    }

    /**
     * The buffer row that holds an image row.
     *
     * <p>GL reads the first row of the data it is given as the bottom of the texture
     * (t=0) - which is why this project uploads PNGs flipped - so the image's top row
     * is the buffer's last.
     */
    static int dataRow(int imageRow) {
        return SIZE - 1 - imageRow;
    }

    /**
     * Writes one raster's coverage into its cell.
     *
     * <p>The whole cell is cleared first, gutter included: the outline pass samples
     * those texels, so they have to be transparent no matter what used to live there.
     * The clear writes all four channels at once, because a stale alpha of 0xFF would
     * be an opaque gutter and a stale RGB would tint a glyph drawn over it.
     */
    private void blit(TtfFace.Raster raster, Cell cell) {
        int cellWidth = cell.width() + GUTTER;
        int cellHeight = cell.height() + GUTTER;
        for (int row = 0; row < cellHeight; row++) {
            int at = (dataRow(cell.y() + row) * SIZE + cell.x()) * 4;
            for (int column = 0; column < cellWidth; column++) {
                pixels.putInt(at + column * 4, 0);
            }
        }
        byte[] source = raster.pixels();
        if (source == null) {
            return;
        }
        int width = raster.width();
        for (int row = 0; row < raster.height(); row++) {
            // stb's first row is the top of the ink and the image's first row is the
            // top of the page, so the two run in the same order; dataRow does the flip
            // into upload order and nothing else here has to care.
            int at = (dataRow(cell.y() + row) * SIZE + cell.x()) * 4;
            int sourceRow = row * width;
            for (int column = 0; column < width; column++) {
                int coverage = source[sourceRow + column] & 0xFF;
                // White RGB plus coverage alpha: see the class comment.
                pixels.put(at + column * 4, (byte) 0xFF);
                pixels.put(at + column * 4 + 1, (byte) 0xFF);
                pixels.put(at + column * 4 + 2, (byte) 0xFF);
                pixels.put(at + column * 4 + 3, (byte) coverage);
            }
        }
    }
}
