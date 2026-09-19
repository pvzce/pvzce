package com.pvzce.common.util;

import com.pvzce.api.util.Identifier;

import java.nio.file.Path;

/**
 * How a level id becomes a path: the run-save directory, its file name and the completion marker.
 *
 * <p>One rule, one place. Both the server (which writes the save) and the client's editor (which
 * has to move a save without a server round trip) derive every one of these names from here;
 * while the rule was written twice, the two spellings could drift apart and a moved save would
 * silently stop being found.
 *
 * <p><b>Constraint:</b> the key must be <em>injective</em> - distinct level ids may never share a
 * directory. {@code pvzce:a/b} and {@code pvzce:a_b} are both legal ids and both in use, so the
 * obvious {@code path.replace('/', '_')} cannot be made safe. The rule is therefore the namespace
 * hex-encoded, then {@code __}, then the path with {@code %} and {@code /} escaped: a namespace
 * and a path share nearly their whole alphabet, but the hex half leaves no character in common
 * with the path half, so the boundary is exact. Ugly directory names are the price; the
 * alternative was two levels quietly sharing one run. Pinned by
 * {@code LevelKeyTest.distinctIdsNeverShareAKey}. (History: see {@code docs/架构变更记录.md}.)
 */
public final class LevelKey {
    /** Separates the hex namespace half from the escaped path half. */
    private static final String SEPARATOR = "__";
    private static final String ESCAPED_ESCAPE = "%25";
    private static final String ESCAPED_SLASH = "%2F";
    /** Two hex digits per namespace character: identifiers are ASCII, so this is exact. */
    private static final int HEX_DIGITS_PER_CHAR = 2;

    /** The directory holding the run save of every level of one world. */
    private static final String LEVELS_DIR = "levels";
    /** The directory holding one completion marker per cleared level. */
    private static final String STATUS_DIR = "level_status";
    private static final String STATUS_SUFFIX = ".dat";

    private LevelKey() {
    }

    /** The file name for a level: {@code <hex namespace>__<escaped path>}. */
    public static String of(Identifier id) {
        if (id == null) {
            return "";
        }
        return hex(id.namespace()) + SEPARATOR + escapePath(id.path());
    }

    /** The namespace half, hex-encoded so it cannot be confused with the path half. */
    public static String hex(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder(text.length() * HEX_DIGITS_PER_CHAR);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            for (int shift = (HEX_DIGITS_PER_CHAR - 1) * 4; shift >= 0; shift -= 4) {
                builder.append(Character.forDigit((c >> shift) & 0xF, 16));
            }
        }
        return builder.toString();
    }

    /** The path half of a key; see {@link #of}. */
    public static String escapePath(String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder(path.length());
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            switch (c) {
                case '%' -> builder.append(ESCAPED_ESCAPE);
                case '/' -> builder.append(ESCAPED_SLASH);
                default -> builder.append(c);
            }
        }
        return builder.toString();
    }

    /** One level's run save: {@code <world>/levels/<key>}. */
    public static Path levelDir(Path worldDir, Identifier id) {
        return worldDir.resolve(LEVELS_DIR).resolve(of(id));
    }

    /** One level's completion marker: {@code <world>/level_status/<key>.dat}. */
    public static Path statusFile(Path worldDir, Identifier id) {
        return worldDir.resolve(STATUS_DIR).resolve(of(id) + STATUS_SUFFIX);
    }
}
