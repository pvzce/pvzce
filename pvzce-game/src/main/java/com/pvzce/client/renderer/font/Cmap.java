package com.pvzce.client.renderer.font;

import java.nio.ByteBuffer;

/**
 * The {@code cmap} table: which codepoint is which glyph.
 *
 * <p>This is parsed here rather than left to {@code stbtt_FindGlyphIndex}, because
 * stb_truetype only understands cmap formats 0/4/6 while the fonts this project
 * bundles (Noto Sans/Serif SC) publish their mapping as format 4 <em>plus</em>
 * format 12. Format 12 is the only one that reaches past the BMP, and the fonts
 * cover up to U+3106C - so deferring to stb would have silently turned every
 * astral-plane character into a missing glyph.
 *
 * <p>Bitmap-only subtables (formats 1/2/8) are skipped: they are device-specific
 * legacy encodings, and a TTF that ships only one of those is not a text font.
 *
 * <p>Format 4's mapping is stored as deltas from a set of segments, which makes
 * it the one table whose values have to be interpreted rather than read - the
 * comments below are the specification condensed to what this code does.
 */
final class Cmap {
    /** The codepoints this face can actually render, ascending. */
    private final int[] codepoints;
    /** Glyph index per entry of {@link #codepoints}. */
    private final int[] glyphs;

    private Cmap(int[] codepoints, int[] glyphs) {
        this.codepoints = codepoints;
        this.glyphs = glyphs;
    }

    /** The highest codepoint the font maps, or -1 for an empty table. */
    int maxCodepoint() {
        return codepoints.length == 0 ? -1 : codepoints[codepoints.length - 1];
    }

    int size() {
        return codepoints.length;
    }

    /**
     * Glyph index for a codepoint, or 0 (the font's own "missing glyph") when the
     * font has no glyph for it - which is also what {@code stbtt} returns for a
     * glyph that exists but is blank, so callers treat both as "draw nothing".
     */
    int glyphIndex(int codepoint) {
        int low = 0;
        int high = codepoints.length - 1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            int value = codepoints[middle];
            if (value < codepoint) {
                low = middle + 1;
            } else if (value > codepoint) {
                high = middle - 1;
            } else {
                return glyphs[middle];
            }
        }
        return 0;
    }

    /**
     * Reads the best available subtable of {@code fontData} starting at the
     * table directory entry for {@code cmap}.
     *
     * @param cmapOffset absolute offset of the cmap table
     * @param cmapLength its length, used to reject a malformed subtable offset
     */
    static Cmap parse(ByteBuffer fontData, int cmapOffset, int cmapLength) {
        // cmap header: version(2) numTables(2), then one encoding record per table:
        // platformID(2) encodingID(2) offset(4, relative to the cmap table).
        int tableCount = u16(fontData, cmapOffset + 2);
        int bestScore = -1;
        int bestOffset = -1;
        for (int i = 0; i < tableCount; i++) {
            int record = cmapOffset + 4 + i * 8;
            int platform = u16(fontData, record);
            int encoding = u16(fontData, record + 2);
            int subtable = cmapOffset + (int) u32(fontData, record + 4);
            if (subtable < cmapOffset || subtable - cmapOffset >= cmapLength) {
                continue;
            }
            int format = u16(fontData, subtable);
            if (format != 4 && format != 12) {
                continue;
            }
            // Unicode full repertoire beats BMP-only; a (3,10)/(0,4)+(0,5) record is
            // the platform-neutral way to say "format 12" and is the strongest hint.
            int score = (format == 12 ? 4 : 0) + (platform == 3 && encoding == 10 ? 4 : 0)
                    + (platform == 0 ? 2 : 0) + (platform == 3 && encoding == 1 ? 1 : 0);
            if (score > bestScore) {
                bestScore = score;
                bestOffset = subtable;
            }
        }
        if (bestOffset < 0) {
            return new Cmap(new int[0], new int[0]);
        }
        return u16(fontData, bestOffset) == 12 ? parseFormat12(fontData, bestOffset)
                : parseFormat4(fontData, bestOffset);
    }

    /**
     * Format 4: BMP only, stored as ranges. A segment with {@code idDelta == 0}
     * and {@code idRangeOffset == 0} whose last glyph is 0xFFFF terminates the
     * table; segments whose {@code endCode < startCode} are not real ranges.
     */
    private static Cmap parseFormat4(ByteBuffer data, int base) {
        int length = u16(data, base + 2) / 2;
        int segCount = u16(data, base + 6) / 2;
        int endBase = base + 14;
        int startBase = endBase + segCount * 2 + 2;
        int deltaBase = startBase + segCount * 2;
        int rangeBase = deltaBase + segCount * 2;

        IntList codepoints = new IntList(Math.max(16, length));
        IntList glyphs = new IntList(Math.max(16, length));
        for (int segment = 0; segment < segCount; segment++) {
            int end = u16(data, endBase + segment * 2);
            int start = u16(data, startBase + segment * 2);
            int delta = (short) u16(data, deltaBase + segment * 2);
            int rangeOffset = u16(data, rangeBase + segment * 2);
            if (start > end) {
                continue;
            }
            for (int code = start; code <= end && code != 0x10000; code++) {
                int glyph;
                if (rangeOffset == 0) {
                    glyph = (code + delta) & 0xFFFF;
                } else {
                    // &idRangeOffset[i] is the address of the rangeOffset entry itself,
                    // so the array starts rangeOffset bytes further along.
                    int address = rangeBase + segment * 2 + rangeOffset + (code - start) * 2;
                    if (address + 2 > data.limit()) {
                        continue;
                    }
                    glyph = u16(data, address);
                    if (glyph != 0) {
                        glyph = (glyph + delta) & 0xFFFF;
                    }
                }
                if (glyph != 0) {
                    codepoints.add(code);
                    glyphs.add(glyph);
                }
            }
        }
        return new Cmap(codepoints.toArray(), glyphs.toArray());
    }

    /**
     * Format 12: 32-bit codepoint ranges, one record per contiguous group.
     * A group whose start equals its end covers exactly one codepoint; a group
     * with differing glyph ids fans out across the range.
     */
    private static Cmap parseFormat12(ByteBuffer data, int base) {
        int groups = (int) u32(data, base + 12);
        IntList codepoints = new IntList(1024);
        IntList glyphs = new IntList(1024);
        for (int group = 0; group < groups; group++) {
            int record = base + 16 + group * 12;
            long start = u32(data, record);
            long end = u32(data, record + 4);
            long glyph = u32(data, record + 8) - start;
            if (start > end || start > 0x10FFFFL) {
                continue;
            }
            for (long code = start; code <= end && code <= 0x10FFFFL; code++) {
                codepoints.add((int) code);
                glyphs.add((int) (code + glyph));
            }
        }
        return new Cmap(codepoints.toArray(), glyphs.toArray());
    }

    private static int u16(ByteBuffer data, int offset) {
        return data.getShort(offset) & 0xFFFF;
    }

    private static long u32(ByteBuffer data, int offset) {
        return data.getInt(offset) & 0xFFFFFFFFL;
    }

    /**
     * A tiny growable int array. Both parse paths know roughly how many entries
     * to expect (a 30k-glyph CJK font is ~20k entries) but not exactly, and the
     * alternative - boxing into ArrayList&lt;Integer&gt; - costs an allocation per
     * glyph in the one table every text draw consults.
     */
    private static final class IntList {
        private int[] values;
        private int size;

        IntList(int capacity) {
            values = new int[Math.max(4, capacity)];
        }

        void add(int value) {
            if (size == values.length) {
                int[] grown = new int[values.length * 2];
                System.arraycopy(values, 0, grown, 0, size);
                values = grown;
            }
            values[size++] = value;
        }

        int[] toArray() {
            int[] result = new int[size];
            System.arraycopy(values, 0, result, 0, size);
            return result;
        }
    }
}
