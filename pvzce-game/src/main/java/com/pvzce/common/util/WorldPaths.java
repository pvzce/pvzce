package com.pvzce.common.util;

import java.nio.file.Path;

/**
 * Where a world lives on disk, and what its name is allowed to be.
 *
 * <p>One rule, three former copies. The server derived the directory of a world's profile and
 * saves, the title screen's player picker created the directory, and the editor looked one up -
 * and the picker's copy had drifted in two ways: it did not trim, and an empty name became
 * {@code 新世界} rather than {@code world}. Since the directory name is what the world list reads
 * back, an unnamed player was created in {@code saves/新世界} while the server sanitised the same
 * name to {@code ___} and wrote the profile somewhere the list never showed: the progress looked
 * like it had vanished.
 *
 * <p>The rule: trim, then replace every character outside {@code [A-Za-z0-9_-]} with {@code _},
 * and use {@code world} when nothing is left. It has to be a pure function of a string, because
 * both sides derive the path from the name alone and neither may ask the other.
 */
public final class WorldPaths {
    /** The name an empty or fully-sanitised-away world name becomes. */
    public static final String DEFAULT_WORLD = "world";

    private static final String SAVES_DIR = "saves";
    private static final String DISALLOWED = "[^A-Za-z0-9_-]";

    private WorldPaths() {
    }

    /** The directory name for a world name as typed by the player. */
    public static String sanitize(String worldName) {
        if (worldName == null) {
            return DEFAULT_WORLD;
        }
        String safe = worldName.trim().replaceAll(DISALLOWED, "_");
        return safe.isBlank() ? DEFAULT_WORLD : safe;
    }

    /** Every world of this game directory. */
    public static Path savesDir(Path gameDir) {
        return gameDir.resolve(SAVES_DIR);
    }

    /** One world's directory: {@code <gameDir>/saves/<sanitised name>}. */
    public static Path worldDir(Path gameDir, String worldName) {
        return savesDir(gameDir).resolve(sanitize(worldName));
    }
}
