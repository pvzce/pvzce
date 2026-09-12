package com.pvzce.client.gui.screens;

import com.pvzce.api.util.Identifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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

    /**
     * The save key matches the server's own spelling - escaped, so it is injective.
     *
     * <p>Duplicated on the client so the editor can move a save without a round trip; this is
     * the assertion that keeps the two spellings identical. It also pins the fix for the old
     * key, which replaced {@code /} with {@code _} and let {@code a/b} and {@code a_b} share
     * one directory.
     */
    @Test
    void theSaveKeyMatchesTheServerSpelling() {
        assertEquals("70767a6365__yard%2Fadventure%2F1_1", LevelMove.saveKeyAfterMove(YARD_ADVENTURE));
        assertEquals("70767a6365__on_sea",
                LevelMove.saveKeyAfterMove(Identifier.withDefaultNamespace("on_sea")));
        assertNotEquals(LevelMove.saveKeyAfterMove(Identifier.withDefaultNamespace("a/b")),
                LevelMove.saveKeyAfterMove(Identifier.withDefaultNamespace("a_b")));
    }

    @Test
    void movingRenamesTheLevelFileAndTheSave(@TempDir Path root) throws Exception {
        Path pack = root.resolve("datapacks/user_levels/data/pvzce/levels");
        Path oldFile = pack.resolve("yard/adventure/1_1.json");
        Files.createDirectories(oldFile.getParent());
        Files.writeString(oldFile, "{\"id\":\"pvzce:yard/adventure/1_1\"}");

        Path world = root.resolve("saves/world");
        Path saveDir = world.resolve("levels/70767a6365__yard%2Fadventure%2F1_1");
        Files.createDirectories(saveDir);
        Files.writeString(saveDir.resolve("level.dat"), "save");
        Path status = world.resolve("level_status/70767a6365__yard%2Fadventure%2F1_1.dat");
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
        assertEquals("pvzce:redstone/minigame/1_1", result.id().toString());
        assertFalse(Files.exists(oldFile), "the file the level used to live in is removed");
        assertTrue(Files.isRegularFile(newFile));
        assertTrue(Files.isRegularFile(world.resolve("levels/70767a6365__redstone%2Fminigame%2F1_1/level.dat")),
                "the run moves with the level");
        assertFalse(Files.isDirectory(saveDir), "the old save directory is gone");
        assertTrue(Files.isRegularFile(world.resolve("level_status/70767a6365__redstone%2Fminigame%2F1_1.dat")));
        assertFalse(Files.exists(status), "the old completion marker is gone");
    }

    /** A level that was never saved has no file to move, and the save move still applies. */
    @Test
    void anUnsavedLevelOnlyHasItsSaveMoved(@TempDir Path root) throws Exception {
        Path world = root.resolve("saves/world");
        Path saveDir = world.resolve("levels/70767a6365__yard%2Fadventure%2F1_1");
        Files.createDirectories(saveDir);
        Files.writeString(saveDir.resolve("level.dat"), "save");

        LevelMove.Result result = LevelMove.move(YARD_ADVENTURE,
                Identifier.withDefaultNamespace("redstone"), Identifier.withDefaultNamespace("minigame"),
                null, null, world);

        assertTrue(result.ok());
        assertTrue(Files.isRegularFile(world.resolve("levels/70767a6365__redstone%2Fminigame%2F1_1/level.dat")));
    }

    /** A save that is already at the target is left alone rather than overwritten. */
    @Test
    void anExistingTargetSaveIsNotClobbered(@TempDir Path root) throws Exception {
        Path world = root.resolve("saves/world");
        Path oldDir = world.resolve("levels/70767a6365__yard%2Fadventure%2F1_1");
        Path newDir = world.resolve("levels/70767a6365__redstone%2Fminigame%2F1_1");
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
