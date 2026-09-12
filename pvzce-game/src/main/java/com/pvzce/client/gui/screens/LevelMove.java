package com.pvzce.client.gui.screens;

import com.pvzce.api.util.Identifier;
import com.pvzce.api.util.LevelGrouping;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Moving a level to another theme/category: rename its id, move its file, move its save.
 *
 * <p>This is the only operation that changes a level id, and it is deliberately a separate,
 * explicit step rather than a side effect of editing the level's name. A level id is not
 * just a label - the run save directory, the completion marker and every reference to the
 * level are keyed by it - so the rename has to carry those along or the progress silently
 * disappears.
 *
 * <p>Static and free of the client so the arithmetic can be tested without a window:
 * {@link #idAfterMove}, {@link #saveKeyAfterMove} and {@link #saveDirectoryAfterMove} are
 * the three derivations, and the rest is the two renames.
 */
final class LevelMove {
    /** What moving a level does, or why it does not. */
    record Result(Identifier id, boolean moved, String problem) {
        static Result unchanged(Identifier id) {
            return new Result(id, false, null);
        }

        static Result refused(Identifier id, String problem) {
            return new Result(id, false, problem);
        }

        boolean ok() {
            return moved;
        }
    }

    private LevelMove() {
    }

    /**
     * The id a level gets when moved to {@code theme}/{@code category}.
     *
     * <p>The level's own remaining name is kept: moving {@code yard/adventure/1_1} to
     * {@code redstone/minigame} gives {@code redstone/minigame/1_1}. The unclassified
     * bucket has no theme in the id, so moving into it reduces the level to its leaf name.
     */
    static Identifier idAfterMove(Identifier current, Identifier theme, Identifier category) {
        if (current == null) {
            return null;
        }
        String name = LevelGrouping.leafName(current);
        Identifier moved = LevelGrouping.levelId(current.namespace(), theme, category, name);
        return moved == null ? null : Identifier.tryParse(moved.toString());
    }

    /**
     * The world-save directory key for a level id, mirroring the server's {@code LevelKey}.
     *
     * <p>Duplicated here on purpose: this screen runs on the client and has to be able to say
     * where a save <em>would</em> live - and move it - without a server round trip. The two
     * must produce the same string, which is what {@code LevelMoveTest} pins.
     *
     * <p>The rule is "hex namespace, {@code __}, path with {@code %} and {@code /} escaped",
     * which is injective: two different level ids can never share a save directory. The old
     * rule replaced {@code /} with {@code _} and could not tell {@code a/b} from {@code a_b}.
     */
    static String saveKeyAfterMove(Identifier id) {
        if (id == null) {
            return "";
        }
        return hexNamespace(id.namespace()) + "__" + escapeForSaveKey(id.path());
    }

    private static String hexNamespace(String namespace) {
        StringBuilder builder = new StringBuilder(namespace.length() * 2);
        for (int i = 0; i < namespace.length(); i++) {
            char c = namespace.charAt(i);
            builder.append(Character.forDigit((c >> 4) & 0xF, 16)).append(Character.forDigit(c & 0xF, 16));
        }
        return builder.toString();
    }

    private static String escapeForSaveKey(String path) {
        StringBuilder builder = new StringBuilder(path.length());
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            switch (c) {
                case '%' -> builder.append("%25");
                case '/' -> builder.append("%2F");
                default -> builder.append(c);
            }
        }
        return builder.toString();
    }

    /** The per-level save directory inside a world's {@code saves/} folder. */
    static Path saveDirectoryAfterMove(Path worldDirectory, Identifier id) {
        if (worldDirectory == null) {
            return null;
        }
        return worldDirectory.resolve("levels").resolve(saveKeyAfterMove(id));
    }

    /** The completion marker for a level inside a world's {@code saves/} folder. */
    static Path statusFileAfterMove(Path worldDirectory, Identifier id) {
        if (worldDirectory == null) {
            return null;
        }
        return worldDirectory.resolve("level_status").resolve(saveKeyAfterMove(id) + ".dat");
    }

    /**
     * Moves the level file and its save, and reports the id to continue with.
     *
     * <p>Order matters: the level file is moved first so a failure to move the save leaves a
     * loadable level, and the old file is only deleted after the new one has been written by
     * the caller. Everything is best-effort and reported, never thrown - a mis-typed
     * category should not cost the author the level.
     *
     * @param levelFile     the file the level came from, or {@code null} for an unsaved level
     * @param newFile       where the level is being saved now
     * @param worldDirectory {@code saves/<world>} for the running world, or {@code null}
     */
    static Result move(Identifier current, Identifier theme, Identifier category,
                       Path levelFile, Path newFile, Path worldDirectory) {
        Identifier moved = idAfterMove(current, theme, category);
        if (moved == null) {
            return Result.refused(current, "目标分类无法生成合法的关卡 ID");
        }
        if (moved.equals(current)) {
            return Result.unchanged(current);
        }
        try {
            if (levelFile != null && !levelFile.equals(newFile)) {
                Files.deleteIfExists(levelFile);
            }
            if (worldDirectory != null) {
                moveSave(worldDirectory, current, moved);
            }
        } catch (IOException e) {
            return Result.refused(moved, "移动存档失败：" + e.getMessage());
        }
        return new Result(moved, true, null);
    }

    /**
     * Renames the run save and the completion marker.
     *
     * <p>Failure to move the save does not refuse the level move: the level is still where
     * the author put it, and a run that cannot be carried over is a level that starts over -
     * which is what "not migrated" means everywhere else. The message says so.
     */
    private static void moveSave(Path worldDirectory, Identifier from, Identifier to) throws IOException {
        Path fromDir = saveDirectoryAfterMove(worldDirectory, from);
        Path toDir = saveDirectoryAfterMove(worldDirectory, to);
        if (Files.isDirectory(fromDir) && !Files.exists(toDir)) {
            Files.createDirectories(toDir.getParent());
            Files.move(fromDir, toDir);
        }
        Path fromStatus = statusFileAfterMove(worldDirectory, from);
        Path toStatus = statusFileAfterMove(worldDirectory, to);
        if (Files.isRegularFile(fromStatus) && !Files.exists(toStatus)) {
            Files.createDirectories(toStatus.getParent());
            Files.move(fromStatus, toStatus);
        }
    }
}
