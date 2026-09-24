package com.pvzce.client.renderer.font;

import java.util.List;

/**
 * A UI role resolved to an actual face at an actual size.
 *
 * <p>This is the one object that can answer "where does this character live, and
 * where does the pen go next" - every size-dependent decision (which face, which
 * atlas page, how far to advance) has already been made by the time a
 * {@code GlyphRef} exists. The draw loop therefore never consults the font again.
 *
 * @param face         the face that has the ink, which is not the role's primary
 *                     face when a fallback covered a character the primary lacks
 * @param slot         atlas page and texel rectangle
 * @param advance      pen movement, device pixels
 * @param visualLeft   left edge of the ink relative to the pen origin
 * @param visualTop    top of the ink, above the baseline
 * @param visualBottom bottom of the ink: 0 on the baseline, negative below it
 *                     (a signed offset in the up-positive sense; see {@link Glyph})
 */
record GlyphRef(FontFace face, GlyphPage.Slot slot, float advance, float visualLeft,
                float visualTop, float visualBottom) {

    /** Width of the ink rectangle in device pixels. */
    int width() {
        return slot.width();
    }

    /** Height of the ink rectangle in device pixels. */
    int height() {
        return slot.height();
    }
}

/**
 * A font role: one primary typeface, its fallback chain, and the logical em size
 * the UI means when it asks for this role at scale 1.
 *
 * <p>Fallback is a chain rather than a flag because the bundled faces cover
 * different repertoires: 站酷快乐体 has the 6763 common hanzi but no CJK
 * extension A, while 思源黑体 has 21k hanzi and full Latin. A role that names a
 * display face therefore still needs a body face behind it or a rare character
 * in a level name silently disappears.
 *
 * <p>The em size is in <em>GUI units</em>; the device pixel size is derived per
 * draw from the window's GUI scale, so a window or DPI change produces sharper
 * glyphs rather than a stretched bitmap.
 *
 * <p>Package private on purpose: call sites outside this package name a role
 * through {@code Fonts}, which hands back a {@link Fonts.FontRole}. This class is
 * the resolver those roles delegate to.
 */
final class FontFamily {
    private final String name;
    private final FontFace primary;
    private final List<FontFace> fallbacks;
    private final float emSize;
    /**
     * Below this many device pixels the primary face is skipped in favour of the
     * fallback chain. 0 means "always use the primary".
     */
    private final int minimumPrimaryPixels;

    FontFamily(String name, FontFace primary, List<FontFace> fallbacks, float emSize) {
        this(name, primary, fallbacks, emSize, 0);
    }

    FontFamily(String name, FontFace primary, List<FontFace> fallbacks, float emSize,
               int minimumPrimaryPixels) {
        this.name = name;
        this.primary = primary;
        this.fallbacks = List.copyOf(fallbacks);
        this.emSize = emSize;
        this.minimumPrimaryPixels = minimumPrimaryPixels;
    }

    /** True when the primary face is legible at this size. */
    private boolean primaryUsable(int devicePixelSize) {
        return devicePixelSize >= minimumPrimaryPixels;
    }

    String name() {
        return name;
    }

    /** The logical line size at scale 1, in GUI units. */
    float emSize() {
        return emSize;
    }

    FontFace primary() {
        return primary;
    }

    /** Resolves one codepoint at one device pixel size, walking the fallback chain. */
    GlyphRef glyph(int codepoint, int devicePixelSize) {
        if (primaryUsable(devicePixelSize)) {
            Glyph glyph = primary.glyph(codepoint, devicePixelSize);
            if (glyph != null) {
                return ref(primary, glyph, devicePixelSize);
            }
        }
        for (FontFace fallback : fallbacks) {
            Glyph glyph = fallback.glyph(codepoint, devicePixelSize);
            if (glyph != null) {
                return ref(fallback, glyph, devicePixelSize);
            }
        }
        return null;
    }

    /**
     * Pen movement for a codepoint, whether or not it has ink. Spaces have no ink
     * but must still advance, and a glyph the atlas had no room for this frame must
     * still leave the same gap next frame.
     */
    float advance(int codepoint, int devicePixelSize) {
        if (primaryUsable(devicePixelSize)) {
            float advance = primary.advance(codepoint, devicePixelSize);
            if (advance > 0F || primary.covers(codepoint)) {
                return advance;
            }
        }
        for (FontFace fallback : fallbacks) {
            float advance = fallback.advance(codepoint, devicePixelSize);
            if (advance > 0F || fallback.covers(codepoint)) {
                return advance;
            }
        }
        return 0F;
    }

    /** Kerning between two codepoints, from whichever face covers both. */
    float kern(int left, int right, int devicePixelSize) {
        if (primaryUsable(devicePixelSize)) {
            float kern = primary.kern(left, right, devicePixelSize);
            if (kern != 0F) {
                return kern;
            }
        }
        for (FontFace fallback : fallbacks) {
            float kern = fallback.kern(left, right, devicePixelSize);
            if (kern != 0F) {
                return kern;
            }
        }
        return 0F;
    }

    /**
     * Baseline-to-top of a line, in device pixels: how far the line box reaches above the
     * baseline every draw anchors on.
     *
     * <p>Always from the primary face, even when the size is below
     * {@link #minimumPrimaryPixels}: the fallback swaps one legible face for
     * another, it is not a change of layout.
     */
    float ascent(int devicePixelSize) {
        return primary.ascent(devicePixelSize);
    }

    /** Baseline-to-bottom of a line, in device pixels: the descent below the anchor. */
    float descent(int devicePixelSize) {
        return primary.descent(devicePixelSize);
    }

    /** Frees this role's atlas pages, keeping the loaded font. */
    void recycle() {
        primary.recycle();
        // Fallbacks are shared between roles, so they are not recycled here: another
        // role may be mid-frame with glyphs from the same page.
    }

    String debugName() {
        return name + " @" + emSize + " -> " + primary.debugName();
    }

    private static GlyphRef ref(FontFace face, Glyph glyph, int devicePixelSize) {
        return new GlyphRef(face, glyph.slot(), glyph.advance(),
                glyph.visualLeft(), glyph.visualTop(), glyph.visualBottom());
    }
}
