package com.pvzce.server;

import com.pvzce.api.util.Identifier;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.network.Connection;
import com.pvzce.common.network.PvzcePacket;
import com.pvzce.common.network.PvzcePackets;
import com.pvzce.common.network.packet.CommandC2S;
import com.pvzce.common.network.packet.EntitySpawnS2C;
import com.pvzce.common.network.packet.LevelInitS2C;
import com.pvzce.common.network.packet.PauseGameC2S;
import com.pvzce.common.network.packet.PlacePlantC2S;
import com.pvzce.common.network.packet.SlotInfo;
import com.pvzce.common.network.packet.PlayLevelC2S;
import com.pvzce.common.resource.PvzceDataLoader;
import com.pvzce.common.resource.PvzceResourceManager;
import com.pvzce.server.level.LevelServer;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end restart flows driven exactly the way the client drives them.
 *
 * <p>The client sends two different shapes of "start this level": {@code ContinueLevelC2S}
 * (the level list's 继续游戏) and {@code PlayLevelC2S} (the seed chooser's 开始游戏,
 * carrying the chosen card bar). This pins what each one does when the level is already
 * running, which is what the reported restart bug is about.
 */
class LevelRestartFlowTest {
    /**
     * A purpose-built level rather than a shipped one.
     *
     * <p>These tests plant a peashooter at tick zero (the shipped first level
     * deliberately starts with 50 sun, which cannot pay for one), want free card
     * slots to hand a pick through, and need a five-row board. That is the shape of
     * a test fixture, not of a level a player should ever see.
     */
    private static final String LEVEL = "pvzce:test_restart";
    private static final String WORLD = "restartflow";
    /** {@code LevelKey.of(pvzce:test_restart)}: hex namespace, then the escaped path. */
    private static final String SAVE_KEY = "70767a6365__test_restart";

    /** Builds the card bar payload the way {@code PvzceClient} does. */
    private static List<String> seedsFromCards(List<SlotInfo> slots) {
        return slots.stream().map(SlotInfo::defId).toList();
    }

    private static final class Harness implements AutoCloseable {
        final PvzceServer server;
        final Connection.Pair pair;
        final List<PvzcePacket> packets = new ArrayList<>();

        Harness(Path gameDir) throws Exception {
            BuiltInRegistries.bootstrap();
            PvzcePackets.register();
            this.pair = Connection.createMemoryPair();
            this.server = new PvzceServer(pair.server(), gameDir, Thread.currentThread().getContextClassLoader());
            pair.client().setListener(packets::add);
            server.start();
            // Wait for the first data load.
            // A sandbox world: these tests are about the restart protocol, so every
            // card must be a legal pick. Sent before the level starts because the
            // server handles client packets in order.
            send(new com.pvzce.common.network.packet.CreateWorldC2S(WORLD, true));
            send(new com.pvzce.common.network.packet.RequestLevelListC2S("__probe__"));
            waitFor(() -> packets.stream().anyMatch(com.pvzce.common.network.packet.LevelListS2C.class::isInstance),
                    5_000);
            packets.clear();
        }

        void send(PvzcePacket packet) {
            pair.client().send(packet);
        }

        void waitFor(BooleanSupplier condition, long timeoutMs) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
            while (System.nanoTime() < deadline) {
                pair.client().tick();
                if (condition.getAsBoolean()) {
                    return;
                }
                Thread.sleep(5);
            }
            throw new AssertionError("Timed out waiting for condition"
                    + " | clientConnected=" + pair.client().isConnected()
                    + " | serverConnected=" + pair.server().isConnected()
                    + " | clientDisconnect=" + pair.client().disconnectReason()
                    + " | serverDisconnect=" + pair.server().disconnectReason()
                    + " | level=" + (server.level() == null ? "null" : server.level().gameState())
                    + " | packets=" + packets.stream().map(p -> p.getClass().getSimpleName()).toList());
        }

        LevelInitS2C awaitLevelInit(long timeoutMs) throws Exception {
            waitFor(() -> packets.stream().anyMatch(LevelInitS2C.class::isInstance), timeoutMs);
            return packets.stream().filter(LevelInitS2C.class::isInstance)
                    .map(LevelInitS2C.class::cast).reduce((a, b) -> b).orElseThrow();
        }

        @Override
        public void close() throws Exception {
            server.stop();
            server.thread().join(3_000);
        }
    }

    /** Writes the fixture level into a data pack the server will scan at startup. */
    private static void writeTestLevel(Path dir) throws Exception {
        Path pack = dir.resolve("datapacks/restart_test");
        Path levelFile = pack.resolve("data/pvzce/levels/test_restart.json");
        Files.createDirectories(levelFile.getParent());
        Files.writeString(pack.resolve("pack.mcmeta"),
                "{\"pack\":{\"pack_format\":1,\"description\":\"restart flow test\"}}");
        Files.writeString(levelFile, """
                {
                  "id": "pvzce:test_restart",
                  "name": "Restart Flow Test",
                  "width": 9,
                  "height": 5,
                  "scene": { "pvzce:grass": [
                    "0,0","1,0","2,0","3,0","4,0","5,0","6,0","7,0","8,0",
                    "0,1","1,1","2,1","3,1","4,1","5,1","6,1","7,1","8,1",
                    "0,2","1,2","2,2","3,2","4,2","5,2","6,2","7,2","8,2",
                    "0,3","1,3","2,3","3,3","4,3","5,3","6,3","7,3","8,3",
                    "0,4","1,4","2,4","3,4","4,4","5,4","6,4","7,4","8,4" ] },
                  "rules": { "pvzce:day_length": 0, "pvzce:night_length": -1 },
                  "waves": [],
                  "slots": [ "pvzce:pea_shooter", "pvzce:sun" ],
                  "max_seed_slots": 4,
                  "initial_sun": 150
                }
                """);
    }

    private static Path gameDir() throws Exception {
        Path dir = Files.createTempDirectory("pvzce-restart-flow");
        writeTestLevel(dir);
        // Capability types must be registered before the shipped plants can be decoded.
        BuiltInRegistries.bootstrap();
        PvzcePackets.register();
        // Load the shipped pack once so levels/plants exist.
        PvzceResourceManager resources = new PvzceResourceManager(
                Thread.currentThread().getContextClassLoader());
        resources.init(dir);
        PvzceDataLoader.LoadResult result = new PvzceDataLoader().load(resources, BuiltInRegistries.ACCESS);
        assertTrue(result.errors().isEmpty(), result.errors().toString());
        resources.close();
        return dir;
    }

    /**
     * The pause menu's 重新开始 sends {@code PlayLevelC2S(restart=true)}. The running
     * level must be replaced by a genuinely fresh one, with the chosen cards applied.
     */
    @Test
    void pauseMenuRestartReplacesTheRunningLevel() throws Exception {
        Path dir = gameDir();
        try (Harness harness = new Harness(dir)) {
            harness.send(new PlayLevelC2S(LEVEL, WORLD, true,
                    List.of("pvzce:pea_shooter", "pvzce:sun")));
            LevelInitS2C first = harness.awaitLevelInit(5_000);
            LevelServer previous = harness.server.level();
            assertNotNull(previous);

            // Plant something so a "restart" that is really a resync is visible.
            harness.send(new PlacePlantC2S(0, 0, 0));
            harness.waitFor(() -> previous.plantCount() == 1, 5_000);
            // And put a run on disk, so "the old progress is gone" below is an assertion
            // about a file that really existed rather than about nothing at all.
            Path saveFile = dir.resolve("saves/" + WORLD + "/levels/" + SAVE_KEY + "/level.dat");
            harness.send(new CommandC2S("/save"));
            harness.waitFor(() -> Files.isRegularFile(saveFile), 5_000);
            harness.packets.clear();

            // Pause the way the pause dialog does, then restart from it.
            harness.send(new PauseGameC2S(true));
            harness.send(new PlayLevelC2S(LEVEL, WORLD, true, seedsFromCards(first.slots())));

            LevelInitS2C second = harness.awaitLevelInit(5_000);
            LevelServer restarted = harness.server.level();

            assertTrue(restarted != previous, "restart must install a new level instance");
            assertEquals("closed", previous.gameState(), "the previous level must be shut down");
            assertEquals(0, restarted.plantCount(), "a restarted level must start with an empty field");
            // The clock is deliberately not asserted: the server ticks the fresh level from
            // its own thread, so by the time the test reads it a tick or two have already
            // run. "Fresh" is what the instance identity, the empty field and the save
            // below are for.
            assertTrue(previous.entities().isEmpty(), "the shut down level must not keep entities");
            assertEquals(second.slots().size(), restarted.slotInfos().size(),
                    "the restarted level must expose the card bar it was created with");

            // "Really restarted" also means the old progress is gone: the save written
            // above must not survive for a later entry to resume from.
            harness.waitFor(() -> !Files.exists(saveFile), 5_000);
            assertFalse(Files.exists(saveFile), "a restart must not leave the old run resumable");
        }
    }

    /**
     * The level list's 继续游戏 sends {@code ContinueLevelC2S} without a card bar. When
     * the level is already running, this used to answer with a plain resync - the
     * player saw the level "start" while the old run kept going.
     */
    @Test
    void requestLevelOnTheRunningLevelMustNotSilentlyResync() throws Exception {
        Path dir = gameDir();
        try (Harness harness = new Harness(dir)) {
            harness.send(new PlayLevelC2S(LEVEL, WORLD, true, List.of("pvzce:pea_shooter", "pvzce:sun")));
            harness.awaitLevelInit(5_000);
            LevelServer running = harness.server.level();
            harness.send(new PlacePlantC2S(0, 0, 0));
            harness.waitFor(() -> running.plantCount() == 1, 5_000);
            harness.packets.clear();

            // The player asked for this level again *without* asking for a fresh run.
            harness.send(new com.pvzce.common.network.packet.ContinueLevelC2S(LEVEL, WORLD));
            harness.awaitLevelInit(5_000);

            // Resyncing the running level is acceptable for a "continue" request, but it
            // must not look like a fresh start: the field has to still be there.
            assertTrue(harness.server.level() == running,
                    "a continue request must keep the running level, not build a second one");
            assertEquals(1, running.plantCount(),
                    "a continue request must not wipe the field it is resuming");
        }
    }

    /**
     * Starting the same level with an explicit card bar and {@code restart=false} while
     * it is running: the chosen cards must not be silently discarded.
     */
    @Test
    void startingWithNewCardsWhileTheLevelRunsMustApplyThem() throws Exception {
        Path dir = gameDir();
        try (Harness harness = new Harness(dir)) {
            harness.send(new PlayLevelC2S(LEVEL, WORLD, true, List.of("pvzce:pea_shooter", "pvzce:sun")));
            harness.awaitLevelInit(5_000);
            LevelServer running = harness.server.level();
            harness.send(new PlacePlantC2S(0, 0, 0));
            harness.waitFor(() -> running.plantCount() == 1, 5_000);
            harness.packets.clear();

            // The seed chooser only ever sends the cards the player picked.
            harness.send(new PlayLevelC2S(LEVEL, WORLD, false, List.of("pvzce:sun", "pvzce:wall_nut")));
            LevelInitS2C init = harness.awaitLevelInit(5_000);

            List<String> cards = init.slots().stream().map(SlotInfo::defId).toList();
            assertTrue(cards.contains("pvzce:wall_nut"),
                    "the card the player picked must reach the server; got " + cards);
        }
    }
}
