package com.pvzce.client.gui;

import com.pvzce.client.PvzceClient;
import com.pvzce.common.resource.PvzceResourceManager;
import com.pvzce.common.tag.TestContent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The first-run page's two questions, as far as they are facts rather than pixels.
 *
 * <p>The page itself needs a window; what it *asks* does not. Three things decide whether a new
 * player lands on a readable interface: which languages the pack stack offers, whether this install
 * has already answered the page, and what size this resolution wants. Each is asserted here, and
 * the page is left to be looked at.
 */
class OnboardingTest {
    private static PvzceResourceManager resources;

    @BeforeAll
    static void load() throws Exception {
        resources = TestContent.loadBuiltInContentAndTags();
    }

    /** The built-in locale leads, and a pack's locale is discovered rather than listed. */
    @Test
    void theLanguageListLeadsWithTheBuiltInLocale() {
        List<String> locales = GuiLang.availableLocales(resources);

        assertFalse(locales.isEmpty(), "a chooser with no options is not a chooser");
        assertEquals(GuiLang.DEFAULT_LOCALE, locales.get(0),
                "the game is authored in this language and every key exists there");
        assertTrue(locales.contains("en_us"), "the shipped English file is offered: " + locales);
        assertEquals(locales.size(), locales.stream().distinct().count(), "no locale twice");
    }

    /**
     * Every language names every language, in its own words.
     *
     * <p>The rows are read by someone who cannot read the current language - that is what the row
     * is for - so the endonyms cannot come from the loaded locale alone. Both built-in files carry
     * both names, and a locale nobody named falls back to its code rather than to a blank row.
     */
    @Test
    void everyLanguageIsNamedInItsOwnWords() {
        GuiLang.reload(resources, "zh_cn");
        assertEquals("English", GuiLang.localeName("en_us"), "from the Chinese file");
        assertEquals("简体中文", GuiLang.localeName("zh_cn"));

        GuiLang.reload(resources, "en_us");
        assertEquals("English", GuiLang.localeName("en_us"), "and from the English one");
        assertEquals("简体中文", GuiLang.localeName("zh_cn"), "which is the point: it is readable");

        assertEquals("ja_jp", GuiLang.localeName("ja_jp"), "an unnamed locale is its own code");
        GuiLang.reload(resources, GuiLang.DEFAULT_LOCALE);
    }

    /**
     * The recommended scale is the one nearest a 960x540 interface, not the largest one that fits.
     *
     * <p>At 1080p the largest scale that fits is 4, which draws the interface in 480x270 - half the
     * size every screen was laid out at. The recommendation is what "auto" now means, so these are
     * the numbers a first run lands on.
     */
    @Test
    void theRecommendedScaleTargetsTheDesignedInterfaceSize() {
        assertEquals(2, PvzceClient.recommendedScale(1920, 1080),
                "1080p: 960x540, the size the layouts were written at");
        assertEquals(2, PvzceClient.recommendedScale(1600, 900), "900p: 800x450");
        assertEquals(3, PvzceClient.recommendedScale(2560, 1440), "1440p: 853x480, nearer 540 than 720");
        assertEquals(4, PvzceClient.recommendedScale(3840, 2160), "4K: 960x540 again");

        // A small window keeps the bigger interface on a tie, and never asks for more than fits.
        assertEquals(1, PvzceClient.recommendedScale(1280, 720), "720p: 1280x720 over 640x360");
        assertEquals(1, PvzceClient.recommendedScale(640, 480), "and a 640x480 window is 1x");
        assertTrue(PvzceClient.recommendedScale(3840, 2160)
                <= PvzceClient.fitScale(3840, 2160, 4), "never past what the layout can take");
    }

    /** A bigger scale is a smaller interface, so the recommendation has to grow with the screen. */
    @Test
    void theRecommendationNeverShrinksAsTheScreenGrows() {
        int previous = 0;
        for (int height : new int[] {480, 600, 720, 768, 900, 1080, 1200, 1440, 1600, 2160}) {
            int scale = PvzceClient.recommendedScale(height * 16 / 9, height);
            assertTrue(scale >= previous, "at " + height + "p the scale went backwards: " + scale);
            previous = scale;
        }
    }
}
