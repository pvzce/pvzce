package com.pvzce.server;

import com.pvzce.api.util.Identifier;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.common.nbt.NbtIo;
import com.pvzce.common.network.packet.LevelListS2C;
import com.pvzce.common.tag.TestContent;
import com.pvzce.common.util.LevelKey;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A world's files on disk: the profile, the run saves and the completion markers.
 *
 * <p>These operations used to be private methods of {@code PvzceServer}, reachable only through a
 * running server, so the only thing that could check a corrupt completion marker was an
 * integration test. They are file operations on a known layout, and this is the direct test of
 * them - including the one failure that has a user-visible consequence: a marker that exists but
 * cannot be read must be reported, not silently read as "never cleared", which re-locked every
 * level behind it.
 */
class WorldStoreTest {
    private static final Identifier LEVEL = Identifier.withDefaultNamespace("yard/test/1_1");
    private static final String WORLD = "world";

    @BeforeAll
    static void loadContent() throws Exception {
        TestContent.loadBuiltInContentAndTags();
    }

    @Test
    void aWorldWithoutAProfileStartsFromTheStarterOne(@TempDir Path gameDir) {
        WorldStore store = new WorldStore(gameDir);
        PlayerProfile profile = store.profileFor(WORLD);
        assertEquals(PlayerProfile.starter().coins(), profile.coins());
        assertFalse(profile.unlockedIds().isEmpty(), "a new player owns something to plant");
    }

    @Test
    void aSavedProfileComesBackFromDisk(@TempDir Path gameDir) throws Exception {
        WorldStore store = new WorldStore(gameDir);
        PlayerProfile profile = store.profileFor(WORLD);
        profile.grantCoins(1234);
        profile.setSeedSlots(10);
        profile.unlock(Identifier.withDefaultNamespace("sunflower"));
        store.saveProfile(WORLD, profile);

        // A fresh store, as a later session would build: nothing is cached in memory.
        PlayerProfile reloaded = new WorldStore(gameDir).profileFor(WORLD);
        assertEquals(1234, reloaded.coins());
        assertEquals(10, reloaded.seedSlots());
        assertTrue(reloaded.unlockedIds().contains("pvzce:sunflower"),
                "the unlock survives the round trip, got " + reloaded.unlockedIds());
    }

    @Test
    void theProfileBelongsToOneWorldAtATime(@TempDir Path gameDir) {
        WorldStore store = new WorldStore(gameDir);
        store.profileFor("alpha").grantCoins(50);
        store.saveProfile("alpha", store.profileFor("alpha"));
        store.profileFor("beta").grantCoins(7);
        store.saveProfile("beta", store.profileFor("beta"));

        assertEquals(50, new WorldStore(gameDir).profileFor("alpha").coins());
        assertEquals(7, new WorldStore(gameDir).profileFor("beta").coins());
    }

    /** The production path: a win writes the marker, and the list reads it back. */
    @Test
    void aWonLevelWritesACompletionMarker(@TempDir Path gameDir) throws Exception {
        WorldStore store = new WorldStore(gameDir);
        Path worldDir = store.worldDir(WORLD);
        store.writeCompletion(worldDir, LEVEL, Identifier.withDefaultNamespace("plant_team"), 900, 12);

        assertTrue(store.isCompleted(WORLD, LEVEL));
        assertTrue(WorldStore.isCompletedIn(worldDir, LEVEL));
        assertFalse(store.isCompleted(WORLD, Identifier.withDefaultNamespace("yard/test/1_2")),
                "a marker says something about one level, not about the world");
    }

    /**
     * The failure with a consequence: the file is there and unreadable. It must count as "not
     * cleared" for the caller, but it must not be silent - the level appears locked again, and
     * without a log line nothing points at the damaged file.
     */
    @Test
    void anUnreadableMarkerCountsAsNotCleared(@TempDir Path gameDir) throws Exception {
        WorldStore store = new WorldStore(gameDir);
        Path status = LevelKey.statusFile(store.worldDir(WORLD), LEVEL);
        Files.createDirectories(status.getParent());
        Files.write(status, new byte[]{1, 2, 3, 4, 5});

        assertFalse(store.isCompleted(WORLD, LEVEL));
    }

    /** A marker that is readable but not a completion (a half-written one) is not a clear either. */
    @Test
    void aMarkerThatSaysSomethingElseIsNotAClear(@TempDir Path gameDir) throws Exception {
        WorldStore store = new WorldStore(gameDir);
        Path status = LevelKey.statusFile(store.worldDir(WORLD), LEVEL);
        Files.createDirectories(status.getParent());
        CompoundTag tag = new CompoundTag();
        tag.putString("GameState", LevelListS2C.LevelInfo.IN_PROGRESS);
        NbtIo.writeCompressed(tag, status);

        assertFalse(store.isCompleted(WORLD, LEVEL));
    }

    @Test
    void aRunningSaveIsDetectedAndDeleted(@TempDir Path gameDir) throws Exception {
        Path saveDir = LevelKey.levelDir(new WorldStore(gameDir).worldDir(WORLD), LEVEL);
        assertFalse(WorldStore.hasRunningSave(saveDir), "nothing to resume yet");

        Files.createDirectories(saveDir.resolve("nested"));
        Files.writeString(saveDir.resolve("level.dat"), "save");
        Files.writeString(saveDir.resolve("nested/extra"), "x");
        assertTrue(WorldStore.hasRunningSave(saveDir));

        WorldStore.deleteRunningSave(saveDir);
        assertFalse(Files.exists(saveDir), "the whole directory goes, not just level.dat");
    }

    @Test
    void deletingASaveThatIsNotThereIsNotAnError(@TempDir Path gameDir) {
        WorldStore.deleteRunningSave(new WorldStore(gameDir).worldDir(WORLD).resolve("nothing"));
    }

}
