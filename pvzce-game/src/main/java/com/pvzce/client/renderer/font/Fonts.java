package com.pvzce.client.renderer.font;

import com.pvzce.common.resource.PvzceResourceManager;

import java.util.List;
import java.util.function.IntSupplier;

/**
 * The project's fonts, by role.
 *
 * <p>Call sites ask for a role, never for a file or a family: which face satisfies
 * "button" or "body" is decided once, in {@link FontRenderer}, so swapping a
 * typeface or adding a weight is a change in one place instead of 260.
 *
 * <pre>{@code
 * client.fonts().button().draw("开始游戏", x, y, scale, 1F, 1F, 1F, 1F);
 * client.fonts().body().drawWrapped(hint, x, y, width, scale, ...);
 * }</pre>
 *
 * <h2>Coordinates</h2>
 * {@code y} is the top of the line box, exactly as it was for the bitmap renderer
 * this replaced, so existing layouts keep meaning what they meant. Every draw
 * method returns the top of the <em>next</em> line, so a caller that stacks text
 * does not repeat that arithmetic. {@link FontRole#lineHeight(float)} is the step
 * between lines and {@link FontRole#ascent(float)} the baseline offset from the top.
 */
public final class Fonts implements AutoCloseable {
    private final FontRenderer renderer;
    private final FontRole button;
    private final FontRole body;
    private final FontRole bodyMedium;
    private final FontRole serif;

    public Fonts(PvzceResourceManager resources, IntSupplier guiScale) {
        renderer = new FontRenderer(resources, guiScale);
        button = new FontRole(renderer, renderer.button());
        body = new FontRole(renderer, renderer.body());
        bodyMedium = new FontRole(renderer, renderer.bodyMedium());
        serif = new FontRole(renderer, renderer.serif());
    }

    /** 站酷快乐体: buttons, titles and dialogue. */
    public FontRole button() {
        return button;
    }

    /** 思源黑体: hints, labels, numbers - the default for everything short. */
    public FontRole body() {
        return body;
    }

    /** 思源黑体 Medium, for the odd label that needs to stand out. */
    public FontRole bodyMedium() {
        return bodyMedium;
    }

    /** 思源宋体: long-form prose. */
    public FontRole serif() {
        return serif;
    }

    /**
     * Drops every rasterised glyph. For a resource reload: nothing else identifies
     * the atlases to the pack stack, and a pack that swaps a TTF needs the old
     * outlines gone. The next frame rasterises what it draws again.
     */
    public void invalidate() {
        renderer.invalidate();
    }

    /** Writes every atlas page under {@code directory}; see {@code -Dpvzce.dumpFontAtlas}. */
    public void dumpAtlases(java.nio.file.Path directory) {
        renderer.dumpAtlases(directory);
    }

    /** One line per loaded face: pages, cached glyphs, covered codepoints. */
    public String debugInfo() {
        return renderer.debugInfo();
    }

    @Override
    public void close() {
        renderer.close();
    }

    /**
     * One role, ready to measure and draw.
     *
     * <p>A thin view rather than the renderer itself, so the API a call site sees
     * has no notion of device pixels, atlas pages or fallback chains.
     */
    public static final class FontRole {
        private final FontRenderer renderer;
        private final FontFamily family;

        FontRole(FontRenderer renderer, FontFamily family) {
            this.renderer = renderer;
            this.family = family;
        }

        /** The role's name, for diagnostics. */
        public String name() {
            return family.name();
        }

        // ---------- drawing ----------

        /** Draws one line (or several, split on {@code \n}) and returns the next line's top. */
        public float draw(String text, float x, float y, float scale, float r, float g, float b, float a) {
            return renderer.draw(family, text, x, y, scale, r, g, b, a, TextStyle.NONE);
        }

        /** Draws with an outline or drop shadow - see {@link TextStyle}. */
        public float draw(String text, float x, float y, float scale, float r, float g, float b, float a,
                          TextStyle style) {
            return renderer.draw(family, text, x, y, scale, r, g, b, a, style);
        }

        /** Draws centred on {@code centerX} and returns the next line's top. */
        public float drawCentered(String text, float centerX, float y, float scale,
                                  float r, float g, float b, float a) {
            return renderer.drawCentered(family, text, centerX, y, scale, r, g, b, a, TextStyle.NONE);
        }

        /** Draws centred on {@code centerX} with an effect. */
        public float drawCentered(String text, float centerX, float y, float scale,
                                  float r, float g, float b, float a, TextStyle style) {
            return renderer.drawCentered(family, text, centerX, y, scale, r, g, b, a, style);
        }

        /** Draws with the right edge at {@code rightX} and returns the next line's top. */
        public float drawRight(String text, float rightX, float y, float scale,
                               float r, float g, float b, float a) {
            return renderer.drawRight(family, text, rightX, y, scale, r, g, b, a, TextStyle.NONE);
        }

        /** Draws already-broken lines, one per entry, and returns the next line's top. */
        public float drawLines(List<String> lines, float x, float y, float scale,
                               float r, float g, float b, float a) {
            return renderer.drawLines(family, lines, x, y, scale, r, g, b, a, TextStyle.NONE);
        }

        /** Draws already-broken lines with an effect. */
        public float drawLines(List<String> lines, float x, float y, float scale,
                               float r, float g, float b, float a, TextStyle style) {
            return renderer.drawLines(family, lines, x, y, scale, r, g, b, a, style);
        }

        /**
         * Wraps to {@code maxWidth}, draws the result and returns the next line's top.
         * The one call for long prose: the same wrap that {@link #wrapLines} does,
         * so a bubble that measured its lines draws exactly those lines.
         */
        public float drawWrapped(String text, float x, float y, float maxWidth, float scale,
                                 float r, float g, float b, float a) {
            return drawWrapped(text, x, y, maxWidth, scale, r, g, b, a, TextStyle.NONE);
        }

        /** Wraps to {@code maxWidth}, draws the result with an effect. */
        public float drawWrapped(String text, float x, float y, float maxWidth, float scale,
                                 float r, float g, float b, float a, TextStyle style) {
            return renderer.drawLines(family, renderer.wrapLines(family, text, maxWidth, scale),
                    x, y, scale, r, g, b, a, style);
        }

        // ---------- measuring ----------

        /** Width of the widest line, in GUI units. */
        public float width(String text, float scale) {
            return renderer.width(family, text, scale);
        }

        /** Line height in GUI units: the step between stacked lines. */
        public int lineHeight(float scale) {
            return renderer.lineHeight(family, scale);
        }

        /** Baseline offset from the top of the line box, in GUI units. */
        public float ascent(float scale) {
            return renderer.ascent(family, scale);
        }

        /**
         * Breaks text into lines that each fit {@code maxWidth}, honouring explicit
         * newlines. Breaks between characters rather than at spaces: this is a CJK
         * UI, where a sentence has no spaces to break at, and a Latin word that
         * overflows is still better split than left running off the panel.
         */
        public List<String> wrapLines(String text, float maxWidth, float scale) {
            return renderer.wrapLines(family, text, maxWidth, scale);
        }
    }
}
