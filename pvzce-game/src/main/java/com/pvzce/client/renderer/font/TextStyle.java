package com.pvzce.client.renderer.font;

/**
 * Outline and drop shadow for one text draw.
 *
 * <p>Both used to be done by the call sites themselves, by drawing the same string
 * several times: a black copy under a coloured one for the wave banner, a darker
 * copy behind the award screen's headings. That works for a shadow but not for an
 * outline (the copies overlap into a smear at CJK stroke density) and it costs one
 * draw call per copy. This moves the effect into the fragment shader, where an
 * outline is one extra texture sample.
 *
 * <p>Immutable, and cheap to build: the constants below cover the two effects the
 * UI actually asks for, and {@link #NONE} is a singleton because most text has no
 * effect at all.
 */
public final class TextStyle {
    /** Plain text: no outline, no shadow. */
    public static final TextStyle NONE =
            new TextStyle(false, 0F, 0F, 0F, 0F, 0F, false, 0F, 0F, 0F, 0F, 0F);

    private final boolean outline;
    private final float outlineR;
    private final float outlineG;
    private final float outlineB;
    private final float outlineA;
    private final float outlineThickness;
    private final boolean shadow;
    private final float shadowOffsetX;
    private final float shadowOffsetY;
    private final float shadowR;
    private final float shadowG;
    private final float shadowB;

    private TextStyle(boolean outline, float outlineR, float outlineG, float outlineB, float outlineA,
                      float outlineThickness, boolean shadow, float shadowOffsetX, float shadowOffsetY,
                      float shadowR, float shadowG, float shadowB) {
        this.outline = outline;
        this.outlineR = outlineR;
        this.outlineG = outlineG;
        this.outlineB = outlineB;
        this.outlineA = outlineA;
        this.outlineThickness = outlineThickness;
        this.shadow = shadow;
        this.shadowOffsetX = shadowOffsetX;
        this.shadowOffsetY = shadowOffsetY;
        this.shadowR = shadowR;
        this.shadowG = shadowG;
        this.shadowB = shadowB;
    }

    /**
     * A halo around every stroke, in GUI units (the shader offsets are converted
     * per draw). Thickness is clamped to 2 GUI units: the atlas gutters are sized
     * for that, and a thicker halo would sample the neighbouring glyph.
     */
    public static TextStyle outline(float r, float g, float b, float a, float thickness) {
        return new TextStyle(true, r, g, b, a, Math.max(0F, Math.min(2F, thickness)),
                false, 0F, 0F, 0F, 0F, 0F);
    }

    /** A hard drop shadow at a GUI-unit offset, drawn behind the glyph. */
    public static TextStyle shadow(float offsetX, float offsetY, float r, float g, float b, float a) {
        return new TextStyle(false, 0F, 0F, 0F, 0F, 0F, true, offsetX, offsetY, r, g, b);
    }

    /** The house shadow: one unit down-right in near-black, for text over art. */
    public static TextStyle standardShadow() {
        return shadow(1F, -1F, 0.05F, 0.05F, 0.08F, 0.85F);
    }

    /** Outline plus shadow, for the one case that wants both (wave banners). */
    public TextStyle withShadow(float offsetX, float offsetY, float r, float g, float b) {
        return new TextStyle(outline, outlineR, outlineG, outlineB, outlineA, outlineThickness,
                true, offsetX, offsetY, r, g, b);
    }

    boolean hasOutline() {
        return outline;
    }

    float outlineR() {
        return outlineR;
    }

    float outlineG() {
        return outlineG;
    }

    float outlineB() {
        return outlineB;
    }

    float outlineA() {
        return outlineA;
    }

    float outlineThickness() {
        return outlineThickness;
    }

    boolean hasShadow() {
        return shadow;
    }

    float shadowOffsetX() {
        return shadowOffsetX;
    }

    float shadowOffsetY() {
        return shadowOffsetY;
    }

    float shadowR() {
        return shadowR;
    }

    float shadowG() {
        return shadowG;
    }

    float shadowB() {
        return shadowB;
    }
}
