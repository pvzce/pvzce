package com.pvzce.client.renderer.font;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The TTF path: cmap parsing, metrics and glyph rasterisation.
 *
 * <p>Deliberately GL-free. Glyph <em>placement</em> is where this renderer can go
 * wrong in ways a screenshot would show but a human might not notice (a baseline
 * half a pixel off, an advance taken from the wrong face), and the parts that
 * decide placement - which glyph, how wide, how high above the baseline - are all
 * answerable without an atlas. The atlas upload itself is covered by
 * {@link GlyphAtlasTest} and the smoke screenshots.
 *
 * <p>The fonts are the bundled ones on purpose: a test against a synthetic TTF
 * would not catch the thing that actually bites, which is that these two files
 * publish their mapping as cmap format 4 <em>plus</em> format 12 and stb_truetype
 * can only read the former. They are loaded through {@link BundledFonts}, which
 * exists to keep each face to one load per JVM - see its comment.
 */
class TtfFaceTest {
    @Test
    void everyBundledFontLoadsAndCoversBothScripts() throws IOException {
        for (String file : new String[]{"zhanku.ttf", "noto_sans_sc_regular.ttf",
                "noto_sans_sc_medium.ttf", "noto_serif_sc_regular.ttf"}) {
            TtfFace face = BundledFonts.face(file);
            // Latin, digits and the characters every piece of UI text uses.
            for (int codepoint : new int[]{'A', 'z', '0', ' ', '中', '僵', '尸', '，', '。'}) {
                assertTrue(face.covers(codepoint),
                        file + " should cover U+" + Integer.toHexString(codepoint));
            }
            assertTrue(face.codepointCount() > 6000,
                    file + " should have a full CJK repertoire, has " + face.codepointCount());
        }
    }

    @Test
    void sansFontCoversAstralPlaneCharactersThroughCmapFormat12() throws IOException {
        TtfFace face = BundledFonts.face("noto_sans_sc_regular.ttf");
        // U+3106C is beyond the BMP and therefore unreachable through format 4;
        // stbtt_FindGlyphIndex reads formats 0/4/6 only, so this asserts that the
        // project's own cmap parser is the one answering.
        assertTrue(face.covers(0x3106C), "Noto Sans SC maps U+3106C (extension G)");
        assertFalse(face.covers(0x10FFFD), "and nothing maps a private-use plane");
    }

    @Test
    void zhankuStopsAtTheCommonHanziWhileNotoKeepsGoing() throws IOException {
        TtfFace display = BundledFonts.face("zhanku.ttf");
        TtfFace body = BundledFonts.face("noto_sans_sc_regular.ttf");
        // Extension A is the gap the display role's fallback chain exists for:
        // 站酷快乐体 has the 6763 common hanzi and nothing above them.
        assertTrue(display.covers(0x4E2D), "站酷 covers the common block");
        assertFalse(display.covers(0x3400), "站酷 has no extension A");
        assertTrue(body.covers(0x3400), "思源黑体 does, so the fallback can serve it");
        assertTrue(display.codepointCount() < body.codepointCount() / 3,
                "the display face is a subset of the body face: "
                        + display.codepointCount() + " vs " + body.codepointCount());
    }

    @Test
    void metricsScaleWithTheRequestedPixelSize() throws IOException {
        TtfFace face = BundledFonts.face("noto_sans_sc_regular.ttf");
        // Noto Sans SC's OS/2 typo metrics are 880/-120 per 1000 units, so a 40px em
        // is 35.2px above the baseline and 4.8px below it. The hhea metrics
        // (1160/-288, 1.45em) are what stb reports by default and are deliberately
        // not used: they carry Windows' GDI line spacing.
        assertEquals(35.2F, face.ascent(40F), 0.01F);
        assertEquals(4.8F, face.descent(40F), 0.01F);
        assertEquals(17.6F, face.ascent(20F), 0.01F);
        assertEquals(40F, face.ascent(40F) + face.descent(40F), 0.01F,
                "the typographic line is exactly one em, so a line box holds one line");
    }

    /**
     * The requested size is the em box - for the outlines, the advances and the
     * kerning alike.
     *
     * <p>Asking stb for a "scale for pixel height" answers a different question: it
     * scales the hhea line box (1160/-288, i.e. 1.448em, for this face) to the
     * requested size, so 中 would advance 27.6px at a nominal 40px and the body face
     * would come out 1.448x smaller than 站酷快乐体 at the same size - while every
     * line in the game is laid out from the OS/2 metrics above, which describe an em.
     */
    @Test
    void theRequestedPixelSizeIsTheEmBoxForMetricsAndOutlinesAlike() throws IOException {
        TtfFace face = BundledFonts.face("noto_sans_sc_regular.ttf");
        // 中 is a full-width glyph: 1000/1000 units of advance, i.e. exactly one em.
        assertEquals(40F, face.advance('中', 40F), 0.05F, "a hanzi advances one em");
        assertEquals(40F, face.rasterize('中', 40F).advance(), 0.05F,
                "and the rasteriser agrees with the measuring path");
        // Latin is proportional, and these are the face's own hmtx widths.
        assertEquals(29.12F, face.advance('H', 40F), 0.05F, "H is 0.728em");
        assertEquals(24.32F, face.advance('A', 40F), 0.05F, "A is 0.608em");
    }

    @Test
    void glyphInkSitsRelativeToTheBaselineAndAdvancesProportionally() throws IOException {
        TtfFace face = BundledFonts.face("noto_sans_sc_regular.ttf");
        TtfFace.Raster hanzi = face.rasterize('中', 40F);
        assertNotNull(hanzi);
        assertNotNull(hanzi.pixels(), "中 has ink");
        // Measured at 40px: the box is (2,-34)-(29,4), advance 40. 中 fills the top of
        // its em (0.84em of ink above the baseline) and dips a little below it, which is
        // the overshoot every CJK face draws rather than a placement error.
        assertEquals(34, hanzi.visualTop(), "ink height above the baseline");
        assertEquals(-4, hanzi.visualBottom(), "and the small overshoot below it");
        assertEquals(40F, hanzi.advance(), 0.05F);

        TtfFace.Raster latin = face.rasterize('H', 40F);
        assertNotNull(latin);
        // A capital sits on the baseline: its ink must not descend past it.
        assertEquals(0, latin.visualBottom(), "H does not descend below the baseline");
        assertEquals(30, latin.visualTop(), "cap height");
        assertTrue(latin.advance() < 30F, "Latin is proportional, narrower than a hanzi");

        TtfFace.Raster space = face.rasterize(' ', 40F);
        assertNotNull(space, "a space is a glyph with no ink, not a missing glyph");
        assertNull(space.pixels());
        assertTrue(space.advance() > 0F, "and it still advances");
    }

    @Test
    void rasterisedInkMatchesTheReportedBox() throws IOException {
        TtfFace face = BundledFonts.face("noto_sans_sc_regular.ttf");
        TtfFace.Raster raster = face.rasterize('W', 48F);
        assertNotNull(raster);
        assertNotNull(raster.pixels());
        // The coverage is tightly packed, one byte per pixel: that is exactly how
        // the atlas reads it, and the array is a copy so it stays valid as long as
        // the glyph cache holds it.
        assertEquals(raster.width() * raster.height(), raster.pixels().length,
                "coverage is width*height bytes");
        assertTrue(raster.width() > 8 && raster.height() > 8, "W has real ink at 48px");
        boolean anyInk = false;
        for (byte pixel : raster.pixels()) {
            if (pixel != 0) {
                anyInk = true;
                break;
            }
        }
        assertTrue(anyInk, "the rasterised coverage is not blank");
    }

    /**
     * A glyph's ink has to fit the line box its own metrics describe, or stacked lines
     * would collide - which is what "the em is the unit for both" buys.
     *
     * <p>Only for the glyphs that stay inside it, with a pixel of slack: stb rounds the
     * ink box outwards to whole pixels, so a comma whose tail is 0.107em below the
     * baseline can reach a fraction of a pixel past a 0.12em descender.
     *
     * <p>The glyphs that hang out on purpose are the Latin descenders: in these faces
     * they reach about 0.25em below the baseline while the OS/2 typographic descent is
     * 0.12em (that table is designed around CJK), so a 'g' hangs under its line box in
     * every renderer that lays text out by these metrics, this project's bitmap
     * predecessor included. The ink still clears the next line: a cap is only 0.73em
     * tall, so there is 0.27em of room below the baseline before it is touched.
     */
    @Test
    void lineBoxesHoldTheInkTheyAreBuiltFrom() throws IOException {
        for (String file : new String[]{"noto_sans_sc_regular.ttf", "zhanku.ttf"}) {
            TtfFace face = BundledFonts.face(file);
            float em = 40F;
            for (int codepoint : new int[]{'中', 'H', 'A', '0', '，'}) {
                TtfFace.Raster raster = face.rasterize(codepoint, em);
                assertNotNull(raster);
                assertTrue(raster.visualTop() <= face.ascent(em) + 1F,
                        file + ": " + (char) codepoint + " reaches above its ascender");
                assertTrue(-raster.visualBottom() <= face.descent(em) + 1F,
                        file + ": " + (char) codepoint + " hangs below its descender");
            }
        }
    }
}
