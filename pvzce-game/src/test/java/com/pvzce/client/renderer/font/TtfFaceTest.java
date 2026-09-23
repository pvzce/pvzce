package com.pvzce.client.renderer.font;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;

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
 * answerable without an atlas. The atlas upload itself is covered by the smoke
 * screenshots, which is the only place a GL context exists.
 *
 * <p>The fonts are the bundled ones on purpose: a test against a synthetic TTF
 * would not catch the thing that actually bites, which is that these two files
 * publish their mapping as cmap format 4 <em>plus</em> format 12 and stb_truetype
 * can only read the former.
 */
class TtfFaceTest {
    /** The shared face for a bundled font; the cache makes repeated lookups cheap. */
    private static TtfFace face(String file) throws IOException {
        return TtfFace.of(file, bundled(file));
    }

    private static byte[] bundled(String file) throws IOException {
        try (InputStream stream = TtfFaceTest.class.getClassLoader()
                .getResourceAsStream("assets/pvzce/font/" + file)) {
            assertNotNull(stream, "bundled font missing from resources: " + file);
            return stream.readAllBytes();
        }
    }

    /**
     * All four bundled faces, each loaded exactly once.
     *
     * <p>One pass rather than a parameterised method, so a single JVM loads each
     * font once and never frees one: repeatedly allocating and releasing a 10-15MB
     * font buffer makes glibc abort at process teardown on a tight address-space
     * limit ("too many chunks detected in tcache"), and that abort reproduces with a
     * probe that does nothing but call stb_truetype in a loop - no project code
     * involved, and identical under memAlloc, nmemAlloc and Unsafe.allocateMemory.
     * The game loads each face once and keeps it, so this is a test-harness
     * constraint, not a runtime one.
     */
    @Test
    void everyBundledFontLoadsAndCoversBothScripts() throws IOException {
        for (String file : new String[]{"zhanku.ttf", "noto_sans_sc_regular.ttf",
                "noto_sans_sc_medium.ttf", "noto_serif_sc_regular.ttf"}) {
            TtfFace face = face(file);
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
        TtfFace face = face("noto_sans_sc_regular.ttf");
            // U+3106C is beyond the BMP and therefore unreachable through format 4;
            // stbtt_FindGlyphIndex reads formats 0/4/6 only, so this asserts that the
            // project's own cmap parser is the one answering.
            assertTrue(face.covers(0x3106C), "Noto Sans SC maps U+3106C (extension G)");
            assertFalse(face.covers(0x10FFFD), "and nothing maps a private-use plane");
    }

    @Test
    void zhankuStopsAtTheCommonHanziWhileNotoKeepsGoing() throws IOException {
        TtfFace display = face("zhanku.ttf");
        TtfFace body = face("noto_sans_sc_regular.ttf");
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
        TtfFace face = face("noto_sans_sc_regular.ttf");
            // Noto Sans SC's OS/2 typo metrics are 880/-120 per 1000 units, so a
            // 40px em is 35.2px above the baseline and 4.8px below it. The hhea
            // metrics (1160/-288, 1.45em) are what stb reports by default and are
            // deliberately not used: they carry Windows' GDI line spacing.
            assertEquals(35.2F, face.ascent(40F), 0.01F);
            assertEquals(4.8F, face.descent(40F), 0.01F);
            assertEquals(17.6F, face.ascent(20F), 0.01F);
            assertEquals(40F, face.ascent(40F) + face.descent(40F), 0.01F,
                    "the typographic line is exactly one em, so a line box holds one line");
    }

    @Test
    void glyphInkSitsRelativeToTheBaselineAndAdvancesProportionally() throws IOException {
        TtfFace face = face("noto_sans_sc_regular.ttf");
            TtfFace.Raster hanzi = face.rasterize('中', 40F);
            assertNotNull(hanzi);
            assertNotNull(hanzi.pixels(), "中 has ink");
            // Measured at 40px: box (2,-24)-(25,3), advance 27.62. The ink sits above
            // the baseline with a small overshoot below it, and a hanzi in this face
            // advances about 0.69em - Noto Sans SC draws its hanzi narrower than a
            // full-width em, which is the one place it differs visibly from the
            // Noto Sans CJK the old bitmap atlas was baked from.
            assertEquals(24, hanzi.visualTop(), "ink height above the baseline");
            assertEquals(-3, hanzi.visualBottom(), "and the small overshoot below it");
            assertEquals(27.62F, hanzi.advance(), 0.05F);

            TtfFace.Raster latin = face.rasterize('H', 40F);
            assertNotNull(latin);
            // A capital sits on the baseline: its ink must not descend past it.
            assertEquals(0, latin.visualBottom(), "H does not descend below the baseline");
            assertEquals(21, latin.visualTop(), "cap height");
            assertTrue(latin.advance() < 27F, "Latin is proportional, narrower than a hanzi");

            TtfFace.Raster space = face.rasterize(' ', 40F);
            assertNotNull(space, "a space is a glyph with no ink, not a missing glyph");
            assertNull(space.pixels());
            assertTrue(space.advance() > 0F, "and it still advances");
    }

    @Test
    void rasterisedInkMatchesTheReportedBox() throws IOException {
        TtfFace face = face("noto_sans_sc_regular.ttf");
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
}
