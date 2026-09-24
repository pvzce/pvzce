package com.pvzce.client.renderer.font;

import com.pvzce.common.resource.PvzceResourceManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The roles' sizes, and the two questions the rest of the UI asks text: how wide is
 * this, and where does it wrap.
 *
 * <p>Nothing here rasterises, which is what lets a real {@link Fonts} be built without
 * a GL context: constructing one only loads the faces, measuring goes straight to the
 * font's own advances, and wrapping only measures. The em constants are the part worth
 * pinning - they are the whole difference between "text the size every panel in this
 * project was built for" and "text five times too big" - and measuring is the part
 * worth pinning twice, because a wrap that disagrees with {@code width} draws text
 * outside the box it was measured into.
 *
 * <p>The renderer is deliberately <em>not</em> closed: faces are process-wide and a
 * closed one would leave the next test (and the next load) without a font. See
 * {@link TtfFace#close}.
 */
class FontRendererTest {
    /** The bitmap atlas this replaced drew a 98px font into 18-unit lines, 中 inked 94px. */
    private static final float OLD_INK_GUI = 94F * 18F / 128F;

    private static Fonts fonts(Path gameDir) throws IOException {
        PvzceResourceManager resources =
                new PvzceResourceManager(FontRendererTest.class.getClassLoader());
        resources.init(gameDir);
        // The calibration below is in scale-1 GUI units, and the smoke runs it was
        // measured against are 1280x720 - where the auto GUI scale is 3.
        return new Fonts(resources, () -> 3);
    }

    /** How tall a character's ink is at a given em, in the same units the em is in. */
    private static float inkHeight(TtfFace face, int codepoint, float em) {
        TtfFace.Raster raster = face.rasterize(codepoint, em);
        assertNotNull(raster, "expected ink for U+" + Integer.toHexString(codepoint));
        return raster.visualTop() - raster.visualBottom();
    }

    @Test
    void bodyTextIsTheSizeThePanelsWereBuiltFor(@TempDir Path gameDir) throws IOException {
        Fonts fonts = fonts(gameDir);

        // A hanzi advances exactly one em, and the em is 14 units: the same optical size
        // as the 98px-in-18-unit bitmap font this renderer replaced.
        assertEquals(14F, fonts.body().width("中", 1F), 0.02F);
        assertEquals(14, fonts.body().lineHeight(1F));
        // Of which the face puts 0.88 above the baseline and 0.12 below, so a line of
        // text fills its box without spilling into the next one.
        assertEquals(12.32F, fonts.body().ascent(1F), 0.02F);
        assertEquals(1.68F, fonts.body().lineHeight(1F) - fonts.body().ascent(1F), 0.02F);
        // Latin is proportional and narrower than a hanzi, which is why a UI made of
        // numbers and ids does not measure like one made of Chinese.
        assertTrue(fonts.body().width("0123456789", 1F) < fonts.body().width("〇一二三四五六七八九", 1F));
    }

    /**
     * The two roles have to look the same size, not be the same number: 站酷快乐体 draws
     * its hanzi smaller on the em than 思源黑体 does, so equal ems would make every
     * button label quietly a size smaller than the body text beside it.
     */
    @Test
    void theTwoRolesAreOpticallyTheSameSize(@TempDir Path gameDir) throws IOException {
        Fonts fonts = fonts(gameDir);
        float display = inkHeight(BundledFonts.face("zhanku.ttf"), '中', FontRenderer.DISPLAY_EM);
        float body = inkHeight(BundledFonts.face("noto_sans_sc_regular.ttf"), '中',
                FontRenderer.BODY_EM);

        assertEquals(body, display, body * 0.08F);
        // Which is the old bitmap ink height, from both roles.
        assertEquals(OLD_INK_GUI, body, OLD_INK_GUI * 0.1F);
        assertEquals(OLD_INK_GUI, display, OLD_INK_GUI * 0.1F);
        // The display role still advances its own em, a little wider than the body's.
        assertEquals(16F, fonts.button().width("中", 1F), 0.02F);
    }

    @Test
    void theDisplayRoleFallsBackForTheHanziItLacks(@TempDir Path gameDir) throws IOException {
        Fonts fonts = fonts(gameDir);
        // 站酷快乐体 stops at the 6763 common hanzi; extension A comes from 思源黑体, so
        // the character is drawn at all instead of vanishing. It is drawn at the *role's*
        // em, not the fallback face's own body size - the fallback swaps one legible face
        // for another, so a rare character must not also change the layout around it.
        assertEquals(16F, fonts.button().width("㐀", 1F), 0.02F);
        assertEquals(0F, fonts.button().width("", 1F), 0.001F);
    }

    @Test
    void wrappingBreaksBetweenCharactersAndHonoursNewlines(@TempDir Path gameDir)
            throws IOException {
        Fonts fonts = fonts(gameDir);

        // 14 units per hanzi at scale 1, so a 40-unit limit holds two of them.
        assertEquals(List.of("中文", "字符"), fonts.body().wrapLines("中文字符", 40F, 1F));
        // An explicit newline is always a break, including an empty line.
        assertEquals(List.of("中文", "", "字符"), fonts.body().wrapLines("中文\n\n字符", 40F, 1F));
        // A single character wider than the limit gets a line of its own rather than an
        // empty line before it.
        assertEquals(List.of("中", "文"), fonts.body().wrapLines("中文", 10F, 1F));
    }

    @Test
    void everyWrappedLineFitsTheWidthItWasMeasuredFor(@TempDir Path gameDir) throws IOException {
        Fonts fonts = fonts(gameDir);
        float limit = 2.5F * fonts.body().width("中", 1F);

        for (String line : fonts.body().wrapLines("僵尸正在接近你的房子", limit, 1F)) {
            assertTrue(fonts.body().width(line, 1F) <= limit + 0.02F,
                    "\"" + line + "\" is wider than the " + limit + " units it was wrapped to");
        }
    }
}
