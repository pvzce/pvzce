package com.pvzce.client.renderer.font;

import org.lwjgl.stb.STBTTFontinfo;
import org.lwjgl.stb.STBTruetype;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * One loaded TTF: the font file bytes, its codepoint map and its outlines.
 *
 * <p>Everything here speaks <em>font units at the requested pixel size</em>: a
 * glyph is rasterised on demand into an 8-bit alpha bitmap, and the metrics are
 * whatever stb_truetype reports scaled to that size. That size is the em box -
 * outlines, advances and kerning all scale by {@code pixelSize / unitsPerEm}, so
 * "font size 40" means the same thing for every face and matches the ascent and
 * descent a line is laid out with. Sizes are device pixels (framebuffer pixels),
 * not GUI pixels - the caller decides how many device pixels a line of text is
 * worth, and this class answers "what does that look like" for one glyph at a
 * time.
 *
 * <p>Not thread safe: glyph rasterisation touches the font's internal state, and
 * the whole renderer is single-threaded.
 */
final class TtfFace implements AutoCloseable {
    /**
     * A rasterised glyph: its coverage plus where it sits relative to the pen.
     *
     * <p>{@code pixels} is a plain array rather than a native buffer on purpose. It is
     * a copy of what stb produced, so it stays valid for as long as the glyph cache
     * keeps it (a native buffer would be reused by the next rasterisation) and the
     * atlas packer can read it without knowing anything about stb.
     */
    record Raster(byte[] pixels, int width, int height,
                  int left, int visualTop, int visualBottom, float advance) {
    }

    private final String name;
    /** Held for the lifetime of {@link #info}: stb points straight into these bytes. */
    private final ByteBuffer fontData;
    private final STBTTFontinfo info;
    private final Cmap cmap;
    private final int unitsPerEm;
    /** Baseline-to-top of the em box, in units per em (from the font's OS/2 typo metrics). */
    private final float ascentUnits;
    /** Baseline-to-bottom, in units per em; negative. */
    private final float descentUnits;

    /**
     * Every face this process has loaded, by file name.
     *
     * <p>Loads are cached and never repeated because {@code stbtt_InitFont} is not
     * safe to call many times in one process: calling it four times over four
     * different font buffers corrupts the native heap, and glibc then aborts with
     * "double free detected in tcache 2" at an unrelated moment. That was pinned down
     * by bisection - allocating, reading and freeing the exact same buffers with no
     * stb call at all is fine, {@code stbtt_GetFontOffsetForIndex} alone is fine, and
     * adding {@code stbtt_InitFont} is what breaks it, after a handful of calls.
     *
     * <p>Caching is also what the game wants anyway: four faces are loaded once at
     * startup and live for the session, so a reload that swaps a TTF keeps its face
     * and only re-rasterises glyphs.
     */
    private static final Map<String, TtfFace> LOADED = new HashMap<>();

    /**
     * The shared instance for a font file, loading it on first use.
     *
     * <p>Never closed: a face is a session-long resource, and the process-wide cache
     * is what keeps {@code stbtt_InitFont} from being called again.
     *
     * <p>The cache key is the file name without its extension: the game names the
     * asset itself and asks for {@code zhanku}, while a test is just as likely to ask
     * for {@code zhanku.ttf}, and both have to land on the same instance - loading one
     * font twice in a process is the one thing this cache exists to prevent.
     */
    static synchronized TtfFace of(String name, byte[] bytes) {
        String key = name.endsWith(".ttf") ? name.substring(0, name.length() - 4) : name;
        TtfFace existing = LOADED.get(key);
        if (existing != null) {
            return existing;
        }
        TtfFace created = new TtfFace(key, bytes);
        LOADED.put(key, created);
        return created;
    }

    private TtfFace(String name, byte[] bytes) {
        this.name = name;
        if (bytes.length < 12) {
            throw new IllegalArgumentException("Font " + name + " is too short to be a TTF");
        }
        // stb_truetype keeps pointers into this buffer rather than copying it, so it
        // has to outlive `info` and it has to be off-heap (stb would otherwise be
        // reading a moving array). Allocated with stb's own allocator on purpose:
        // BufferUtils.createByteBuffer falls back to Unsafe.allocateMemory when the
        // JVM's direct-buffer budget is tight, and that fallback's buffer advertises a
        // no-op cleaner - so the memory is never freed, the next allocation reuses the
        // same address, and the process dies with "double free detected in tcache 2".
        // memAlloc has no such budget and is released by close().
        if (bytes.length > Integer.MAX_VALUE - 8) {
            throw new IllegalArgumentException("Font " + name + " is implausibly large: " + bytes.length);
        }
        fontData = MemoryUtil.memAlloc(bytes.length);
        fontData.put(bytes).flip();
        // Every integer in a TTF is big endian (the sfnt format comes from the
        // Macintosh world). Without this the table directory decodes as nonsense and
        // the first table lookup reports a font with no cmap at all. stb reads the
        // bytes itself and is unaffected either way.
        fontData.order(java.nio.ByteOrder.BIG_ENDIAN);

        int offset = STBTruetype.stbtt_GetFontOffsetForIndex(fontData, 0);
        if (offset < 0) {
            throw new IllegalArgumentException("Font " + name + " has no face at index 0");
        }
        // stb_truetype is only asked for glyph outlines and glyph metrics here; the
        // codepoint-to-glyph mapping comes from our own Cmap, so a format 12 subtable
        // (which stb cannot read) is not a problem.
        info = STBTTFontinfo.create();
        if (!STBTruetype.stbtt_InitFont(info, fontData, offset)) {
            info.free();
            throw new IllegalArgumentException("stb_truetype rejected font " + name);
        }
        unitsPerEm = headUnitsPerEm(offset);
        cmap = Cmap.parse(fontData, offset + tableOffset(offset, "cmap"), tableLength(offset, "cmap"));
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer ascent = stack.mallocInt(1);
            IntBuffer descent = stack.mallocInt(1);
            IntBuffer lineGap = stack.mallocInt(1);
            // OS/2's typographic metrics, not hhea's: the hhea ones carry the Windows
            // GDI line spacing (Noto Sans SC reports 1160/-288, 1.45em), which is far
            // more leading than a UI wants and would also drag every glyph's visual
            // centre off the middle of its box. The typo metrics are the font's own
            // design intent - 880/-120 here, exactly 1.0em - and 站酷快乐体 publishes
            // the same values in both tables, so this is not a special case for the
            // CJK faces, it is what "one em tall" is supposed to mean.
            if (!STBTruetype.stbtt_GetFontVMetricsOS2(info, ascent, descent, lineGap)) {
                STBTruetype.stbtt_GetFontVMetrics(info, ascent, descent, lineGap);
            }
            ascentUnits = ascent.get(0) / (float) unitsPerEm;
            descentUnits = descent.get(0) / (float) unitsPerEm;
        }
    }

    String name() {
        return name;
    }

    /** How many codepoints this face can render; for tests and diagnostics. */
    int codepointCount() {
        return cmap.size();
    }

    /** True when the font maps the codepoint to a real glyph. */
    boolean covers(int codepoint) {
        return cmap.glyphIndex(codepoint) != 0;
    }

    /**
     * Pen movement for a codepoint at {@code pixelSize}, without rasterising it.
     * Zero when the font has no glyph for it.
     */
    float advance(int codepoint, float pixelSize) {
        int glyph = cmap.glyphIndex(codepoint);
        if (glyph == 0) {
            return 0F;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer advanceWidth = stack.mallocInt(1);
            IntBuffer leftSideBearing = stack.mallocInt(1);
            STBTruetype.stbtt_GetGlyphHMetrics(info, glyph, advanceWidth, leftSideBearing);
            return advanceWidth.get(0) * scaleFor(pixelSize);
        }
    }

    /**
     * Font units to pixels at {@code pixelSize}: the em is exactly that many pixels.
     *
     * <p>Not {@code stbtt_ScaleForPixelHeight}, which scales the font's <em>hhea</em>
     * line box to the requested size - 1160/-288, i.e. 1.448em, for every Noto face
     * bundled here - so the same call would give the body face glyphs 1.448x smaller
     * than the display face's at the same size, while {@link #ascent} and
     * {@link #descent} below (which come from OS/2 and are what every caller lays text
     * out with) describe a line of exactly one em. One requested size has to mean one
     * thing for the metrics and the outlines together, and that thing is the em box.
     */
    private float scaleFor(float pixelSize) {
        return pixelSize / unitsPerEm;
    }

    /** Baseline-to-top of a line, in pixels at {@code pixelSize}. */
    float ascent(float pixelSize) {
        return ascentUnits * pixelSize;
    }

    /** Baseline-to-bottom of a line (positive distance down), in pixels. */
    float descent(float pixelSize) {
        return -descentUnits * pixelSize;
    }

    /**
     * Rasterises one codepoint at {@code pixelSize}, or returns null when the font
     * has no glyph for it.
     *
     * <p>The ink is written into a scoped buffer this method owns and then copied to
     * a Java array before returning, so a {@link Raster} never aliases native memory
     * that the next call would overwrite - and there is no bitmap for the caller to
     * free.
     *
     * <p>{@code stbtt_MakeGlyphBitmap} is used rather than {@code stbtt_GetGlyphBitmap}
     * on purpose. The latter hands back a buffer LWJGL sized from its own out-params,
     * and those do not agree with what {@code stbtt_GetGlyphBitmapBox} reports for the
     * same glyph and scale (29x25 against 25x25 for a Latin capital) - so stb writes
     * past the end of the buffer it was given. That overwrite corrupts the native heap
     * and shows up much later as a glibc abort. Allocating the box ourselves and
     * handing stb an explicit width, height and stride makes the buffer size and the
     * write size the same expression.
     */
    Raster rasterize(int codepoint, float pixelSize) {
        int glyph = cmap.glyphIndex(codepoint);
        if (glyph == 0) {
            return null;
        }
        // The em is `pixelSize` tall, so the glyph comes out at the size the layout
        // asked for (see scaleFor). Hinting is on: at the sizes the UI uses (16-40
        // device pixels) it is the difference between crisp and mushy CJK strokes.
        float scale = scaleFor(pixelSize);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer advanceWidth = stack.mallocInt(1);
            IntBuffer leftSideBearing = stack.mallocInt(1);
            STBTruetype.stbtt_GetGlyphHMetrics(info, glyph, advanceWidth, leftSideBearing);
            float advance = advanceWidth.get(0) * scale;

            IntBuffer left = stack.mallocInt(1);
            IntBuffer top = stack.mallocInt(1);
            IntBuffer right = stack.mallocInt(1);
            IntBuffer bottom = stack.mallocInt(1);
            STBTruetype.stbtt_GetGlyphBitmapBox(info, glyph, scale, scale, left, top, right, bottom);
            int width = right.get(0) - left.get(0);
            int height = bottom.get(0) - top.get(0);
            if (width <= 0 || height <= 0) {
                // A space, or a glyph drawn with no ink at all. It still advances.
                return new Raster(null, 0, 0, 0, 0, 0, advance);
            }
            ByteBuffer ink = stack.malloc(width * height);
            STBTruetype.stbtt_MakeGlyphBitmap(info, ink, width, height, width, scale, scale, glyph);
            byte[] pixels = new byte[width * height];
            for (int i = 0; i < pixels.length; i++) {
                pixels[i] = ink.get(i);
            }
            // The box is in pixel coordinates with +y up, and `top` is above the
            // baseline (negative) while `bottom` is below it; the renderer works in +y
            // down from the baseline, so `visualTop` is the positive distance up to the
            // ink and `visualBottom` the (usually negative) distance down to it.
            return new Raster(pixels, width, height, left.get(0), -top.get(0), -bottom.get(0), advance);
        }
    }

    /** Kerning between two glyphs at {@code pixelSize}; 0 when the font has no pair. */
    float kern(int leftCodepoint, int rightCodepoint, float pixelSize) {
        int left = cmap.glyphIndex(leftCodepoint);
        int right = cmap.glyphIndex(rightCodepoint);
        if (left == 0 || right == 0) {
            return 0F;
        }
        return STBTruetype.stbtt_GetGlyphKernAdvance(info, left, right) * scaleFor(pixelSize);
    }

    /**
     * Writes the ink of one rasterisation into {@code destination} at the given
     * position. The coverage is one byte per pixel in row-major order, which is
     * exactly how the atlas wants it.
     */
    static void copyPixels(Raster raster, java.nio.ByteBuffer destination, int x, int y, int stride) {
        byte[] source = raster.pixels();
        if (source == null) {
            return;
        }
        int width = raster.width();
        for (int row = 0; row < raster.height(); row++) {
            int destinationRow = (y + row) * stride + x;
            for (int column = 0; column < width; column++) {
                destination.put(destinationRow + column, source[row * width + column]);
            }
        }
    }

    /**
     * Releases the font file bytes and stb's state.
     *
     * <p>Never called in normal operation: faces are shared, session-long resources
     * (see {@link #of}), and freeing one would leave every other holder of it dangling
     * as well as making the next load call {@code stbtt_InitFont} again. The method
     * exists for a test that needs to drop a face deliberately.
     */
    @Override
    public void close() {
        info.free();
        MemoryUtil.memFree(fontData);
    }

    private int tableOffset(int fontOffset, String tag) {
        int tables = fontData.getShort(fontOffset + 4) & 0xFFFF;
        for (int i = 0; i < tables; i++) {
            int record = fontOffset + 12 + i * 16;
            if (fontData.getInt(record) == tagBits(tag)) {
                return (int) (fontData.getInt(record + 8) & 0xFFFFFFFFL);
            }
        }
        throw new IllegalArgumentException("Font " + name + " has no " + tag + " table");
    }

    private int tableLength(int fontOffset, String tag) {
        int tables = fontData.getShort(fontOffset + 4) & 0xFFFF;
        for (int i = 0; i < tables; i++) {
            int record = fontOffset + 12 + i * 16;
            if (fontData.getInt(record) == tagBits(tag)) {
                return (int) (fontData.getInt(record + 12) & 0xFFFFFFFFL);
            }
        }
        return 0;
    }

    /** {@code head} is at a fixed position in its table; unitsPerEm sits at +18. */
    private int headUnitsPerEm(int fontOffset) {
        int head = fontOffset + tableOffset(fontOffset, "head");
        int value = fontData.getShort(head + 18) & 0xFFFF;
        // A font without a sane units-per-em is unusable; 1000 is the OpenType default
        // for CFF outlines, which is what a missing value most likely meant.
        return value == 0 ? 1000 : value;
    }

    private static int tagBits(String tag) {
        return (tag.charAt(0) << 24) | (tag.charAt(1) << 16) | (tag.charAt(2) << 8) | tag.charAt(3);
    }
}
