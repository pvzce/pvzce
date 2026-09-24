package com.pvzce.client.renderer.font;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The style flags, and the combinations the UI actually asks for.
 *
 * <p>Small on purpose: the parts of a style that end up in a shader uniform or a second draw call
 * are the parts a call site cannot see going missing. Bold in particular travels through the same
 * builder as the effects, so "bold plus a halo" - which is what a button label is - has to keep
 * both flags rather than the last one set.
 */
class TextStyleTest {
    @Test
    void plainTextHasNoEffectsAtAll() {
        assertFalse(TextStyle.NONE.hasOutline());
        assertFalse(TextStyle.NONE.hasShadow());
        assertFalse(TextStyle.NONE.hasBold());
    }

    @Test
    void boldSurvivesTheOtherEffectsBeingAdded() {
        TextStyle halo = TextStyle.outline(1F, 1F, 1F, 1F, 1F).withBold();
        assertTrue(halo.hasBold(), "an outlined bold label is still bold");
        assertTrue(halo.hasOutline());
        assertFalse(halo.hasShadow());

        TextStyle shadowed = TextStyle.bold().withShadow(1F, -1F, 0F, 0F, 0F);
        assertTrue(shadowed.hasBold(), "adding a shadow must not drop the bold");
        assertTrue(shadowed.hasShadow());
        assertFalse(shadowed.hasOutline());
    }

    @Test
    void aHaloIsClampedToWhatTheAtlasGuttersCanTake() {
        // Two GUI units is the widest the API accepts, and the renderer caps the device-pixel
        // footprint at the gutter on top of that - sampling further reads the glyph next door.
        assertTrue(TextStyle.outline(1F, 1F, 1F, 1F, 99F).outlineThickness() <= 2F);
        assertTrue(TextStyle.outline(1F, 1F, 1F, 1F, -3F).outlineThickness() >= 0F);
    }
}
