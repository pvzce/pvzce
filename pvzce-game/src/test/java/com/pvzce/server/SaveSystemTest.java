package com.pvzce.server;

import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.common.nbt.NbtIo;
import com.pvzce.common.network.Connection;
import com.pvzce.common.network.PvzcePacket;
import com.pvzce.common.network.PvzcePackets;
import com.pvzce.common.network.packet.CommandC2S;
import com.pvzce.common.network.packet.EntitySpawnS2C;
import com.pvzce.common.network.packet.GameStateS2C;
import com.pvzce.common.network.packet.LeaveLevelC2S;
import com.pvzce.common.network.packet.LevelInitS2C;
import com.pvzce.common.network.packet.LevelListS2C;
import com.pvzce.common.network.packet.LevelSavePromptS2C;
import com.pvzce.common.network.packet.PlacePlantC2S;
import com.pvzce.common.network.packet.RequestLevelC2S;
import com.pvzce.common.network.packet.RequestLevelListC2S;
import com.pvzce.common.network.packet.ResumeLevelC2S;
import com.pvzce.common.network.packet.WaveProgressS2C;
import com.pvzce.server.entity.ZombieEntity;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Per-level save isolation, resume prompt, continue/restart, and finish status. */
class SaveSystemTest {
    @Test
    void eachLevelGetsItsOwnSaveDirectory() throws Exception {
        Path gameDir = Files.createTempDirectory("pvzce-save-isolation");
        try (TestServer server = new TestServer(gameDir)) {
            server.requestLevel("pvzce:level_1", "isoworld", true);
            server.waitFor(p -> p instanceof LevelInitS2C init && init.levelId().equals("pvzce:level_1"), 5_000);
            server.send(new PlacePlantC2S(0, 0, 0));
            server.waitFor(p -> p instanceof EntitySpawnS2C spawn && "plant".equals(spawn.entityKind()), 5_000);
            server.send(new LeaveLevelC2S());

            Path level1Save = gameDir.resolve("saves/isoworld/levels/pvzce__level_1/level.dat");
            server.waitForFile(level1Save, 5_000);

            server.packets.clear();
            server.requestLevel("pvzce:combat_test", "isoworld", true);
            server.waitFor(p -> p instanceof LevelInitS2C init && init.levelId().equals("pvzce:combat_test"), 5_000);
            server.send(new LeaveLevelC2S());

            Path combatSave = gameDir.resolve("saves/isoworld/levels/pvzce__combat_test/level.dat");
            server.waitForFile(combatSave, 5_000);

            assertTrue(Files.isRegularFile(level1Save), "level_1 save must not be overwritten by combat_test");
            assertTrue(Files.isRegularFile(combatSave));
            assertFalse(Files.exists(gameDir.resolve("saves/isoworld/level.dat")),
                    "old world-level save path must not be used");
            assertEquals("pvzce:level_1", NbtIo.readCompressed(level1Save).getString("LevelId"));
            assertEquals("pvzce:combat_test", NbtIo.readCompressed(combatSave).getString("LevelId"));
        }
    }

    @Test
    void existingSavePromptsAndCanContinueOrRestart() throws Exception {
        Path gameDir = Files.createTempDirectory("pvzce-save-prompt");
        try (TestServer server = new TestServer(gameDir)) {
            server.requestLevel("pvzce:level_1", "promptworld", true);
            server.waitFor(p -> p instanceof LevelInitS2C init && init.levelId().equals("pvzce:level_1"), 5_000);
            server.send(new PlacePlantC2S(0, 0, 0));
            server.waitFor(p -> p instanceof EntitySpawnS2C spawn && "plant".equals(spawn.entityKind()), 5_000);
            server.send(new LeaveLevelC2S());

            Path saveFile = gameDir.resolve("saves/promptworld/levels/pvzce__level_1/level.dat");
            server.waitForFile(saveFile, 5_000);

            server.packets.clear();
            server.requestLevel("pvzce:level_1", "promptworld", false);
            server.waitFor(p -> p instanceof LevelInitS2C init && init.levelId().equals("pvzce:level_1"), 5_000);
            LevelSavePromptS2C prompt = server.waitForPacket(LevelSavePromptS2C.class, 5_000);
            assertEquals("pvzce:level_1", prompt.levelId());
            assertEquals(1, prompt.plantCount());

            server.packets.clear();
            long pausedTick = server.server().level().tickCount();
            server.send(new ResumeLevelC2S("pvzce:level_1", "promptworld", false));
            Thread.sleep(200);
            assertTrue(server.server().level().tickCount() > pausedTick, "continue should resume the loaded level");
            assertFalse(server.packets.stream().anyMatch(LevelInitS2C.class::isInstance),
                    "continue must not reload/recreate the already loaded level");

            server.send(new LeaveLevelC2S());
            server.waitForFile(saveFile, 5_000);

            server.packets.clear();
            server.requestLevel("pvzce:level_1", "promptworld", false);
            server.waitForPacket(LevelSavePromptS2C.class, 5_000);

            server.packets.clear();
            server.send(new ResumeLevelC2S("pvzce:level_1", "promptworld", true));
            server.waitFor(p -> p instanceof LevelInitS2C init && init.levelId().equals("pvzce:level_1"), 5_000);
            Thread.sleep(250);
            assertFalse(server.packets.stream()
                            .anyMatch(p -> p instanceof EntitySpawnS2C spawn && "plant".equals(spawn.entityKind())),
                    "restart must start fresh and not restore plants");
        }
    }

    @Test
    void continueRestoresTickAndWaveProgress() throws Exception {
        Path gameDir = Files.createTempDirectory("pvzce-save-progress");
        try (TestServer server = new TestServer(gameDir)) {
            server.requestLevel("pvzce:combat_test", "waveworld", true);
            server.waitFor(p -> p instanceof LevelInitS2C init && init.levelId().equals("pvzce:combat_test"), 5_000);
            server.waitFor(p -> p instanceof WaveProgressS2C wave && wave.currentWave() >= 1, 5_000);
            server.send(new CommandC2S("/save"));

            Path saveFile = gameDir.resolve("saves/waveworld/levels/pvzce__combat_test/level.dat");
            server.waitForFile(saveFile, 5_000);
            server.send(new LeaveLevelC2S());

            server.packets.clear();
            server.requestLevel("pvzce:combat_test", "waveworld", false);
            server.waitFor(p -> p instanceof LevelInitS2C init && init.levelId().equals("pvzce:combat_test"), 5_000);
            WaveProgressS2C progress = server.waitForPacket(WaveProgressS2C.class, 5_000);
            server.waitForPacket(LevelSavePromptS2C.class, 5_000);
            assertTrue(progress.currentWave() >= 1, "saved world should already carry spawned wave progress");
            assertTrue(server.server().level().tickCount() >= 60, "saved world should restore tick count");

            server.packets.clear();
            long pausedTick = server.server().level().tickCount();
            server.send(new ResumeLevelC2S("pvzce:combat_test", "waveworld", false));
            Thread.sleep(200);
            assertTrue(server.server().level().tickCount() > pausedTick, "continue should resume the restored level");
            assertFalse(server.packets.stream().anyMatch(LevelInitS2C.class::isInstance),
                    "continue must not recreate the level");
        }
    }

    @Test
    void continueRestoresZombiesOnField() throws Exception {
        Path gameDir = Files.createTempDirectory("pvzce-save-zombies");
        try (TestServer server = new TestServer(gameDir)) {
            server.requestLevel("pvzce:level_1", "zombieworld", true);
            server.waitFor(p -> p instanceof LevelInitS2C init && init.levelId().equals("pvzce:level_1"), 5_000);
            server.send(new CommandC2S("/spawn zombie pvzce:buckethead_zombie 5 2"));
            server.waitFor(p -> p instanceof EntitySpawnS2C spawn
                    && "zombie".equals(spawn.entityKind())
                    && Math.floor(spawn.cellY()) == 2F, 5_000);

            server.send(new CommandC2S("/save"));
            Path saveFile = gameDir.resolve("saves/zombieworld/levels/pvzce__level_1/level.dat");
            server.waitForFile(saveFile, 5_000);
            CompoundTag savedState = NbtIo.readCompressed(saveFile);
            assertEquals(1, savedState.getList("Entities").size(),
                    "every field entity must be written to level.dat in one snapshot list");

            server.send(new LeaveLevelC2S());
            server.packets.clear();
            server.requestLevel("pvzce:level_1", "zombieworld", false);
            server.waitFor(p -> p instanceof LevelInitS2C init && init.levelId().equals("pvzce:level_1"), 5_000);
            server.waitForPacket(LevelSavePromptS2C.class, 5_000);

            assertTrue(server.packets.stream().anyMatch(p -> p instanceof EntitySpawnS2C spawn
                            && "zombie".equals(spawn.entityKind()) && Math.floor(spawn.cellY()) == 2F),
                    "restored zombies must be streamed to the client behind the save prompt");
            assertEquals(1, server.server().level().aliveZombieCount(),
                    "the saved field zombie must exist in the restored level");
            ZombieEntity restored = server.server().level().entities().stream()
                    .filter(ZombieEntity.class::isInstance)
                    .map(ZombieEntity.class::cast)
                    .findFirst()
                    .orElseThrow();
            assertTrue(restored.armorHealth() > 0, "bucket armor must survive the save/restore round-trip");
        }
    }

    @Test
    void winningWritesCompletionStatusAndDeletesRunningSave() throws Exception {
        Path gameDir = Files.createTempDirectory("pvzce-save-win");
        writeCompletionLevel(gameDir);
        try (TestServer server = new TestServer(gameDir)) {
            server.requestLevel("pvzce:test_complete", "statusworld", true);
            server.waitFor(p -> p instanceof LevelInitS2C init && init.levelId().equals("pvzce:test_complete"), 5_000);
            server.send(new CommandC2S("/save"));

            Path saveDir = gameDir.resolve("saves/statusworld/levels/pvzce__test_complete");
            server.waitForFile(saveDir.resolve("level.dat"), 5_000);

            server.waitFor(p -> p instanceof GameStateS2C state && !GameStateS2C.RUNNING.equals(state.state()), 12_000);
            Path statusFile = gameDir.resolve("saves/statusworld/level_status/pvzce__test_complete.dat");
            server.waitForFile(statusFile, 5_000);
            server.waitForDeleted(saveDir, 5_000);

            CompoundTag status = NbtIo.readCompressed(statusFile);
            assertEquals("completed", status.getString("GameState"));
            assertEquals("pvzce:zombie_team", status.getString("Winner"));

            server.packets.clear();
            server.send(new RequestLevelListC2S("statusworld"));
            LevelListS2C list = server.waitForPacket(LevelListS2C.class, 5_000);
            String statusValue = list.levels().stream()
                    .filter(level -> level.id().equals("pvzce:test_complete"))
                    .findFirst().orElseThrow().status();
            assertEquals("completed", statusValue);
        }
    }

    @Test
    void losingDeletesRunningSaveWithoutCompletionStatus() throws Exception {
        Path gameDir = Files.createTempDirectory("pvzce-save-loss");
        try (TestServer server = new TestServer(gameDir)) {
            server.requestLevel("pvzce:level_1", "lossworld", true);
            server.waitFor(p -> p instanceof LevelInitS2C init && init.levelId().equals("pvzce:level_1"), 5_000);
            server.send(new CommandC2S("/save"));

            Path saveDir = gameDir.resolve("saves/lossworld/levels/pvzce__level_1");
            server.waitForFile(saveDir.resolve("level.dat"), 5_000);

            server.send(new CommandC2S("/spawn zombie pvzce:basic_zombie 0 0"));
            server.waitFor(p -> p instanceof GameStateS2C state && !GameStateS2C.RUNNING.equals(state.state()), 12_000);
            server.waitForDeleted(saveDir, 5_000);

            assertFalse(Files.exists(gameDir.resolve("saves/lossworld/level_status/pvzce__level_1.dat")),
                    "loss must not write a completion status");
        }
    }

    private static void writeCompletionLevel(Path gameDir) throws IOException {
        Path pack = gameDir.resolve("datapacks/save_test");
        Path levelFile = pack.resolve("data/pvzce/pvzce/levels/test_complete.json");
        Files.createDirectories(levelFile.getParent());
        Files.writeString(pack.resolve("pack.mcmeta"),
                "{\"pack\":{\"pack_format\":1,\"description\":\"save system test\"}}");
        Files.writeString(levelFile, """
                {
                  "id": "pvzce:test_complete",
                  "name": "Save Complete Test",
                  "width": 3,
                  "height": 1,
                  "teams": [
                    { "id": "pvzce:plant_team", "name": "植物方", "win_condition": "survive_waves" },
                    { "id": "pvzce:zombie_team", "name": "僵尸方", "win_condition": "plant_side_lost" }
                  ],
                  "win_team": "pvzce:zombie_team",
                  "rules": { "pvzce:day_length": 0, "pvzce:night_length": -1 },
                  "waves": [],
                  "slots": [ "pvzce:pea_shooter", "pvzce:sun" ],
                  "initial_entities": [
                    { "kind": "zombie", "id": "pvzce:basic_zombie", "x": 0, "y": 0 }
                  ]
                }
                """);
    }

    private static final class TestServer implements AutoCloseable {
        private final PvzceServer server;
        private final Connection.Pair pair;
        private final List<PvzcePacket> packets = new ArrayList<>();

        private TestServer(Path gameDir) throws Exception {
            PvzcePackets.register();
            this.pair = Connection.createMemoryPair();
            this.server = new PvzceServer(pair.server(), gameDir, Thread.currentThread().getContextClassLoader());
            pair.client().setListener(packets::add);
            server.start();
            pair.client().send(new RequestLevelListC2S("__probe__"));
            waitFor(packet -> packet instanceof LevelListS2C, 5_000);
            packets.clear();
        }

        private void requestLevel(String levelId, String worldName, boolean restart) {
            pair.client().send(new RequestLevelC2S(levelId, worldName, restart));
        }

        private void send(PvzcePacket packet) {
            pair.client().send(packet);
        }

        private PvzceServer server() {
            return server;
        }

        private void waitFor(Predicate<PvzcePacket> predicate, long timeoutMs) throws Exception {
            long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
            while (System.nanoTime() < deadline) {
                pair.client().tick();
                if (packets.stream().anyMatch(predicate)) {
                    return;
                }
                Thread.sleep(10);
            }
            throw new AssertionError("Timed out waiting for packet condition");
        }

        private <T extends PvzcePacket> T waitForPacket(Class<T> type, long timeoutMs) throws Exception {
            waitFor(packet -> type.isInstance(packet), timeoutMs);
            return packets.stream().filter(type::isInstance).map(type::cast).findFirst().orElseThrow();
        }

        private void waitForFile(Path file, long timeoutMs) throws Exception {
            long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
            while (System.nanoTime() < deadline) {
                if (Files.isRegularFile(file)) {
                    return;
                }
                Thread.sleep(20);
            }
            throw new AssertionError("Timed out waiting for file " + file);
        }

        private void waitForDeleted(Path path, long timeoutMs) throws Exception {
            long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
            while (System.nanoTime() < deadline) {
                if (!Files.exists(path)) {
                    return;
                }
                Thread.sleep(20);
            }
            throw new AssertionError("Timed out waiting for deletion of " + path);
        }

        @Override
        public void close() throws Exception {
            server.stop();
            server.thread().join(3_000);
        }
    }
}
