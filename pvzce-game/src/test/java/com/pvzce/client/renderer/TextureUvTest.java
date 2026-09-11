package com.pvzce.client.renderer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TextureUvTest {
    @Test
    void topLeftPixelMapsToTextureTopLeft() {
        assertEquals(0F, TextureUv.normalizeU(0F, 64), 0.0001F);
        assertEquals(1F, TextureUv.normalizeV(0F, 64), 0.0001F);
    }

    @Test
    void bottomRightPixelMapsToTextureBottomRight() {
        assertEquals(1F, TextureUv.normalizeU(64F, 64), 0.0001F);
        assertEquals(0F, TextureUv.normalizeV(64F, 64), 0.0001F);
    }

    @Test
    void centrePixelMapsToCentreUv() {
        assertEquals(0.5F, TextureUv.normalizeU(32F, 64), 0.0001F);
        assertEquals(0.5F, TextureUv.normalizeV(32F, 64), 0.0001F);
    }
}
