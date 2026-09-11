package com.pvzce.client.renderer;

/**
 * Conversion helpers for animation JSON pixel UVs.
 *
 * <p>Animation resources use top-left pixel coordinates. {@code TextureManager}
 * uploads PNGs vertically flipped, so a top-left pixel maps to texture v=1 and
 * a bottom pixel maps to v=0.</p>
 */
public final class TextureUv {
    private TextureUv() {
    }

    public static float normalizeU(float pixel, int textureWidth) {
        return pixel / Math.max(1F, textureWidth);
    }

    /** Converts a top-left-origin pixel v coordinate into GL bottom-left v. */
    public static float normalizeV(float pixel, int textureHeight) {
        return 1F - pixel / Math.max(1F, textureHeight);
    }
}
