package com.pvzce.server;

import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.common.nbt.NbtIo;
import com.pvzce.common.network.packet.CommandC2S;
import com.pvzce.common.network.packet.EntitySpawnS2C;
import com.pvzce.common.network.packet.GameStateS2C;
import com.pvzce.common.network.packet.LeaveLevelC2S;
import com.pvzce.common.network.packet.LevelInitS2C;
import com.pvzce.common.network.packet.LevelListS2C;
import com.pvzce.common.network.packet.LevelSavePromptS2C;
import com.pvzce.common.network.packet.PlacePlantC2S;
import com.pvzce.common.network.packet.ContinueLevelC2S;
import com.pvzce.common.network.packet.RequestLevelListC2S;
import com.pvzce.common.network.packet.RestartLevelC2S;
import com.pvzce.common.network.packet.WaveProgressS2C;
import com.pvzce.testutil.ServerHarness;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Per-level save isolation, resume prompt, continue/restart, and finish status. */
class SaveSystemTest {

    /** A fresh directory per test; JUnit deletes it, and prints it when a test fails. */
    @TempDir
    Path gameDir;
    @Test
    void eachLevelGetsItsOwnSaveDirectory() throws Exception {
        try (ServerHarness server = ServerHarness.create(gameDir)) {
            // A level that can afford a peashooter at tick zero: 1-1 deliberately
            // starts with 50 sun, and this test is about save isolation.
            server.requestLevel("pvzce:yard/adventure/demo_level", "isoworld", true);
            server.waitFor(p -> p instanceof LevelInitS2C init && init.levelId().equals("pvzce:yard/adventure/demo_level"), 5_000);
            server.send(new PlacePlantC2S(0, 0, 0));
            server.waitFor(p -> p instanceof EntitySpawnS2C spawn && "plant".equals(spawn.entityKind()), 5_000);
            server.send(new LeaveLevelC2S());

            Path level1Save = gameDir.resolve("saves/isoworld/levels/70767a6365__yard%2Fadventure%2Fdemo_level/level.dat");
            server.waitForFile(level1Save, 5_000);

            server.clear();
            server.requestLevel("pvzce:yard/adventure/combat_test", "isoworld", true);
            server.waitFor(p -> p instanceof LevelInitS2C init && init.levelId().equals("pvzce:yard/adventure/combat_test"), 5_000);
            server.send(new LeaveLevelC2S());

            Path combatSave = gameDir.resolve("saves/isoworld/levels/70767a6365__yard%2Fadventure%2Fcombat_test/level.dat");
            server.waitForFile(combatSave, 5_000);

            assertTrue(Files.isRegularFile(level1Save), "the demo level save must not be overwritten by combat_test");
            assertTrue(Files.isRegularFile(combatSave));
            assertFalse(Files.exists(gameDir.resolve("saves/isoworld/level.dat")),
                    "old world-level save path must not be used");
            assertEquals("pvzce:yard/adventure/demo_level", NbtIo.readCompressed(level1Save).getString("LevelId"));
            assertEquals("pvzce:yard/adventure/combat_test", NbtIo.readCompressed(combatSave).getString("LevelId"));
        }
    }

    @Test
    void existingSavePromptsAndCanContinueOrRestart() throws Exception {
        try (ServerHarness server = ServerHarness.create(gameDir)) {
            server.requestLevel("pvzce:yard/adventure/demo_level", "promptworld", true);
            server.waitFor(p -> p instanceof LevelInitS2C init && init.levelId().equals("pvzce:yard/adventure/demo_level"), 5_000);
            server.send(new PlacePlantC2S(0, 0, 0));
            server.waitFor(p -> p instanceof EntitySpawnS2C spawn && "plant".equals(spawn.entityKind()), 5_000);
            server.send(new LeaveLevelC2S());

            Path saveFile = gameDir.resolve("saves/promptworld/levels/70767a6365__yard%2Fadventure%2Fdemo_level/level.dat");
            server.waitForFile(saveFile, 5_000);

            server.clear();
            server.requestLevel("pvzce:yard/adventure/demo_level", "promptworld", false);
            server.waitFor(p -> p instanceof LevelInitS2C init && init.levelId().equals("pvzce:yard/adventure/demo_level"), 5_000);
            LevelSavePromptS2C prompt = server.awaitPacket(LevelSavePromptS2C.class, 5_000);
            assertEquals("pvzce:yard/adventure/demo_level", prompt.levelId());
            assertEquals(1, prompt.plantCount());

            server.clear();
            long pausedTick = server.server().level().tickCount();
            server.send(new ContinueLevelC2S("pvzce:yard/adventure/demo_level", "promptworld"));
            Thread.sleep(200);
            assertTrue(server.server().level().tickCount() > pausedTick, "continue should resume the loaded level");
            assertFalse(server.packets().stream().anyMatch(LevelInitS2C.class::isInstance),
                    "continue must not reload/recreate the already loaded level");

            server.send(new LeaveLevelC2S());
            server.waitForFile(saveFile, 5_000);

            server.clear();
            server.requestLevel("pvzce:yard/adventure/demo_level", "promptworld", false);
            server.awaitPacket(LevelSavePromptS2C.class, 5_000);

            server.clear();
            server.send(new RestartLevelC2S("pvzce:yard/adventure/demo_level", "promptworld", List.of()));
            server.waitFor(p -> p instanceof LevelInitS2C init && init.levelId().equals("pvzce:yard/adventure/demo_level"), 5_000);
            Thread.sleep(250);
            assertFalse(server.packets().stream()
                            .anyMatch(p -> p instanceof EntitySpawnS2C spawn && "plant".equals(spawn.entityKind())),
                    "restart must start fresh and not restore plants");
        }
    }

    @Test
    void continueRestoresTickAndWaveProgress() throws Exception {
        try (ServerHarness server = ServerHarness.create(gameDir)) {
            server.requestLevel("pvzce:yard/adventure/combat_test", "waveworld", true);
            server.waitFor(p -> p instanceof LevelInitS2C init && init.levelId().equals("pvzce:yard/adventure/combat_test"), 5_000);
            server.waitFor(p -> p instanceof WaveProgressS2C wave && wave.currentWave() >= 1, 5_000);
            server.send(new CommandC2S("/save"));

            Path saveFile = gameDir.resolve("saves/waveworld/levels/70767a6365__yard%2Fadventure%2Fcombat_test/level.dat");
            server.waitForFile(saveFile, 5_000);
            server.send(new LeaveLevelC2S());

            server.clear();
            server.requestLevel("pvzce:yard/adventure/combat_test", "waveworld", false);
            server.waitFor(p -> p instanceof LevelInitS2C init && init.levelId().equals("pvzce:yard/adventure/combat_test"), 5_000);
            WaveProgressS2C progress = server.awaitPacket(WaveProgressS2C.class, 5_000);
            server.awaitPacket(LevelSavePromptS2C.class, 5_000);
            assertTrue(progress.currentWave() >= 1, "saved world should already carry spawned wave progress");
            assertTrue(server.server().level().tickCount() >= 60, "saved world should restore tick count");
        }
    }

    @Test
    void continueRestoresZombiesOnField() throws Exception {
        try (ServerHarness server = ServerHarness.create(gameDir)) {
            // Row 2 needs a five-lane board, so this test uses the demo level: 1-1
            // has a single lane and cannot hold a zombie that is not in row 0.
            server.requestLevel("pvzce:yard/adventure/demo_level", "zombieworld", true);
            server.waitFor(p -> p instanceof LevelInitS2C init && init.levelId().equals("pvzce:yard/adventure/demo_level"), 5_000);
            // No sky sun: the level's random is unseeded, so a sun landing next to the
            // zombie would turn "one snapshot list" into a count of two.
            server.send(new CommandC2S("/gamerule pvzce:sun_spawn_interval_min 0"));
            server.send(new CommandC2S("/gamerule pvzce:sun_spawn_interval_max 0"));
            server.send(new CommandC2S("/spawn zombie pvzce:buckethead_zombie 5 2"));
            server.waitFor(p -> p instanceof EntitySpawnS2C spawn
                    && "zombie".equals(spawn.entityKind())
                    && Math.floor(spawn.cellY()) == 2F, 5_000);

            // Freeze before snapshotting. The waits above are wall-clock bounded, and the
            // level's first wave lands at 600 ticks (10s): on a loaded suite the waits can
            // outlast that, and a wave zombie would make the counts below read 2. Freezing
            // leaves the field exactly what this test spawned.
            server.send(new CommandC2S("/tick freeze"));
            server.send(new CommandC2S("/save"));
            Path saveFile = gameDir.resolve("saves/zombieworld/levels/70767a6365__yard%2Fadventure%2Fdemo_level/level.dat");
            server.waitForFile(saveFile, 5_000);
            CompoundTag savedState = NbtIo.readCompressed(saveFile);
            assertEquals(1, savedState.getList("Entities").size(),
                    "every field entity must be written to level.dat in one snapshot list");
            assertEquals("zombie", savedState.getList("Entities").getCompound(0).getString("Kind"),
                    "the snapshot's entry is the zombie that was on the field");

            server.send(new LeaveLevelC2S());
            server.clear();
            server.requestLevel("pvzce:yard/adventure/demo_level", "zombieworld", false);
            server.waitFor(p -> p instanceof LevelInitS2C init && init.levelId().equals("pvzce:yard/adventure/demo_level"), 5_000);
            server.awaitPacket(LevelSavePromptS2C.class, 5_000);

            // Read from the spawn packet rather than from the level: what a client is told is
            // the contract, and it already carries the row and the armour this asserts on.
            EntitySpawnS2C zombie = server.packets().stream()
                    .filter(EntitySpawnS2C.class::isInstance)
                    .map(EntitySpawnS2C.class::cast)
                    .filter(spawn -> "zombie".equals(spawn.entityKind()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "restored zombies must be streamed to the client behind the save prompt"));
            assertEquals(2F, (float) Math.floor(zombie.cellY()),
                    "the saved field zombie must come back on the row it was saved on");
            assertTrue(zombie.armor() > 0, "bucket armor must survive the save/restore round-trip");
        }
    }

    @Test
    void winningWritesCompletionStatusAndDeletesRunningSave() throws Exception {
        writeCompletionLevel(gameDir);
        try (ServerHarness server = ServerHarness.create(gameDir)) {
            server.requestLevel("pvzce:test_complete", "statusworld", true);
            server.waitFor(p -> p instanceof LevelInitS2C init && init.levelId().equals("pvzce:test_complete"), 5_000);
            server.send(new CommandC2S("/save"));

            Path saveDir = gameDir.resolve("saves/statusworld/levels/70767a6365__test_complete");
            server.waitForFile(saveDir.resolve("level.dat"), 5_000);

            // Ending the level for real takes ~295 ticks (the zombie walks in, then its
            // countdown). Sprinting runs those ticks back to back instead of making the
            // suite wait ~5s of wall clock for them.
            server.send(new CommandC2S("/tick sprint 400"));

            server.waitFor(p -> p instanceof GameStateS2C state && !GameStateS2C.RUNNING.equals(state.state()), 12_000);
            Path statusFile = gameDir.resolve("saves/statusworld/level_status/70767a6365__test_complete.dat");
            server.waitForFile(statusFile, 5_000);
            server.waitForDeleted(saveDir, 5_000);

            CompoundTag status = NbtIo.readCompressed(statusFile);
            assertEquals("completed", status.getString("GameState"));
            assertEquals("pvzce:zombie_team", status.getString("Winner"));

            server.clear();
            server.send(new RequestLevelListC2S("statusworld"));
            LevelListS2C list = server.awaitPacket(LevelListS2C.class, 5_000);
            String statusValue = list.levels().stream()
                    .filter(level -> level.id().equals("pvzce:test_complete"))
                    .findFirst().orElseThrow().status();
            assertEquals("completed", statusValue);
        }
    }

    @Test
    void losingDeletesRunningSaveWithoutCompletionStatus() throws Exception {
        try (ServerHarness server = ServerHarness.create(gameDir)) {
            server.requestLevel("pvzce:yard/adventure/1_1", "lossworld", true);
            server.waitFor(p -> p instanceof LevelInitS2C init && init.levelId().equals("pvzce:yard/adventure/1_1"), 5_000);
            server.send(new CommandC2S("/save"));

            Path saveDir = gameDir.resolve("saves/lossworld/levels/70767a6365__yard%2Fadventure%2F1_1");
            server.waitForFile(saveDir.resolve("level.dat"), 5_000);

            // A balloon zombie, not a walker: 1-1 has a lawn mower, and a mower eats the
            // first ground zombie to reach the house - the loss would never happen. A flier
            // is the case the mower deliberately does not touch.
            server.send(new CommandC2S("/spawn zombie pvzce:balloon_zombie 0 0"));
            // See the win above: ~295 ticks of walking and countdown, run back to back.
            server.send(new CommandC2S("/tick sprint 400"));
            server.waitFor(p -> p instanceof GameStateS2C state && !GameStateS2C.RUNNING.equals(state.state()), 12_000);
            server.waitForDeleted(saveDir, 5_000);

            assertFalse(Files.exists(gameDir.resolve("saves/lossworld/level_status/70767a6365__yard%2Fadventure%2F1_1.dat")),
                    "loss must not write a completion status");
        }
    }

    private static void writeCompletionLevel(Path gameDir) throws IOException {        Path pack = gameDir.resolve("datapacks/save_test");
        Path levelFile = pack.resolve("data/pvzce/levels/test_complete.json");
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
                  "mechanics": [ { "type": "pvzce:mower", "rows": [] } ],
                  "initial_entities": [
                    { "kind": "zombie", "id": "pvzce:basic_zombie", "x": 0, "y": 0 }
                  ]
                }
                """);
    }
}
