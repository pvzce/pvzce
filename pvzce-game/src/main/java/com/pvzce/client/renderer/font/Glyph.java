package com.pvzce.client.renderer.font;

/**
 * A glyph that is in the atlas and ready to draw.
 *
 * <p>The ink rectangle is recorded in <em>device pixels measured from the
 * baseline</em>: {@code visualTop} is how far above the baseline the ink reaches and
 * {@code visualBottom} how far below it, both as signed offsets in the same
 * up-positive sense as the GUI's y - so a glyph sitting on the baseline has
 * {@code visualBottom == 0} and one that hangs below it (a comma, a 'g') has a
 * negative one. The draw code turns that into a quad without consulting the font
 * again.
 *
 * @param slot          where in which atlas page the ink lives
 * @param advance       pen movement after the glyph, in device pixels
 * @param visualLeft    left edge of the ink relative to the pen origin
 * @param visualTop     top of the ink, above the baseline
 * @param visualBottom  bottom of the ink: 0 on the baseline, negative below it
 */
record Glyph(GlyphPage.Slot slot, float advance, float visualLeft, float visualTop, float visualBottom) {
    int width() {
        return slot.width();
    }

    int height() {
        return slot.height();
    }
}
