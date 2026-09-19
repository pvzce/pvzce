package com.pvzce.server;

import com.pvzce.api.util.Identifier;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.common.nbt.NbtIo;
import com.pvzce.common.network.packet.LevelListS2C;
import com.pvzce.common.util.LevelKey;
import com.pvzce.common.util.WorldPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/**
 * Everything a world keeps on disk, and the one place that knows how to read and write it.
 *
 * <p>The layout is {@code saves/<world>/}: {@code profile.dat} for the player record,
 * {@code levels/<key>/level.dat} for the run save of a level, and
 * {@code level_status/<key>.dat} for its completion marker. The paths themselves come from
 * {@link WorldPaths} and {@link LevelKey}; this class is the operations on them - and it was
 * scattered through {@code PvzceServer} between the tick loop and the packet handlers, which is
 * how a bug like "an unreadable completion marker silently re-locked a level" hides.
 *
 * <p>One cached profile, not one per world: a session plays in one world at a time, and the cache
 * is what makes "mutate in memory during a run, write once at the end" possible.
 */
public final class WorldStore {
    private static final Logger LOGGER = LoggerFactory.getLogger("PVZCE/Worlds");
    private static final String LEVEL_SAVE_FILE = "level.dat";

    private final Path gameDir;
    private PlayerProfile profile;
    private String profileWorld;

    public WorldStore(Path gameDir) {
        this.gameDir = gameDir;
    }

    /** A world directory: {@code saves/<sanitised name>}. */
    public Path worldDir(String worldName) {
        return WorldPaths.worldDir(gameDir, worldName);
    }

    /**
     * The profile of a world, loaded on first use.
     *
     * <p>A world without a record - every world that existed before profiles did - gets
     * {@link PlayerProfile#starter()}, the same thing a new world gets. Never returns null, so
     * callers do not have to decide what an absent profile means.
     */
    public PlayerProfile profileFor(String worldName) {
        String safeWorld = WorldPaths.sanitize(worldName);
        if (profile != null && safeWorld.equals(profileWorld)) {
            return profile;
        }
        Path file = profileFile(WorldPaths.worldDir(gameDir, safeWorld));
        PlayerProfile loaded = PlayerProfile.starter();
        if (Files.isRegularFile(file)) {
            try {
                loaded = PlayerProfile.load(NbtIo.readCompressed(file));
            } catch (Throwable t) {
                LOGGER.warn("Failed to read profile " + file + " - starting from the default profile.", t);
            }
        }
        profile = loaded;
        profileWorld = safeWorld;
        return loaded;
    }

    /** Writes a world's profile, creating the world directory when needed. */
    public void saveProfile(String worldName, PlayerProfile toSave) {
        String safeWorld = WorldPaths.sanitize(worldName);
        Path worldDir = WorldPaths.worldDir(gameDir, safeWorld);
        try {
            Files.createDirectories(worldDir);
            NbtIo.writeCompressed(toSave.save(), profileFile(worldDir));
        } catch (Throwable t) {
            LOGGER.error("Failed to write profile for " + safeWorld, t);
        }
        profile = toSave;
        profileWorld = safeWorld;
    }

    /** Flushes the cached profile if it belongs to this world; used on the way out. */
    public void flushProfile() {
        if (profile != null && profileWorld != null) {
            saveProfile(profileWorld, profile);
        }
    }

    /** The world whose profile is cached, or null before any profile was read. */
    public String cachedWorld() {
        return profileWorld;
    }

    private static Path profileFile(Path worldDir) {
        return worldDir.resolve("profile.dat");
    }

    // ------------------------------------------------------------------
    // Level saves and completion markers
    // ------------------------------------------------------------------

    /** True when this level has a run save waiting to be resumed. */
    public static boolean hasRunningSave(Path levelDir) {
        return Files.isRegularFile(levelDir.resolve(LEVEL_SAVE_FILE));
    }

    /**
     * Writes one level's running save.
     *
     * <p>Everything the level needs - teams, resources, unlocks, the card bar, the scene grid and
     * every entity snapshot - is written by the level itself into a single {@code level.dat}.
     */
    public void saveLevel(CompoundTag save, Path saveDir) throws IOException {
        Files.createDirectories(saveDir);
        NbtIo.writeCompressed(save, saveDir.resolve(LEVEL_SAVE_FILE));
    }

    /** Reads a run save, or null when there is none to read. */
    public CompoundTag readLevelSave(Path saveDir) throws IOException {
        return NbtIo.readCompressed(saveDir.resolve(LEVEL_SAVE_FILE));
    }

    /**
     * Writes the marker that says this level was cleared.
     *
     * <p>Takes the four facts it records rather than the level it read them from: what a
     * completion marker contains is a property of the file format, and a store that needs a live
     * {@code LevelServer} to write one cannot be tested without winning a level first.
     */
    public void writeCompletion(Path worldDir, Identifier id, Identifier winner, int tickCount,
                                int plantCount) throws IOException {
        Path statusFile = LevelKey.statusFile(worldDir, id);
        Files.createDirectories(statusFile.getParent());
        CompoundTag status = new CompoundTag();
        status.putString("LevelId", id.toString());
        status.putString("GameState", LevelListS2C.LevelInfo.COMPLETED);
        status.putString("Winner", winner.toString());
        status.putInt("Tick", tickCount);
        status.putInt("PlantCount", plantCount);
        NbtIo.writeCompressed(status, statusFile);
    }

    /**
     * True when this level has a completion marker in the world - "cleared at least once".
     *
     * <p><b>Completion is permanent.</b> The marker is a record of something that already
     * happened, so nothing that happens later may take it away: replaying a cleared level,
     * losing that replay, or leaving it half-finished all leave it cleared.
     *
     * <p>It is deliberately not the same question as "has a resumable save", which is a property
     * of the moment and lives beside it. Reading one off the other is what made an abandoned
     * replay of a cleared level both lock the levels behind it again and hide the fact that a run
     * was waiting to be resumed.
     */
    public boolean isCompleted(String worldName, Identifier id) {
        if (worldName == null || worldName.isBlank()) {
            return false;
        }
        return isCompletedIn(WorldPaths.worldDir(gameDir, worldName), id);
    }

    /** True when this world's completion marker for the level is on disk and says so. */
    public static boolean isCompletedIn(Path worldDir, Identifier id) {
        Path statusFile = LevelKey.statusFile(worldDir, id);
        if (!Files.isRegularFile(statusFile)) {
            return false;
        }
        try {
            CompoundTag status = NbtIo.readCompressed(statusFile);
            return LevelListS2C.LevelInfo.COMPLETED.equals(status.getString("GameState"));
        } catch (Throwable t) {
            // Not the same as "not cleared": the marker is there and unreadable, and the
            // consequence is that the level looks locked again, so say so.
            LOGGER.warn("The completion marker for {} is unreadable; treating the level as not cleared",
                    id, t);
            return false;
        }
    }

    /** Deletes a level's run save directory, or does nothing when there is none. */
    public static void deleteRunningSave(Path saveDir) {
        if (!Files.exists(saveDir)) {
            return;
        }
        try (var paths = Files.walk(saveDir)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to delete level save " + saveDir, e);
        }
    }
}
