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
 * {@code y} is the <em>top of the line box</em>, exactly as it was for the old
 * bitmap renderer: call sites pass the top of the box they laid out and this
 * class derives the baseline from real font metrics. {@link #draw} returns the
 * top of the next line, and {@link #drawLines} advances through a list, so
 * stacking call sites do not repeat the arithmetic.
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
     * <p>Calibrated against the bitmap atlas this replaced, which drew a 98px font into
     * 18-unit lines: its full-width "中" advanced 16.9 units and its ink was about 22
     * units tall. 思源黑体's own "中" is 0.69em wide and 0.67em tall, so an em of 72
     * units reproduces both - 16.5 units of advance and 21.7 of ink. Getting this wrong
     * is not subtle: at the first attempt (em 20) a CJK glyph was 5 units tall and every
     * label read as a solid black blob.
     */
    private static final float BODY_EM = 72F;
    /**
     * The display face is larger, because 站酷快乐体's hanzi are drawn smaller on their em
     * than 思源黑体's: at em 76 its ink is 15 units where the body face reaches 21.7, so
     * the display role asks for more to land at the same optical size.
     */
    private static final float DISPLAY_EM = 90F;
    /** Long-form serif text reads at the body size. */
    private static final float SERIF_EM = 72F;
    /**
     * Below this many device pixels 站酷快乐体's strokes merge into a blob, so the
     * display role quietly switches to 思源黑体: readability beats house style.
     * 16 device pixels is roughly where the two stop being distinguishable.
     */
    private static final int DISPLAY_MIN_PIXELS = 16;


    private static int GL30_TEXTURE_BINDING_2D() {
        return org.lwjgl.opengl.GL11.GL_TEXTURE_BINDING_2D;
    }

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

    /** Draws one line (or several, split on {@code \n}) and returns the next line's top. */
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

    /** Draws centred on {@code centerX} and returns the next line's top. */
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

    /** Draws with the right edge at {@code rightX} and returns the next line's top. */
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
     * Line height, in GUI units: what a stacked line advances by. Rounded to a whole
     * unit, like the bitmap renderer's line height was.
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

    /** The baseline offset from the top of the line box, in GUI units. */
    public float ascent(float scale) {
        return ascent(body, scale);
    }

    /** The baseline offset for an explicit role, in GUI units. */
    public float ascent(FontFamily family, float scale) {
        int size = devicePixelSize(family, scale);
        return family.baselineFromTop(size) / Math.max(1, guiScale.getAsInt());
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
        float baseline = y - family.ascent(size) * toGui;
        float left = x + glyph.visualLeft() * toGui;
        float bottom = baseline - glyph.visualBottom() * toGui;
        float width = glyph.width() * toGui;
        float height = glyph.height() * toGui;
        renderGlyph(glyph, left, bottom, width, height, r, g, b, a, style, shadow, outline, toGui);
    }

    /** One glyph quad, with the shader effect uniforms set for it. */
    private void renderGlyph(GlyphRef glyph, float x, float y, float width, float height,
                             float r, float g, float b, float a, TextStyle style,
                             boolean shadow, boolean outline, float toGui) {
        if (shadow) {
            RenderSystem.setTextEffects(true, 1F,
                    style.shadowOffsetX() / toGui, style.shadowOffsetY() / toGui,
                    style.shadowR(), style.shadowG(), style.shadowB());
        } else if (outline) {
            // The outline branch ignores the shadow colour: the shader only uses one
            // of the two effects at a time, because a shadow under an outline is
            // invisible anyway.
            RenderSystem.setTextEffects(true, 2F,
                    style.outlineThickness() / toGui, style.outlineThickness() / toGui,
                    style.outlineR(), style.outlineG(), style.outlineB());
        }
        SpriteRenderer.texturedRegion(textureOf(glyph),
                glyph.slot().u0(), glyph.slot().v0(), glyph.slot().u1(), glyph.slot().v1(),
                x, y, width, height, 0F, r, g, b, a);
        if (shadow || outline) {
            RenderSystem.setTextEffects(false, 0F, 0F, 0F, 1F, 1F, 1F);
        }
    }

    /** The atlas page as a {@link Sprite}-compatible texture handle. */
    private static com.pvzce.client.renderer.texture.Texture textureOf(GlyphRef glyph) {
        return glyph.slot().page().texture();
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
