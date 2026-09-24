package com.pvzce.client.renderer.font;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The glyph atlas: where a glyph's ink lands, which way up it is, and what the
 * renderer's UVs actually sample from it.
 *
 * <p>Deliberately GL-free, like {@link GlyphAtlas} itself. The failure this file is
 * here to prevent is not "the font failed to load" but the one that shipped: an atlas
 * whose cover letter was in the wrong channel and whose rows ran the other way, which
 * looks like a solid rectangle on screen and like perfectly good ink in a dump.
 */
class GlyphAtlasTest {
    /**
     * A raster whose texels are all identifiable - coverage is row-major and non-zero -
     * so a flipped, shifted or clipped blit cannot pass by accident. Keep the texel
     * count below 256 so no two texels share a value.
     */
    private static TtfFace.Raster pattern(int width, int height) {
        byte[] pixels = new byte[width * height];
        for (int row = 0; row < height; row++) {
            for (int column = 0; column < width; column++) {
                pixels[row * width + column] = (byte) (1 + row * width + column);
            }
        }
        return new TtfFace.Raster(pixels, width, height, 0, height, 0, width);
    }

    /** The quad a caller would draw for a raster, at toGui 1, so texels are pixels. */
    private static GlyphQuad quadFor(GlyphAtlas.Cell cell, TtfFace.Raster raster, float baseline) {
        GlyphPage.Slot slot = new GlyphPage.Slot(null, cell.x(), cell.y(), cell.width(), cell.height());
        // A face is not part of the geometry, and the slot's page is only consulted when
        // the texture is bound, so both are null here on purpose. Every other component
        // is what the real pipeline would have.
        GlyphRef glyph = new GlyphRef(null, slot, raster.advance(), raster.left(),
                raster.visualTop(), raster.visualBottom());
        return GlyphQuad.of(glyph, 12F, baseline, 1F);
    }

    /**
     * The coverage the GPU ends up with for a sample at one uv, computed the way GL
     * actually places the data: {@link GlyphAtlas#uploadSlice} is handed to
     * {@code glTexSubImage2D} at the row {@link GlyphAtlas#uploadRow} names, so data row
     * {@code d} of that slice becomes texel row {@code uploadRow + d} - and texel row
     * {@code t} covers v in {@code [t/SIZE, (t+1)/SIZE]} counting from the bottom, which
     * is where the first row of an upload goes. {@code GL_UNPACK_ROW_LENGTH} is the page
     * width, so a slice row is {@code SIZE} texels whatever the cell's width.
     *
     * <p>This deliberately reads the bytes that are uploaded rather than the decoded page:
     * a bug in the upload geometry (the wrong row, a flipped rectangle) is exactly what
     * this file exists to catch.
     */
    private static int uploadedCoverage(GlyphAtlas atlas, GlyphAtlas.Cell cell, float u, float v) {
        int cellWidth = cell.width() + GlyphAtlas.GUTTER;
        int cellHeight = cell.height() + GlyphAtlas.GUTTER;
        int sliceColumn = (int) (u * GlyphAtlas.SIZE) - cell.x();
        int sliceRow = (int) (v * GlyphAtlas.SIZE) - atlas.uploadRow(cell);
        if (sliceColumn < 0 || sliceColumn >= cellWidth || sliceRow < 0 || sliceRow >= cellHeight) {
            return 0;
        }
        return atlas.uploadSlice(cell).get((sliceRow * GlyphAtlas.SIZE + sliceColumn) * 4 + 3) & 0xFF;
    }

    /**
     * The coverage a GPU reads for the fragment at the centre of one ink texel: the
     * quad's uv rectangle is interpolated across the quad, so texel {@code (column, row)}
     * of the glyph - counted from its top-left - sits at
     * {@code u0 + (column + 0.5) / SIZE} across and {@code v1 - (row + 0.5) / SIZE} up.
     */
    private static int sample(GlyphAtlas atlas, GlyphAtlas.Cell cell, GlyphQuad quad,
                              int column, int row) {
        return uploadedCoverage(atlas, cell,
                quad.u0() + (column + 0.5F) / GlyphAtlas.SIZE,
                quad.v1() - (row + 0.5F) / GlyphAtlas.SIZE);
    }

    @Test
    void inkLandsInTheCellItsRasterAskedFor() {
        GlyphAtlas atlas = new GlyphAtlas();
        TtfFace.Raster raster = pattern(8, 5);
        GlyphAtlas.Cell cell = atlas.insert(raster);

        assertNotNull(cell, "a small glyph fits an empty page");
        for (int row = 0; row < raster.height(); row++) {
            for (int column = 0; column < raster.width(); column++) {
                int ink = cell.x() + column;
                int scanline = cell.y() + row;
                assertEquals(raster.pixels()[row * raster.width() + column] & 0xFF,
                        atlas.coverage(ink, scanline),
                        "coverage of ink texel (" + column + "," + row + ")");
                assertEquals(0xFF, atlas.red(ink, scanline),
                        "the ink is white, because the sprite path multiplies it by the "
                                + "vertex colour");
            }
        }
    }

    @Test
    void everyReservedTexelAroundAGlyphIsTransparent() {
        GlyphAtlas atlas = new GlyphAtlas();
        TtfFace.Raster raster = pattern(4, 3);
        GlyphAtlas.Cell cell = atlas.insert(raster);

        for (int row = -GlyphAtlas.GUTTER; row < raster.height() + GlyphAtlas.GUTTER; row++) {
            for (int column = -GlyphAtlas.GUTTER; column < raster.width() + GlyphAtlas.GUTTER;
                    column++) {
                boolean onInk = row >= 0 && row < raster.height()
                        && column >= 0 && column < raster.width();
                int x = cell.x() + column;
                int y = cell.y() + row;
                if (onInk || x < 0 || y < 0) {
                    continue;
                }
                // Both channels: a stale alpha is an opaque halo around the glyph, and a
                // stale colour is a tint on whatever is drawn over the page.
                assertEquals(0, atlas.coverage(x, y), "alpha of the gutter at (" + x + "," + y + ")");
                assertEquals(0, atlas.red(x, y), "colour of the gutter at (" + x + "," + y + ")");
            }
        }
    }

    /**
     * The screen-shaped question: sample the atlas at the UVs the renderer computes for
     * each ink texel and check that texel comes back.
     *
     * <p>An atlas uploaded in the opposite row order still holds the ink - it is just
     * sampled mirrored, so a glyph whose ink sits at the top of its box comes out mostly
     * empty with another glyph's ink bleeding in from the far end of the page.
     */
    @Test
    void theQuadsUvsSampleTheirOwnInkTheRightWayUp() {
        GlyphAtlas atlas = new GlyphAtlas();
        TtfFace.Raster raster = pattern(6, 4);
        GlyphAtlas.Cell cell = atlas.insert(raster);
        GlyphQuad quad = quadFor(cell, raster, 30F);

        for (int row = 0; row < raster.height(); row++) {
            for (int column = 0; column < raster.width(); column++) {
                assertEquals(raster.pixels()[row * raster.width() + column] & 0xFF,
                        sample(atlas, cell, quad, column, row),
                        "the texel " + row + " rows below the top of the glyph's ink");
            }
        }
    }

    /**
     * The quad's rectangle IS the ink box, so its edges have to land where the font's
     * metrics say they are - for every glyph, including the ones that hang below the
     * baseline. A sign error here draws the whole face slightly too high, which is
     * invisible in a dump and obvious in a line of CJK punctuation.
     */
    @Test
    void glyphQuadsSitOnTheBaselineTheirMetricsAskFor() throws IOException {
        TtfFace body = BundledFonts.face("noto_sans_sc_regular.ttf");
        float em = 40F;
        float baseline = 100F;

        for (int codepoint : new int[]{'中', 'H', '，'}) {
            TtfFace.Raster raster = body.rasterize(codepoint, em);
            assertNotNull(raster, "expected ink for U+" + Integer.toHexString(codepoint));
            GlyphAtlas atlas = new GlyphAtlas();
            GlyphAtlas.Cell cell = atlas.insert(raster);
            GlyphQuad quad = quadFor(cell, raster, baseline);

            assertEquals(baseline + raster.visualBottom(), quad.bottom(), 0.01F,
                    "bottom edge of " + (char) codepoint);
            assertEquals(baseline + raster.visualTop(), quad.bottom() + quad.height(), 0.01F,
                    "top edge of " + (char) codepoint);
            assertEquals(raster.pixels()[0] & 0xFF, sample(atlas, cell, quad, 0, 0),
                    "the top-left ink texel of " + (char) codepoint);
        }

        TtfFace.Raster hanzi = body.rasterize('中', em);
        assertTrue(hanzi.visualBottom() < 0, "中 dips below the baseline in this face");
        assertTrue(hanzi.visualTop() < body.ascent(em),
                "and its ink stops short of the ascender the line box is built from");
    }

    /**
     * The anchor a caller hands over is the baseline, not the top of a line box: the ink
     * sits on it, reaching up by the font's own ascent and dipping below it only by the
     * little a hanzi overshoots.
     *
     * <p>This is the contract every text call site in the project is spaced against (a label
     * centred in a box, a plate sized around a line), so a renderer that anchored at the top
     * of the box instead - which the TTF rewrite did at first - drops the whole UI by an
     * ascent, and that is a whole line's worth of drift in a button.
     */
    @Test
    void glyphInkSitsOnTheAnchorRatherThanUnderIt() throws IOException {
        for (String file : new String[]{"noto_sans_sc_regular.ttf", "zhanku.ttf"}) {
            TtfFace face = BundledFonts.face(file);
            float em = 40F;
            float anchor = 100F;
            TtfFace.Raster raster = face.rasterize('中', em);
            assertNotNull(raster);
            GlyphQuad quad = quadFor(new GlyphAtlas().insert(raster), raster, anchor);

            assertTrue(quad.bottom() > anchor - face.descent(em),
                    file + ": the ink must not hang a whole descent below the anchor");
            assertTrue(quad.bottom() + quad.height() > anchor,
                    file + ": the ink is above the anchor, not below it");
            assertTrue(quad.bottom() + quad.height() - anchor <= face.ascent(em),
                    file + ": and it stops within the line's ascent");
        }
    }

    @Test
    void aGlyphBiggerThanTheWholePageIsRefusedRatherThanWrapped() {
        GlyphAtlas atlas = new GlyphAtlas();
        assertNull(atlas.insert(new TtfFace.Raster(new byte[4], GlyphAtlas.SIZE + 1, 1,
                0, 1, 0, 1F)));
    }

    @Test
    void aFullPageReportsNoRoomWithoutOverwritingWhatItHolds() {
        GlyphAtlas atlas = new GlyphAtlas();
        TtfFace.Raster raster = pattern(16, 16);
        List<GlyphAtlas.Cell> cells = new ArrayList<>();
        GlyphAtlas.Cell cell;
        while ((cell = atlas.insert(raster)) != null) {
            cells.add(cell);
        }

        assertTrue(cells.size() > 20, "a 2048-wide page holds many 16px glyphs");
        GlyphAtlas.Cell first = cells.get(0);
        assertEquals(raster.pixels()[0] & 0xFF, atlas.coverage(first.x(), first.y()),
                "refusing a glyph must not touch what is already packed");
    }
}
