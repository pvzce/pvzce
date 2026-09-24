package com.pvzce.client.gui.components;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How a button sizes its label.
 *
 * <p>The rule is one pure function so that "a label is the same proportion of every button" is a
 * statement with a test rather than a look somebody has to eyeball on every screen: it says a label
 * fits its frame, that a bigger button gets a bigger label, and that neither a tiny button nor a
 * caller's multiplier can push text out of the box it belongs to.
 */
class ButtonLabelScaleTest {
    /** A short label on a typical button: a 16-unit line box, 64 units of text. */
    private static final float LINE = 16F;
    private static final float TEXT = 64F;

    @Test
    void aLabelFitsTheButtonItIsIn() {
        for (int[] size : new int[][]{{280, 20}, {200, 32}, {150, 16}, {420, 44}}) {
            float scale = Button.fittedLabelScale(size[0], size[1], TEXT, LINE, 1F);
            assertTrue(scale * TEXT <= size[0] * Button.LABEL_SIZE_OF_FACE + 0.01F,
                    "text " + scale * TEXT + " wider than " + size[0] + " allows");
            assertTrue(scale * LINE <= size[1] * Button.LABEL_SIZE_OF_FACE + 0.01F,
                    "line " + scale * LINE + " taller than " + size[1] + " allows");
        }
    }

    @Test
    void aBiggerButtonGetsABiggerLabel() {
        float small = Button.fittedLabelScale(280, 16, TEXT, LINE, 1F);
        float medium = Button.fittedLabelScale(280, 24, TEXT, LINE, 1F);
        float large = Button.fittedLabelScale(280, 40, TEXT, LINE, 1F);

        assertTrue(small < medium, "16 -> 24 units tall must grow the label: " + small + " vs " + medium);
        assertTrue(medium < large, "24 -> 40 units tall must grow the label: " + medium + " vs " + large);
    }

    @Test
    void aLongLabelIsPulledBackByTheWidth() {
        // The editor's buttons hold labels like 移除最后的前置关卡, which are wider than the button
        // at the size the height alone would ask for.
        float shortLabel = Button.fittedLabelScale(140, 40, 32F, LINE, 1F);
        float longLabel = Button.fittedLabelScale(140, 40, 144F, LINE, 1F);

        assertTrue(longLabel < shortLabel, "a nine-character label has to give way");
        assertTrue(longLabel * 144F <= 140F * Button.LABEL_SIZE_OF_FACE + 0.01F,
                "and it still has to fit");
    }

    @Test
    void theSizeStaysInTheBandThatReadsAsALabel() {
        assertEquals(Button.MIN_LABEL_SCALE, Button.fittedLabelScale(40, 6, TEXT, LINE, 1F), 0.001F);
        assertEquals(Button.MAX_LABEL_SCALE, Button.fittedLabelScale(2000, 400, TEXT, LINE, 1F), 0.001F);
        // A degenerate size (a widget laid out before its screen has one) must not divide by
        // anything or answer nonsense.
        assertEquals(Button.MIN_LABEL_SCALE, Button.fittedLabelScale(0, 0, TEXT, LINE, 1F), 0.001F);
        assertEquals(24F * Button.LABEL_SIZE_OF_FACE / LINE,
                Button.fittedLabelScale(280, 24, 0F, LINE, 1F), 0.001F,
                "an empty label has no width to fit, so the height decides alone");
    }

    /**
     * The label sits where the ink is centred, and it follows the plate when the plate moves.
     *
     * <p>A hovered stone button draws the pressed art - the same plate one native pixel lower
     * inside its texture - and a label that stayed put would be left hanging over the frame it no
     * longer lines up with. The two halves are one function so neither can be changed alone.
     */
    @Test
    void theLabelCentresOnTheInkAndSinksWithAPressedPlate() {
        float height = 46F;
        // The metrics of the display role: 0.859em above the baseline, 0.141em below.
        float ascent = 13.7F;
        float descent = 2.3F;

        float resting = Button.labelBaseline(0F, height, ascent, descent, false);
        assertEquals(height / 2F - (ascent - descent) / 2F, resting, 0.001F);
        assertEquals(height / 2F, resting + (ascent - descent) / 2F, 0.001F,
                "the middle of the metrics' box lands on the middle of the button");

        float sunk = Button.labelBaseline(0F, height, ascent, descent, true);
        assertEquals(height / NinePatch.BUTTON_NATIVE_HEIGHT, resting - sunk, 0.001F,
                "and a pressed plate takes the label down by exactly the pixel it sank");
    }

    @Test
    void theMultiplierIsAnEmphasisNotAnOverride() {
        float plain = Button.fittedLabelScale(280, 24, TEXT, LINE, 1F);
        float emphasised = Button.fittedLabelScale(280, 24, TEXT, LINE, 1.2F);

        assertEquals(plain * 1.2F, emphasised, 0.001F);
        // But it cannot escape the clamp, so a caller cannot draw text out of its own button.
        assertEquals(Button.MAX_LABEL_SCALE, Button.fittedLabelScale(2000, 400, TEXT, LINE, 4F), 0.001F);
    }
}
