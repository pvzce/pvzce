package com.pvzce.server.level;

import com.pvzce.api.util.Identifier;

/**
 * How a level id becomes a file name: the world-save directory key and the completion marker.
 *
 * <p>The old rule was {@code namespace + "__" + path.replace('/', '_')}. It was not
 * injective: {@code pvzce:a/b} and {@code pvzce:a_b} produced the same key, so the two levels
 * shared one run save and one completion marker - the second level to be saved silently
 * overwrote the first, with nothing in the log. Level ids are allowed to contain {@code _}
 * and {@code /} and both are in use, so "just replace the slash" could never be made safe.
 *
 * <p>The rule now: the namespace hex-encoded, then {@code __}, then the escaped path.
 *
 * <ul>
 *   <li><b>Namespace → hex.</b> {@code pvzce} → {@code 70767a6365}. A namespace and a path
 *       share almost their whole alphabet ({@code _}, {@code .}, {@code -} and
 *       alphanumerics), so any plain separator between them is ambiguous: {@code pvzce_} +
 *       {@code a_b} and {@code pvzce} + {@code _a_b} both read as {@code pvzce___a_b}. Hex
 *       leaves no character in common with the path half, so the boundary is exact.
 *   <li><b>Path → escaped.</b> {@code %} → {@code %25}, {@code /} → {@code %2F}, everything
 *       else literal. One output character per input character, so the path half is injective
 *       on its own and stays readable: the level's own name is right there in the directory
 *       name.
 * </ul>
 *
 * <p>Together the two halves are injective, which is the property that matters: distinct
 * level ids can never share a save directory. Both parts of that are pinned by
 * {@code LevelKeyTest.distinctIdsNeverShareAKey} over a hostile set of ids.
 *
 * <p>Ugly on purpose. A directory name nobody enjoys reading is a better outcome than two
 * levels quietly sharing one run.
 */
public final class LevelKey {
    /** Separates the hex namespace half from the escaped path half. */
    private static final String SEPARATOR = "__";
    private static final String ESCAPED_ESCAPE = "%25";
    private static final String ESCAPED_SLASH = "%2F";
    /** Two hex digits per namespace character: identifiers are ASCII, so this is exact. */
    private static final int HEX_DIGITS_PER_CHAR = 2;

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
}
