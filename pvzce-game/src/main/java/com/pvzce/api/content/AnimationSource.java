package com.pvzce.api.content;

import com.pvzce.api.util.Identifier;

/**
 * Which animation file a definition uses, and which directory it lives in.
 *
 * <p>The file path used to be derived from the content id alone
 * ({@code assets/<ns>/animations/<id.path()>.json}), which made the animation
 * directory a mirror of the id and left no room to group animations by kind:
 * the shipped art is organised as {@code animations/plant/attacker/pea_shooter.json}
 * while the content id stays {@code pvzce:pea_shooter}. The directory is now
 * declared by the definition; a definition that declares nothing keeps the old
 * id-derived path, so a mod still only has to drop one file in one obvious place.
 *
 * <p>Split out of {@code AnimationBindings} because it is the one field that is
 * identical across plant, zombie, projectile, resource and tool definitions - the
 * bindings proper carry per-state overrides and stay where they were.
 */
public final class AnimationSource {
    /**
     * Namespace-relative directory under {@code assets/<ns>/}, without a leading or
     * trailing slash: {@code "plant/attacker"} for
     * {@code assets/pvzce/animations/plant/attacker/pea_shooter.json}.
     *
     * <p>An empty string means "directly under {@code animations/}"; the default
     * (absent field) means "mirror the content id path".
     */
    public static final com.mojang.serialization.Codec<String> CODEC =
            com.mojang.serialization.Codec.STRING;

    /**
     * The animation file id for a definition id.
     *
     * <p>With no declared directory the id path is returned unchanged, so a nested
     * id ({@code pvzce:upgrades/pea}) keeps resolving to
     * {@code animations/upgrades/pea.json} - the existing convention, which is also
     * why the directory may group a single leaf rather than a whole subtree.
     *
     * @param dir the declared directory, or {@code null} for the id-derived path
     */
    public static Identifier fileId(Identifier defId, String dir) {
        if (defId == null) {
            return null;
        }
        String base = normalize(dir);
        if (base == null) {
            return Identifier.of(defId.namespace(), defId.path());
        }
        String path = defId.path();
        int slash = path.lastIndexOf('/');
        String leaf = slash < 0 ? path : path.substring(slash + 1);
        return Identifier.of(defId.namespace(), base.isEmpty() ? leaf : base + "/" + leaf);
    }

    /**
     * Normalises a declared directory.
     *
     * @return {@code null} when nothing was declared (callers then use the id path),
     *         otherwise a slash-free, non-null directory that may be empty
     */
    public static String normalize(String dir) {
        if (dir == null) {
            return null;
        }
        String trimmed = dir.trim();
        while (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private AnimationSource() {
    }
}
