package com.pvzce.client.renderer.font;

/**
 * A glyph that is in the atlas and ready to draw.
 *
 * <p>The ink rectangle is recorded in <em>device pixels measured from the
 * baseline</em>: {@code visualTop} is how far above the baseline the ink reaches
 * (positive up) and {@code visualBottom} how far below it (positive down, and
 * usually negative for a glyph that sits on the baseline). The draw code turns
 * that into a quad without consulting the font again.
 *
 * @param slot          where in which atlas page the ink lives
 * @param advance       pen movement after the glyph, in device pixels
 * @param visualLeft    left edge of the ink relative to the pen origin
 * @param visualTop     top of the ink, above the baseline
 * @param visualBottom  bottom of the ink, below the baseline
 */
record Glyph(GlyphPage.Slot slot, float advance, float visualLeft, float visualTop, float visualBottom) {
    int width() {
        return slot.width();
    }

    int height() {
        return slot.height();
    }
}
