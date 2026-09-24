package com.pvzce.client.renderer.font;

import com.pvzce.api.util.Identifier;
import com.pvzce.client.renderer.RenderSystem;
import com.pvzce.client.renderer.SpriteRenderer;
import com.pvzce.client.renderer.sprite.Sprite;
import com.pvzce.common.resource.PackResource;
import com.pvzce.common.resource.PvzceResourceManager;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntSupplier;

/**
 * The one text renderer: three typeface roles, rasterised from TTF at the pixel
 * size the screen actually has.
 *
 * <p>Replaces the pre-baked bitmap atlas this project shipped with (a 16384x7168
 * PNG holding 7131 glyphs, ~470MB of VRAM once a driver expands it). Glyphs now
 * come from the fonts in {@code assets/pvzce/font/} through stb_truetype, packed
 * on demand into 2048x2048 R8 atlas pages - a screenful of text costs one page,
 * and the em size follows the window's GUI scale instead of being fixed at bake
 * time, so text stays sharp at any window size or DPI.
 *
 * <h2>Roles</h2>
 * The three roles are what call sites ask for; which face satisfies them is
 * decided here, once:
 * <ul>
 *   <li>{@link #button()} - 站酷快乐体, for buttons, titles and dialogue;</li>
 *   <li>{@link #body()} - 思源黑体, for hints, labels and everything else;</li>
 *   <li>{@link #serif()} - 思源宋体, for long-form prose (the codex to come).</li>
 * </ul>
 *
 * <h2>The y coordinate</h2>
 * {@code y} is the <em>baseline</em> the line sits on, exactly as it was for the old
 * bitmap renderer: every layout in this project was spaced against that anchor, so a
 * label centred in a box, a plate sized around a line and a block of stacked lines all
 * keep meaning what they meant. The old renderer anchored there by construction - it
 * placed the ink's bottom edge at {@code y + (baselineRow - inkBottomRow)}, which is
 * {@code y} itself once the overshoot below the baseline is accounted for - and the TTF
 * rewrite kept every call site's arithmetic, so this class has to keep that meaning.
 * {@link #draw} returns the next line's baseline and {@link #drawLines} advances through
 * a list, so stacking call sites do not repeat the arithmetic; {@link #ascent} is how
 * far a line reaches above that baseline, which is what a caller needs to centre one.
 *
 * <p>Not thread safe: everything here runs on the render thread.
 */
public final class FontRenderer implements AutoCloseable {
    /** Files under {@code assets/pvzce/font/}, without the extension. */
    private static final String DISPLAY_FILE = "zhanku";
    private static final String SANS_FILE = "noto_sans_sc_regular";
    private static final String SANS_MEDIUM_FILE = "noto_sans_sc_medium";
    private static final String SERIF_FILE = "noto_serif_sc_regular";

    /**
     * Logical em size of the body role at scale 1, in GUI units.
     *
     * <p>Calibrated against the bitmap atlas this replaced, which drew a 98px font
     * into 18-unit lines: its em was 98 x 18/128 = 13.8 GUI units and a full-width
     * hanzi carried 94px of ink, i.e. 13.2 units - and every layout in this project is
     * spaced against those numbers. The bundled body face draws a hanzi at 0.919 of
     * its em, so an em of 14 reproduces the ink height (12.9 units) almost exactly.
     *
     * <p>What it does not reproduce is the old line box: that was 18 units, while this
     * face's own typographic line is exactly 1.0em, so {@code lineHeight(1)} is 14 now
     * and stacked lines sit closer together than they used to. Getting this wrong is
     * not subtle - at 72 (the first TTF attempt) a hanzi was 66 units tall and every
     * label drew as a solid block the size of its button.
     */
    static final float BODY_EM = 14F;
    /**
     * The display face asks for a larger em than the body face, because its hanzi are
     * drawn smaller on the em (0.808 against 0.919): 1.14 is what makes the two roles
     * *optically* the same size rather than numerically the same size. At the same em
     * the display face would look about an eighth smaller than the body face beside it.
     */
    static final float DISPLAY_EM = BODY_EM * 1.14F;
    /** The serif face's hanzi are 0.916 of its em, so long-form prose reads at the body size. */
    static final float SERIF_EM = BODY_EM;
    /**
     * Below this many device pixels 站酷快乐体's strokes merge into a blob, so the
     * display role quietly switches to 思源黑体: readability beats house style.
     * 16 device pixels is roughly where the two stop being distinguishable.
     */
    private static final int DISPLAY_MIN_PIXELS = 16;
    /**
     * One atlas texel, in texture coordinates.
     *
     * <p>A glyph is rasterised at exactly the size it is drawn at, so a texel is one
     * device pixel - which is what lets an effect's offset be turned from GUI units
     * into a sampling distance. The shader's offsets are uv deltas, not pixels: handing
     * it raw device pixels samples the far side of the page rather than the next texel.
     */
    private static final float TEXEL = 1F / GlyphPage.SIZE;
    /**
     * How far a shadow or an outline may sample, in device pixels.
     *
     * <p>The atlas gutter, because that is all the room a glyph has that is guaranteed
     * to be transparent: a halo that reads further finds the glyph packed next door and
     * paints a ghost of it. At a GUI scale above {@code GUTTER} this caps the effect
     * below the offset that was asked for, which is the right trade - a thinner halo
     * beats a neighbour's strokes appearing around this one.
     */
    private static final float MAX_EFFECT_PIXELS = GlyphPage.GUTTER;
    /**
     * How far a bold glyph is grown, in device pixels.
     *
     * <p>One is the smallest amount that reads as bold and the most a stroke can gain without
     * closing the counters of a hanzi at the sizes the UI asks for.
     */
    private static final float BOLD_PIXELS = 1F;

    private final FontFace displayFace;
    private final FontFace sansFace;
    private final FontFace sansMediumFace;
    private final FontFace serifFace;
    private final FontFamily display;
    private final FontFamily body;
    private final FontFamily bodyMedium;
    private final FontFamily serif;
    /** GUI scale in framebuffer pixels per GUI unit; text is rasterised at that density. */
    private final IntSupplier guiScale;

    /**
     * @param resources the pack stack to read the TTFs from
     * @param guiScale  framebuffer pixels per GUI unit, read per draw so a window or
     *                  scale change produces sharper glyphs instead of a stretched
     *                  bitmap
     */
    public FontRenderer(PvzceResourceManager resources, IntSupplier guiScale) {
        this.guiScale = guiScale;
        displayFace = load(resources, DISPLAY_FILE);
        sansFace = load(resources, SANS_FILE);
        sansMediumFace = loadOptional(resources, SANS_MEDIUM_FILE);
        serifFace = loadOptional(resources, SERIF_FILE);
        // 站酷 first for the display role, 思源黑体 behind it for the hanzi it does
        // not have (extension A) and for anything below its legible size.
        display = new FontFamily("display", displayFace, List.of(sansFace), DISPLAY_EM,
                DISPLAY_MIN_PIXELS);
        body = new FontFamily("body", sansFace, List.of(), BODY_EM);
        bodyMedium = new FontFamily("body_medium", sansMediumFace == null ? sansFace : sansMediumFace,
                List.of(), BODY_EM);
        serif = new FontFamily("serif", serifFace == null ? sansFace : serifFace,
                List.of(sansFace), SERIF_EM);
    }

    // ---------- roles ----------

    /** Buttons, titles and dialogue: 站酷快乐体. */
    public FontFamily button() {
        return display;
    }

    /** Hints, labels, numbers and every other short string: 思源黑体. */
    public FontFamily body() {
        return body;
    }

    /** A slightly heavier body face, for the one label that needs the emphasis. */
    public FontFamily bodyMedium() {
        return bodyMedium;
    }

    /** Long-form prose: 思源宋体. */
    public FontFamily serif() {
        return serif;
    }

    // ---------- drawing ----------

    /** Draws one line (or several, split on {@code \n}) and returns the next line's baseline. */
    public float draw(String text, float x, float y, float scale, float r, float g, float b, float a) {
        return draw(text, x, y, scale, r, g, b, a, TextStyle.NONE);
    }

    /** Draws with an outline or drop shadow - see {@link TextStyle}. */
    public float draw(String text, float x, float y, float scale, float r, float g, float b, float a,
                      TextStyle style) {
        return draw(body, text, x, y, scale, r, g, b, a, style);
    }

    /** Draws with an explicit role. */
    public float draw(FontFamily family, String text, float x, float y, float scale,
                      float r, float g, float b, float a, TextStyle style) {
        if (text == null || text.isEmpty()) {
            return y;
        }
        int size = devicePixelSize(family, scale);
        return drawRun(family, text, x, y, scale, size, r, g, b, a, style);
    }

    /** Convenience: draw an already-wrapped list, one line per entry. */
    public float drawLines(List<String> lines, float x, float y, float scale,
                           float r, float g, float b, float a, TextStyle style) {
        return drawLines(body, lines, x, y, scale, r, g, b, a, style);
    }

    /** Convenience: draw an already-wrapped list with an explicit role. */
    public float drawLines(FontFamily family, List<String> lines, float x, float y, float scale,
                           float r, float g, float b, float a, TextStyle style) {
        float cursor = y;
        for (String line : lines) {
            cursor = draw(family, line, x, cursor, scale, r, g, b, a, style);
        }
        return cursor;
    }

    /** Draws centred on {@code centerX} and returns the next line's baseline. */
    public float drawCentered(String text, float centerX, float y, float scale,
                              float r, float g, float b, float a) {
        return drawCentered(body, text, centerX, y, scale, r, g, b, a, TextStyle.NONE);
    }

    /** Draws centred on {@code centerX} with an explicit role and style. */
    public float drawCentered(FontFamily family, String text, float centerX, float y, float scale,
                              float r, float g, float b, float a, TextStyle style) {
        return draw(family, text, centerX - width(family, text, scale) / 2F, y, scale,
                r, g, b, a, style);
    }

    /** Draws with the right edge at {@code rightX} and returns the next line's baseline. */
    public float drawRight(FontFamily family, String text, float rightX, float y, float scale,
                           float r, float g, float b, float a, TextStyle style) {
        return draw(family, text, rightX - width(family, text, scale), y, scale, r, g, b, a, style);
    }

    // ---------- measuring ----------

    /** Width of the widest line, in GUI units. */
    public float width(String text, float scale) {
        return width(body, text, scale);
    }

    /** Width of the widest line, in GUI units, for an explicit role. */
    public float width(FontFamily family, String text, float scale) {
        if (text == null || text.isEmpty()) {
            return 0F;
        }
        int size = devicePixelSize(family, scale);
        float toGui = 1F / Math.max(1, guiScale.getAsInt());
        float widest = 0F;
        float current = 0F;
        int previous = -1;
        for (int i = 0; i < text.length(); ) {
            int codepoint = text.codePointAt(i);
            i += Character.charCount(codepoint);
            if (codepoint == '\n') {
                widest = Math.max(widest, current);
                current = 0F;
                previous = -1;
                continue;
            }
            if (previous >= 0) {
                current += family.kern(previous, codepoint, size);
            }
            current += family.advance(codepoint, size);
            previous = codepoint;
        }
        return Math.max(widest, current) * toGui;
    }

    /**
     * The baseline step between stacked lines, in GUI units: the distance from one line's
     * baseline to the next one's, which is what a caller stacking text advances by.
     * Rounded to a whole unit, like the bitmap renderer's line height was.
     */
    public int lineHeight(float scale) {
        return lineHeight(body, scale);
    }

    /** Line height for an explicit role, in GUI units. */
    public int lineHeight(FontFamily family, float scale) {
        int size = devicePixelSize(family, scale);
        return Math.round((family.ascent(size) + family.descent(size))
                / Math.max(1, guiScale.getAsInt()));
    }

    /**
     * How far a line reaches above its baseline, in GUI units.
     *
     * <p>The counterpart of the {@code y} a draw takes: the top of a line whose baseline is
     * {@code y} is {@code y + ascent}, so a caller centring one line in a box of its own
     * uses this and {@link #lineHeight} together.
     */
    public float ascent(float scale) {
        return ascent(body, scale);
    }

    /** The ascent above the baseline for an explicit role, in GUI units. */
    public float ascent(FontFamily family, float scale) {
        int size = devicePixelSize(family, scale);
        return family.ascent(size) / Math.max(1, guiScale.getAsInt());
    }

    // ---------- legacy helpers ----------

    /**
     * Alias of {@link #lineHeight(float)} kept for call sites that used the old
     * renderer's name for it.
     */
    public int height(float scale) {
        return lineHeight(scale);
    }

    /**
     * Breaks text into lines that each fit {@code maxWidth}, honouring explicit
     * newlines.
     *
     * <p>Breaks between characters rather than at spaces: this is a CJK UI, where a
     * sentence has no spaces to break at, and a Latin word that overflows is still
     * better split than left running off the panel. A single character wider than the
     * limit gets its own line rather than an empty one before it.
     *
     * <p>Shared because two callers need the same answer to "what are the lines":
     * {@code ModsScreen} draws them into a band, and {@code DialogueOverlay} measures
     * them to size a speech bubble.
     */
    public List<String> wrapLines(String text, float maxWidth, float scale) {
        return wrapLines(body, text, maxWidth, scale);
    }

    /** Wrapping for an explicit role. */
    public List<String> wrapLines(FontFamily family, String text, float maxWidth, float scale) {
        List<String> lines = new ArrayList<>();
        if (text == null) {
            return lines;
        }
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < text.length(); ) {
            int codepoint = text.codePointAt(i);
            i += Character.charCount(codepoint);
            if (codepoint == '\n') {
                lines.add(line.toString());
                line.setLength(0);
                continue;
            }
            int before = line.length();
            line.appendCodePoint(codepoint);
            if (before > 0 && width(family, line.toString(), scale) > maxWidth) {
                line.setLength(before);
                lines.add(line.toString());
                line.setLength(0);
                line.appendCodePoint(codepoint);
            }
        }
        lines.add(line.toString());
        return lines;
    }

    // ---------- lifecycle ----------

    /**
     * Drops every atlas page. For a resource reload: nothing else identifies the
     * atlas to the pack stack, and a pack that swaps a TTF needs the old outlines
     * gone. The next frame re-rasterises what it draws.
     */
    public void invalidate() {
        displayFace.recycle();
        sansFace.recycle();
        if (sansMediumFace != null && sansMediumFace != sansFace) {
            sansMediumFace.recycle();
        }
        if (serifFace != null && serifFace != sansFace) {
            serifFace.recycle();
        }
    }

    /**
     * Writes every atlas page under {@code directory}. For {@code -Dpvzce.dumpFontAtlas}
     * and for tests: a screenshot shows that text is wrong, this shows why.
     */
    public void dumpAtlases(java.nio.file.Path directory) {
        displayFace.dumpAtlases(directory);
        sansFace.dumpAtlases(directory);
        if (sansMediumFace != null && sansMediumFace != sansFace) {
            sansMediumFace.dumpAtlases(directory);
        }
        if (serifFace != null && serifFace != sansFace) {
            serifFace.dumpAtlases(directory);
        }
    }

    /** One line per face: pages, cached glyphs and covered codepoints. */
    public String debugInfo() {
        StringBuilder info = new StringBuilder();
        info.append(display.debugName()).append('\n');
        info.append(body.debugName()).append('\n');
        if (sansMediumFace != null) {
            info.append(sansMediumFace.debugName()).append('\n');
        }
        if (serifFace != null) {
            info.append(serifFace.debugName()).append('\n');
        }
        return info.toString();
    }

    @Override
    public void close() {
        displayFace.close();
        if (sansMediumFace != null && sansMediumFace != sansFace) {
            sansMediumFace.close();
        }
        if (serifFace != null && serifFace != sansFace) {
            serifFace.close();
        }
        sansFace.close();
    }

    // ---------- internals ----------

    private int devicePixelSize(FontFamily family, float scale) {
        float pixels = family.emSize() * scale * Math.max(1, guiScale.getAsInt());
        return Math.max(1, Math.round(pixels));
    }

    /**
     * Draws a run of text. Glyphs already in the atlas come back without touching
     * the font; the first frame after a recycle rasterises on the fly, which is
     * why a glyph the atlas could not take is simply skipped rather than rerouted.
     */
    private float drawRun(FontFamily family, String text, float x, float y, float scale,
                          int size, float r, float g, float b, float a, TextStyle style) {
        float toGui = 1F / Math.max(1, guiScale.getAsInt());
        float lineAdvance = (family.ascent(size) + family.descent(size)) * toGui;
        float startX = x;
        float cursor = x;
        float top = y;
        boolean shadow = style.hasShadow() && a > 0F;
        boolean outline = style.hasOutline() && style.outlineA() > 0F;

        RenderSystem.setTextured(true);
        for (int i = 0; i < text.length(); ) {
            int codepoint = text.codePointAt(i);
            i += Character.charCount(codepoint);
            if (codepoint == '\n') {
                cursor = startX;
                top -= lineAdvance;
                continue;
            }
            drawGlyph(family, codepoint, size, cursor, top, r, g, b, a, style, shadow, outline, toGui);
            cursor += family.advance(codepoint, size) * toGui;
        }
        RenderSystem.setTextured(false);
        RenderSystem.setTextEffects(false, 0F, 0F, 0F, 1F, 1F, 1F);
        return top - lineAdvance;
    }

    private void drawGlyph(FontFamily family, int codepoint, int size, float x, float y,
                           float r, float g, float b, float a, TextStyle style,
                           boolean shadow, boolean outline, float toGui) {
        GlyphRef glyph = family.glyph(codepoint, size);
        if (glyph == null) {
            return;
        }
        // `y` is the baseline itself - see the class comment. Deriving one from the font's
        // ascent instead (as "the top of the line box") would drop every line in the game by
        // one ascent, which is a whole line's worth of drift for the 240-odd call sites that
        // centre text in a box.
        renderGlyph(textureOf(glyph), GlyphQuad.of(glyph, x, y, toGui),
                r, g, b, a, style, shadow, outline, toGui);
    }

    /** One glyph quad, drawn once per layer it needs: halo, bold core, or just the fill. */
    private void renderGlyph(com.pvzce.client.renderer.texture.Texture texture, GlyphQuad quad,
                             float r, float g, float b, float a, TextStyle style,
                             boolean shadow, boolean outline, float toGui) {
        boolean effected = shadow || outline;
        if (shadow) {
            RenderSystem.setTextEffects(true, 1F,
                    effectOffset(style.shadowOffsetX() / toGui), effectOffset(style.shadowOffsetY() / toGui),
                    style.shadowR(), style.shadowG(), style.shadowB());
            drawQuad(texture, quad, r, g, b, a);
        } else if (outline) {
            // The outline branch ignores the shadow colour: the shader only uses one
            // of the two effects at a time, because a shadow under an outline is
            // invisible anyway.
            float thickness = effectOffset(style.outlineThickness() / toGui);
            RenderSystem.setTextEffects(true, 2F, thickness, thickness,
                    style.outlineR(), style.outlineG(), style.outlineB());
            drawQuad(texture, quad, r, g, b, a);
        }
        // The core, last so it sits over the inside of its own halo. Bold makes this pass the
        // glyph *dilated* in the ink colour rather than a plain fill: the shader's outline branch
        // samples one texel out in all four directions and paints that as ink, which thickens
        // every stroke by the same amount on every side. A translated second copy - the usual
        // faux bold - was the first attempt here and reads as two glyphs printed over each other,
        // because at these stroke widths a shift is a shift, not a thickening.
        RenderSystem.setTextEffects(style.hasBold(), 2F, BOLD_PIXELS * TEXEL, BOLD_PIXELS * TEXEL,
                r, g, b);
        drawQuad(texture, quad, r, g, b, a);
        if (effected || style.hasBold()) {
            RenderSystem.setTextEffects(false, 0F, 0F, 0F, 1F, 1F, 1F);
        }
    }

    /** One textured glyph quad, in GUI units, at the layer the effect uniforms describe. */
    private static void drawQuad(com.pvzce.client.renderer.texture.Texture texture, GlyphQuad quad,
                                 float r, float g, float b, float a) {
        SpriteRenderer.texturedRegion(texture, quad.u0(), quad.v0(), quad.u1(), quad.v1(),
                quad.left(), quad.bottom(), quad.width(), quad.height(), 0F, r, g, b, a);
    }

    /** The atlas page as a {@link Sprite}-compatible texture handle. */
    private static com.pvzce.client.renderer.texture.Texture textureOf(GlyphRef glyph) {
        return glyph.slot().page().texture();
    }

    /**
     * A device-pixel effect offset as the uv delta the shader samples at, capped at
     * {@link #MAX_EFFECT_PIXELS}. Signed, because a shadow's direction is the sign of
     * its offset.
     */
    private static float effectOffset(float devicePixels) {
        float capped = Math.max(-MAX_EFFECT_PIXELS, Math.min(MAX_EFFECT_PIXELS, devicePixels));
        return capped * TEXEL;
    }

    private static FontFace load(PvzceResourceManager resources, String file) {
        FontFace face = loadOptional(resources, file);
        if (face == null) {
            throw new IllegalStateException("Missing bundled font assets/pvzce/font/" + file + ".ttf");
        }
        return face;
    }

    /** Loads a face, returning null when the pack stack does not have it. */
    private static FontFace loadOptional(PvzceResourceManager resources, String file) {
        try {
            var resource = resources.getAsset(Identifier.withDefaultNamespace("font/" + file + ".ttf"));
            if (resource.isEmpty()) {
                return null;
            }
            PackResource font = resource.get();
            return new FontFace(file, font.bytes());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read bundled font " + file, e);
        }
    }
}

/**
 * Where one glyph goes, in GUI units, and which texels of the atlas it shows.
 *
 * <p>Split out of the draw loop as pure geometry: "does a glyph land on the baseline
 * its metrics asked for, and does it show the ink the right way up" is then a
 * question a test can ask without a GL context, by sampling an atlas with these UVs.
 * {@link FontRenderer} is the only caller.
 *
 * @param left   left edge of the ink, from the pen origin (already scaled)
 * @param bottom y of the ink's bottom edge, in the caller's space (y grows upwards, so
 *               this is usually below the baseline's y - see {@link #of})
 * @param u0     atlas coordinate of the ink's left edge
 * @param v0     atlas coordinate of the ink's bottom edge
 * @param u1     atlas coordinate of the ink's right edge
 * @param v1     atlas coordinate of the ink's top edge
 */
record GlyphQuad(float left, float bottom, float width, float height,
                 float u0, float v0, float u1, float v1) {

    /**
     * @param penX    where the pen is, in GUI units
     * @param anchorY the y a caller passed to the renderer: the line's baseline, which is
     *                what the ink is placed against (see the class comment)
     * @param toGui   GUI units per device pixel, for the glyph's device-pixel metrics
     */
    static GlyphQuad of(GlyphRef glyph, float penX, float anchorY, float toGui) {
        return new GlyphQuad(
                penX + glyph.visualLeft() * toGui,
                // visualBottom is a signed offset from the baseline in the same up-positive
                // sense as the GUI's y: zero for a glyph that sits on the baseline, negative
                // for ink that hangs below it. So the ink's bottom edge is the baseline plus
                // that offset, and a comma ends up under the line instead of through it.
                anchorY + glyph.visualBottom() * toGui,
                glyph.width() * toGui,
                glyph.height() * toGui,
                glyph.slot().u0(), glyph.slot().v0(), glyph.slot().u1(), glyph.slot().v1());
    }
}
