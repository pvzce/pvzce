package com.pvzce.common.resource;

/**
 * Path joining for pack listings.
 *
 * <p>The two pack implementations joined prefixes differently: {@code DirectoryPack}
 * built {@code prefix + '/' + relative} while {@code ClasspathPack} normalised the
 * prefix to always end in {@code /}. A caller passing {@code "data/"} therefore got
 * {@code data//x} from one pack and {@code data/x} from the other, and the loader -
 * which parsed the joined string - silently found nothing in directory packs while
 * working in the built-in one.
 */
public final class PackPaths {
    /** Normalises a prefix to {@code ""} or a single trailing-slash-free path. */
    public static String normalizePrefix(String prefix) {
        if (prefix == null || prefix.isEmpty() || "/".equals(prefix)) {
            return "";
        }
        String trimmed = prefix;
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    /** Joins a prefix and a relative path with exactly one separator. */
    public static String join(String prefix, String relative) {
        String base = normalizePrefix(prefix);
        String child = relative == null ? "" : relative;
        while (child.startsWith("/")) {
            child = child.substring(1);
        }
        if (base.isEmpty()) {
            return child;
        }
        return child.isEmpty() ? base : base + '/' + child;
    }

    private PackPaths() {
    }
}
