package com.pvzce.client.gui.screens;

import com.pvzce.api.util.Identifier;
import com.pvzce.common.util.LevelKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Moving a level between themes/categories.
 *
 * <p>The rename is the risky part: a level id keys the run save, the completion marker and
 * every reference to the level, so "changed the category" must not quietly orphan a run.
 * The three derivations are pure, and the two renames are exercised against a real
 * temporary directory.
 */
class LevelMoveTest {
    private static final Identifier YARD_ADVENTURE =
            Identifier.withDefaultNamespace("yard/adventure/1_1");
    private static final Identifier MOVED =
            Identifier.withDefaultNamespace("redstone/minigame/1_1");

    @Test
    void theLevelsOwnNameIsKept() {
        assertEquals("pvzce:redstone/minigame/1_1",
                LevelMove.idAfterMove(YARD_ADVENTURE,
                        Identifier.withDefaultNamespace("redstone"),
                        Identifier.withDefaultNamespace("minigame")).toString());
    }

    @Test
    void movingIntoTheBucketDropsThePath() {
        Identifier loose = LevelMove.idAfterMove(YARD_ADVENTURE,
                com.pvzce.api.util.LevelGrouping.UNCATEGORIZED,
                com.pvzce.api.util.LevelGrouping.UNCATEGORIZED);
        assertEquals("pvzce:1_1", loose.toString());
    }

    @Test
    void nothingToMoveIsNotAnError() {
        LevelMove.Result result = LevelMove.move(YARD_ADVENTURE,
                Identifier.withDefaultNamespace("yard"), Identifier.withDefaultNamespace("adventure"),
                null, null, null);
        assertFalse(result.ok());
        assertEquals(YARD_ADVENTURE, result.id());
        assertNull(result.problem());
    }

    @Test
    void movingRenamesTheLevelFileAndTheSave(@TempDir Path root) throws Exception {
        Path pack = root.resolve("datapacks/user_levels/data/pvzce/levels");
        Path oldFile = pack.resolve("yard/adventure/1_1.json");
        Files.createDirectories(oldFile.getParent());
        Files.writeString(oldFile, "{\"id\":\"pvzce:yard/adventure/1_1\"}");

        Path world = root.resolve("saves/world");
        // The location is LevelKey's answer, not a literal: this test is about the move, and
        // LevelKeyTest is where the spelling itself is pinned.
        Path saveDir = LevelKey.levelDir(world, YARD_ADVENTURE);
        Files.createDirectories(saveDir);
        Files.writeString(saveDir.resolve("level.dat"), "save");
        Path status = LevelKey.statusFile(world, YARD_ADVENTURE);
        Files.createDirectories(status.getParent());
        Files.writeString(status, "complete");

        Path newFile = pack.resolve("redstone/minigame/1_1.json");
        Files.createDirectories(newFile.getParent());
        Files.writeString(newFile, "{\"id\":\"pvzce:redstone/minigame/1_1\"}");

        Identifier redstone = Identifier.withDefaultNamespace("redstone");
        Identifier minigame = Identifier.withDefaultNamespace("minigame");
        LevelMove.Result result = LevelMove.move(YARD_ADVENTURE, redstone, minigame,
                oldFile, newFile, world);

        assertTrue(result.ok());
        assertEquals(MOVED, result.id());
        Identifier moved = result.id();
        assertFalse(Files.exists(oldFile), "the file the level used to live in is removed");
        assertTrue(Files.isRegularFile(newFile));
        assertTrue(Files.isRegularFile(LevelKey.levelDir(world, moved).resolve("level.dat")),
                "the run moves with the level");
        assertFalse(Files.isDirectory(saveDir), "the old save directory is gone");
        assertTrue(Files.isRegularFile(LevelKey.statusFile(world, moved)));
        assertFalse(Files.exists(status), "the old completion marker is gone");
    }

    /** A level that was never saved has no file to move, and the save move still applies. */
    @Test
    void anUnsavedLevelOnlyHasItsSaveMoved(@TempDir Path root) throws Exception {
        Path world = root.resolve("saves/world");
        Path saveDir = LevelKey.levelDir(world, YARD_ADVENTURE);
        Files.createDirectories(saveDir);
        Files.writeString(saveDir.resolve("level.dat"), "save");

        LevelMove.Result result = LevelMove.move(YARD_ADVENTURE,
                Identifier.withDefaultNamespace("redstone"), Identifier.withDefaultNamespace("minigame"),
                null, null, world);

        assertTrue(result.ok());
        assertTrue(Files.isRegularFile(LevelKey.levelDir(world, result.id()).resolve("level.dat")));
    }

    /** A save that is already at the target is left alone rather than overwritten. */
    @Test
    void anExistingTargetSaveIsNotClobbered(@TempDir Path root) throws Exception {
        Path world = root.resolve("saves/world");
        Path oldDir = LevelKey.levelDir(world, YARD_ADVENTURE);
        Path newDir = LevelKey.levelDir(world, MOVED);
        Files.createDirectories(oldDir);
        Files.createDirectories(newDir);
        Files.writeString(oldDir.resolve("level.dat"), "old");
        Files.writeString(newDir.resolve("level.dat"), "new");

        LevelMove.Result result = LevelMove.move(YARD_ADVENTURE,
                Identifier.withDefaultNamespace("redstone"), Identifier.withDefaultNamespace("minigame"),
                null, null, world);

        assertTrue(result.ok());
        assertEquals("new", Files.readString(newDir.resolve("level.dat")));
        assertEquals("old", Files.readString(oldDir.resolve("level.dat")),
                "the level that already lives there keeps its run");
    }
}
