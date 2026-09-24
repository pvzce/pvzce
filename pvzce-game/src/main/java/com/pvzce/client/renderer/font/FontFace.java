package com.pvzce.client.renderer.font;

import com.pvzce.api.util.Identifier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One typeface at any number of device pixel sizes: TTF outlines, a glyph cache,
 * and the atlas pages the cache packs into.
 *
 * <p>Three things are deliberately separate here, because each has a different
 * lifetime:
 * <ul>
 *   <li>the {@link TtfFace} (font file bytes + cmap) - loaded once, lives for the
 *       session;</li>
 *   <li>the local glyph cache, keyed by (codepoint, device pixel size) - lives
 *       until a page runs out of room, and holds stb's coverage bitmaps;</li>
 *   <li>the {@link GlyphPage} atlas pages - uploaded to the GPU, capped at
 *       {@link #MAX_PAGES}.</li>
 * </ul>
 *
 * <p>There is no eviction policy beyond "when the pages are full, start over":
 * a UI screen's worth of distinct glyphs fits in a single page with room to
 * spare, so the cap is a guard against unbounded growth (a player typing rare
 * characters into the editor) rather than a working set that needs managing.
 * Rebuilding is cheap - the next frame re-rasterises only what it draws.
 */
final class FontFace {
    /**
     * Pages per face before the atlas is recycled. Each 2048x2048 page holds a few
     * thousand hanzi at the sizes the UI asks for (an em is 14-56 device pixels, and a
     * cell is that plus {@link GlyphAtlas#GUTTER}), so this is a guard against unbounded
     * growth rather than a working set that has to be managed.
     */
    private static final int MAX_PAGES = 4;
    /**
     * Ceiling on the rasterisation size, in device pixels. The UI asks for 14-60; this
     * only matters on an extreme window scale, where drawing a slightly soft glyph beats
     * reserving a page per 160px character. Measuring and drawing clamp the same way, so
     * a clamped glyph still advances by the width it reports.
     */
    private static final int MAX_DEVICE_PIXEL_SIZE = 160;

    private final String name;
    private final TtfFace ttf;
    private final List<GlyphPage> pages = new ArrayList<>();
    private final Map<Long, Glyph> glyphs = new HashMap<>();
    /**
     * Cache of "this font has nothing for that codepoint", so a missing glyph is
     * not looked up in the cmap every frame. Absence of a key in {@link #glyphs}
     * means "not rasterised yet"; this set means "will never be".
     */
    private final Map<Long, Boolean> missing = new HashMap<>();

    FontFace(String name, byte[] fontBytes) {
        this.name = name;
        this.ttf = TtfFace.of(name, fontBytes);
    }

    String name() {
        return name;
    }

    /** How many codepoints the underlying font can render; for tests and diagnostics. */
    int codepointCount() {
        return ttf.codepointCount();
    }

    /** Baseline-to-top of a line, in device pixels at the given size. */
    float ascent(int devicePixelSize) {
        return ttf.ascent(clampSize(devicePixelSize));
    }

    /** Baseline-to-bottom of a line, in device pixels at the given size. */
    float descent(int devicePixelSize) {
        return ttf.descent(clampSize(devicePixelSize));
    }

    /** True when this face has a glyph for the codepoint. */
    boolean covers(int codepoint) {
        return ttf.covers(codepoint);
    }

    /**
     * The cached glyph for a codepoint, or null when this face cannot draw it.
     * Rasterises on first use.
     */
    Glyph glyph(int codepoint, int devicePixelSize) {
        int size = clampSize(devicePixelSize);
        long key = key(codepoint, size);
        Glyph glyph = glyphs.get(key);
        if (glyph != null) {
            return glyph;
        }
        if (missing.containsKey(key)) {
            return null;
        }
        TtfFace.Raster raster = ttf.rasterize(codepoint, size);
        if (raster == null) {
            missing.put(key, Boolean.TRUE);
            return null;
        }
        GlyphPage.Slot slot = pack(raster);
        if (slot == null) {
            // The cache has no room for this glyph right now. Reporting it as
            // uncovered makes this frame fall back, and the caller's next frame
            // rasterises it into the freshly recycled pages.
            recycle();
            return null;
        }
        glyph = new Glyph(slot, raster.advance(), raster.left(),
                raster.visualTop(), raster.visualBottom());
        glyphs.put(key, glyph);
        return glyph;
    }

    /** Pen movement for a codepoint, whether or not it has ink. 0 when uncovered. */
    float advance(int codepoint, int devicePixelSize) {
        int size = clampSize(devicePixelSize);
        Glyph glyph = glyphs.get(key(codepoint, size));
        if (glyph != null) {
            return glyph.advance();
        }
        if (missing.containsKey(key(codepoint, size))) {
            return 0F;
        }
        // A space has no ink but does advance. Going to the font directly keeps that
        // answer even when the glyph was never packed (spaces never are: they have no
        // ink, and packing a zero-pixel cell would only waste atlas room).
        return ttf.advance(codepoint, size);
    }

    /** Kerning between two codepoints, in device pixels; 0 when the font has no pair. */
    float kern(int leftCodepoint, int rightCodepoint, int devicePixelSize) {
        return ttf.kern(leftCodepoint, rightCodepoint, clampSize(devicePixelSize));
    }

    /** Frees the atlas pages and every cached glyph, keeping the font itself. */
    void recycle() {
        for (GlyphPage page : pages) {
            page.close();
        }
        pages.clear();
        glyphs.clear();
        missing.clear();
    }

    /**
     * Number of atlas pages currently uploaded; for the smoke diagnostics line.
     * A page that has nothing in it yet is still counted, because it holds VRAM.
     */
    int pageCount() {
        return pages.size();
    }

    /** Writes every atlas page to {@code directory}; for diagnosing a glyph pipeline. */
    void dumpAtlases(java.nio.file.Path directory) {
        try {
            java.nio.file.Files.createDirectories(directory);
            for (int i = 0; i < pages.size(); i++) {
                pages.get(i).dump(directory.resolve(name + "-" + i + "-cpu.png"));
                pages.get(i).dumpFromGl(directory.resolve(name + "-" + i + "-gl.png"));
            }
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    String debugName() {
        return name + " (" + pages.size() + " pages, " + glyphs.size() + " glyphs, "
                + ttf.codepointCount() + " codepoints)";
    }

    void close() {
        recycle();
        ttf.close();
    }

    private GlyphPage.Slot pack(TtfFace.Raster raster) {
        for (GlyphPage page : pages) {
            GlyphPage.Slot slot = page.insert(raster);
            if (slot != null) {
                return slot;
            }
        }
        if (pages.size() >= MAX_PAGES) {
            return null;
        }
        GlyphPage page = new GlyphPage(Identifier.withDefaultNamespace(
                "font/" + name + "_atlas_" + pages.size()));
        pages.add(page);
        return page.insert(raster);
    }

    /** A codepoint and a size, packed into the long the cache maps use. */
    private static long key(int codepoint, int size) {
        return ((long) size << 32) | (codepoint & 0xFFFFFFFFL);
    }

    private static int clampSize(int devicePixelSize) {
        return Math.max(1, Math.min(MAX_DEVICE_PIXEL_SIZE, devicePixelSize));
    }
}
