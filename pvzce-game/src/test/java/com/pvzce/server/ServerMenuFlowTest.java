package com.pvzce.server;

import com.pvzce.common.network.Connection;
import com.pvzce.common.network.PvzcePacket;
import com.pvzce.common.network.PvzcePackets;
import com.pvzce.common.network.packet.LeaveLevelC2S;
import com.pvzce.common.network.packet.LevelInitS2C;
import com.pvzce.common.network.packet.LevelListS2C;
import com.pvzce.common.network.packet.LevelSavePromptS2C;
import com.pvzce.common.network.packet.PauseGameC2S;
import com.pvzce.common.network.packet.RequestLevelC2S;
import com.pvzce.common.network.packet.RequestLevelListC2S;
import com.pvzce.common.network.packet.ResumeLevelC2S;
import com.pvzce.common.network.packet.SlotInfo;
import com.pvzce.common.network.packet.StartLevelC2S;
import com.pvzce.server.level.LevelServer;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** M2: menu flow packets (level list -> enter level -> leave) over the memory connection. */
class ServerMenuFlowTest {
    @Test
    void requestLevelListThenEnterLevelCreatesWorldSave() throws Exception {
        PvzcePackets.register();
        Path gameDir = Files.createTempDirectory("pvzce-menu-flow");
        Connection.Pair pair = Connection.createMemoryPair();
        PvzceServer server = new PvzceServer(pair.server(), gameDir, Thread.currentThread().getContextClassLoader());
        server.start();

        List<PvzcePacket> clientPackets = new ArrayList<>();
        pair.client().setListener(clientPackets::add);

        // Wait until the server is listening, then ask for the level registry.
        waitForCondition(2_000, () -> {
            pair.client().send(new RequestLevelListC2S("testworld"));
            pair.client().tick();
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, () -> clientPackets.stream().anyMatch(p -> p instanceof LevelListS2C));

        LevelListS2C list = clientPackets.stream().filter(p -> p instanceof LevelListS2C)
                .map(p -> (LevelListS2C) p).findFirst().orElse(null);
        assertNotNull(list);
        assertTrue(list.levels().stream().anyMatch(l -> l.id().equals("pvzce:level_1")));

        pair.client().send(new RequestLevelC2S("pvzce:level_1", "testworld", false));
        waitForCondition(5_000, () -> pair.client().tick(),
                () -> clientPackets.stream().anyMatch(p -> p instanceof LevelInitS2C init && init.levelId().equals("pvzce:level_1")));
        waitForCondition(5_000, () -> pair.client().tick(),
                () -> clientPackets.stream().anyMatch(p -> p instanceof com.pvzce.common.network.packet.WaveProgressS2C));
        waitForCondition(5_000, () -> pair.client().tick(),
                () -> clientPackets.stream().anyMatch(p -> p instanceof com.pvzce.common.network.packet.TimeOfDayS2C));

        pair.client().send(new com.pvzce.common.network.packet.CommandC2S("/time set 600"));
        waitForCondition(5_000, () -> pair.client().tick(),
                () -> clientPackets.stream().anyMatch(p -> p instanceof com.pvzce.common.network.packet.ServerMessageS2C m
                        && m.message().equals("已将时间设置为 600")));
        pair.client().send(new com.pvzce.common.network.packet.CommandC2S("/tick query"));
        waitForCondition(5_000, () -> pair.client().tick(),
                () -> clientPackets.stream().anyMatch(p -> p instanceof com.pvzce.common.network.packet.ServerMessageS2C m
                        && m.message().startsWith("服务器状态:")));

        pair.client().send(new LeaveLevelC2S());
        waitForCondition(5_000, () -> pair.client().tick(),
                () -> Files.isRegularFile(gameDir.resolve(
                        "saves/testworld/levels/pvzce__level_1/level.dat")));
        assertTrue(Files.isRegularFile(gameDir.resolve("saves/testworld/levels/pvzce__level_1/level.dat")));
        // One save file per level holds teams, cards, scene and entities; the old
        // byte-identical data/level_state.dat and the never-read per-team/per-player
        // side files are gone.
        assertFalse(Files.exists(gameDir.resolve("saves/testworld/levels/pvzce__level_1/data")),
                "the duplicated side files under data/ must not be written");
        assertTrue(Files.isRegularFile(gameDir.resolve("saves/testworld/session.lock")));

        server.stop();
        server.thread().join(3_000);
    }

    @Test
    void brigadierCommandsExecute() throws Exception {
        PvzcePackets.register();
        Path gameDir = Files.createTempDirectory("pvzce-commands");
        Connection.Pair pair = Connection.createMemoryPair();
        PvzceServer server = new PvzceServer(pair.server(), gameDir, Thread.currentThread().getContextClassLoader());
        server.start();
        List<PvzcePacket> clientPackets = new ArrayList<>();
        pair.client().setListener(clientPackets::add);

        waitForCondition(2_000, () -> {
            pair.client().send(new com.pvzce.common.network.packet.CommandC2S("/tick"));
            pair.client().tick();
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, () -> clientPackets.stream().anyMatch(p -> p instanceof com.pvzce.common.network.packet.ServerMessageS2C m
                && m.message().startsWith("游戏 tick=")));

        pair.client().send(new com.pvzce.common.network.packet.CommandC2S("/tick freeze"));
        waitForCondition(3_000, () -> pair.client().tick(),
                () -> clientPackets.stream().anyMatch(p -> p instanceof com.pvzce.common.network.packet.ServerMessageS2C m
                        && m.message().equals("服务器已冻结。")));
        pair.client().send(new com.pvzce.common.network.packet.CommandC2S("/tick query"));
        waitForCondition(3_000, () -> pair.client().tick(),
                () -> clientPackets.stream().anyMatch(p -> p instanceof com.pvzce.common.network.packet.ServerMessageS2C m
                        && m.message().equals("服务器状态: 已冻结")));
        pair.client().send(new com.pvzce.common.network.packet.CommandC2S("/tick unfreeze"));
        waitForCondition(3_000, () -> pair.client().tick(),
                () -> clientPackets.stream().anyMatch(p -> p instanceof com.pvzce.common.network.packet.ServerMessageS2C m
                        && m.message().equals("服务器已恢复运行。")));

        pair.client().send(new com.pvzce.common.network.packet.CommandC2S("/editor open pvzce:demo_level"));
        waitForCondition(3_000, () -> pair.client().tick(),
                () -> clientPackets.stream().anyMatch(p -> p instanceof com.pvzce.common.network.packet.OpenEditorS2C e
                        && e.levelId().equals("pvzce:demo_level")));

        server.stop();
        server.thread().join(3_000);
    }

    @Test
    void brigadierSuggestionsCoverSubcommands() throws Exception {
        PvzcePackets.register();
        Path gameDir = Files.createTempDirectory("pvzce-suggestions");
        Connection.Pair pair = Connection.createMemoryPair();
        PvzceServer server = new PvzceServer(pair.server(), gameDir, Thread.currentThread().getContextClassLoader());
        server.start();
        List<PvzcePacket> clientPackets = new ArrayList<>();
        pair.client().setListener(clientPackets::add);

        // Wait until the server listener is ready before sending suggestions.
        waitForCondition(2_000, () -> {
            pair.client().send(new com.pvzce.common.network.packet.CommandC2S("/tick"));
            pair.client().tick();
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, () -> clientPackets.stream().anyMatch(p -> p instanceof com.pvzce.common.network.packet.ServerMessageS2C));

        server.sendSuggestions("/level ", 1);
        waitForCondition(3_000, () -> pair.client().tick(),
                () -> clientPackets.stream().anyMatch(p -> p instanceof com.pvzce.common.network.packet.SuggestionsS2C));

        java.util.List<String> texts = clientPackets.stream()
                .filter(p -> p instanceof com.pvzce.common.network.packet.SuggestionsS2C)
                .flatMap(p -> ((com.pvzce.common.network.packet.SuggestionsS2C) p).suggestions().stream())
                .map(com.pvzce.common.network.packet.SuggestionsS2C.Suggestion::text)
                .toList();
        assertTrue(texts.contains("load"), "suggestions=" + texts);

        server.sendSuggestions("/spawn z", 2);
        waitForCondition(3_000, () -> pair.client().tick(),
                () -> clientPackets.stream()
                        .filter(p -> p instanceof com.pvzce.common.network.packet.SuggestionsS2C)
                        .flatMap(p -> ((com.pvzce.common.network.packet.SuggestionsS2C) p).suggestions().stream())
                        .anyMatch(s -> s.text().contains("zombie")));

        assertTrue(clientPackets.stream()
                .filter(p -> p instanceof com.pvzce.common.network.packet.SuggestionsS2C)
                .flatMap(p -> ((com.pvzce.common.network.packet.SuggestionsS2C) p).suggestions().stream())
                .anyMatch(s -> s.text().equals("rules")));

        server.stop();
        server.thread().join(3_000);
    }

    @Test
    void startLevelUsesSanitizedSelectedSeedCards() throws Exception {
        PvzcePackets.register();
        Path gameDir = Files.createTempDirectory("pvzce-seed-start");
        Connection.Pair pair = Connection.createMemoryPair();
        PvzceServer server = new PvzceServer(pair.server(), gameDir, Thread.currentThread().getContextClassLoader());
        server.start();
        List<PvzcePacket> clientPackets = new ArrayList<>();
        pair.client().setListener(clientPackets::add);

        pair.client().send(new StartLevelC2S("pvzce:level_1", "seedworld", false,
                List.of("pvzce:sun", "pvzce:not_a_card", "pvzce:pea_shooter", "pvzce:sun")));
        waitForCondition(5_000, () -> pair.client().tick(),
                () -> clientPackets.stream().anyMatch(LevelInitS2C.class::isInstance));
        LevelInitS2C init = clientPackets.stream()
                .filter(LevelInitS2C.class::isInstance)
                .map(LevelInitS2C.class::cast)
                .findFirst()
                .orElseThrow();
        assertEquals(List.of("pvzce:sun", "pvzce:pea_shooter"),
                init.slots().stream().map(SlotInfo::defId).toList(),
                "unknown/duplicate cards must be filtered and order preserved");
        assertEquals(6, init.maxSeedSlots());
        assertTrue(init.seedPool().size() >= 2, "level list should carry the seed pool metadata");
        assertTrue(init.previewZombies().contains("pvzce:basic_zombie"),
                "seed chooser needs the level's zombie preview list");

        pair.client().send(new LeaveLevelC2S());
        waitForCondition(5_000, () -> pair.client().tick(),
                () -> Files.isRegularFile(gameDir.resolve(
                        "saves/seedworld/levels/pvzce__level_1/level.dat")));
        server.stop();
        server.thread().join(3_000);
    }

    @Test
    void startLevelAllowsEmptySeedSelection() throws Exception {
        PvzcePackets.register();
        Path gameDir = Files.createTempDirectory("pvzce-seed-empty");
        Connection.Pair pair = Connection.createMemoryPair();
        PvzceServer server = new PvzceServer(pair.server(), gameDir, Thread.currentThread().getContextClassLoader());
        server.start();
        List<PvzcePacket> clientPackets = new ArrayList<>();
        pair.client().setListener(clientPackets::add);

        pair.client().send(new StartLevelC2S("pvzce:level_1", "seedworld", false, List.of()));
        waitForCondition(5_000, () -> pair.client().tick(),
                () -> clientPackets.stream().anyMatch(LevelInitS2C.class::isInstance));
        LevelInitS2C init = clientPackets.stream()
                .filter(LevelInitS2C.class::isInstance)
                .map(LevelInitS2C.class::cast)
                .findFirst()
                .orElseThrow();
        assertTrue(init.slots().isEmpty(), "zero-card starts are intentionally allowed");

        server.stop();
        server.thread().join(3_000);
    }

    @Test
    void continueSaveRestoresPreviouslyChosenSeedCards() throws Exception {
        PvzcePackets.register();
        Path gameDir = Files.createTempDirectory("pvzce-seed-continue");
        Connection.Pair pair = Connection.createMemoryPair();
        PvzceServer server = new PvzceServer(pair.server(), gameDir, Thread.currentThread().getContextClassLoader());
        server.start();
        List<PvzcePacket> clientPackets = new ArrayList<>();
        pair.client().setListener(clientPackets::add);

        List<String> chosen = List.of("pvzce:sun", "pvzce:pea_shooter");
        pair.client().send(new StartLevelC2S("pvzce:level_1", "seedworld", false, chosen));
        waitForCondition(5_000, () -> pair.client().tick(), () -> initCount(clientPackets) >= 1);
        pair.client().send(new LeaveLevelC2S());
        waitForCondition(5_000, () -> pair.client().tick(),
                () -> Files.isRegularFile(gameDir.resolve(
                        "saves/seedworld/levels/pvzce__level_1/level.dat")));

        pair.client().send(new ResumeLevelC2S("pvzce:level_1", "seedworld", false));
        waitForCondition(5_000, () -> pair.client().tick(), () -> initCount(clientPackets) >= 2);
        LevelInitS2C resumed = clientPackets.stream()
                .filter(LevelInitS2C.class::isInstance)
                .map(LevelInitS2C.class::cast)
                .skip(1)
                .findFirst()
                .orElseThrow();
        assertEquals(chosen, resumed.slots().stream().map(SlotInfo::defId).toList(),
                "continue must restore the saved card bar, not the level's default pool");

        server.stop();
        server.thread().join(3_000);
    }

    @Test
    void startLevelClampsSelectionToMaxSeedSlots() throws Exception {
        PvzcePackets.register();
        Path gameDir = Files.createTempDirectory("pvzce-seed-clamp");
        Connection.Pair pair = Connection.createMemoryPair();
        PvzceServer server = new PvzceServer(pair.server(), gameDir, Thread.currentThread().getContextClassLoader());
        server.start();
        List<PvzcePacket> clientPackets = new ArrayList<>();
        pair.client().setListener(clientPackets::add);

        pair.client().send(new StartLevelC2S("pvzce:level_1", "seedworld", false, List.of(
                "pvzce:pea_shooter", "pvzce:sunflower", "pvzce:wall_nut", "pvzce:kernel_pult",
                "pvzce:cherry_bomb", "pvzce:chomper", "pvzce:lily_pad", "pvzce:flower_pot")));
        waitForCondition(5_000, () -> pair.client().tick(), () -> initCount(clientPackets) >= 1);
        LevelInitS2C init = clientPackets.stream()
                .filter(LevelInitS2C.class::isInstance)
                .map(LevelInitS2C.class::cast)
                .findFirst()
                .orElseThrow();
        assertEquals(6, init.slots().size(), "level_1 defaults to max_seed_slots=6");
        assertEquals(List.of("pvzce:pea_shooter", "pvzce:sunflower", "pvzce:wall_nut",
                        "pvzce:kernel_pult", "pvzce:cherry_bomb", "pvzce:chomper"),
                init.slots().stream().map(SlotInfo::defId).toList());

        server.stop();
        server.thread().join(3_000);
    }

    @Test
    void existingSaveIsLoadedBeforePromptAndContinueDoesNotCreateAnotherLevel() throws Exception {
        PvzcePackets.register();
        Path gameDir = Files.createTempDirectory("pvzce-save-prompt");
        Connection.Pair pair = Connection.createMemoryPair();
        PvzceServer server = new PvzceServer(pair.server(), gameDir, Thread.currentThread().getContextClassLoader());
        server.start();
        List<PvzcePacket> clientPackets = new ArrayList<>();
        pair.client().setListener(clientPackets::add);

        List<String> chosen = List.of("pvzce:sun", "pvzce:pea_shooter");
        pair.client().send(new StartLevelC2S("pvzce:level_1", "seedworld", false, chosen));
        waitForCondition(5_000, () -> pair.client().tick(), () -> initCount(clientPackets) >= 1);
        pair.client().send(new LeaveLevelC2S());
        waitForCondition(5_000, () -> pair.client().tick(),
                () -> Files.isRegularFile(gameDir.resolve(
                        "saves/seedworld/levels/pvzce__level_1/level.dat")));

        int baseline = clientPackets.size();
        pair.client().send(new StartLevelC2S("pvzce:level_1", "seedworld", false, chosen));
        waitForCondition(5_000, () -> pair.client().tick(), () -> {
            List<PvzcePacket> tail = clientPackets.subList(baseline, clientPackets.size());
            return tail.stream().anyMatch(LevelInitS2C.class::isInstance)
                    && tail.stream().anyMatch(LevelSavePromptS2C.class::isInstance);
        });

        List<PvzcePacket> tail = clientPackets.subList(baseline, clientPackets.size());
        int initIndex = -1;
        int promptIndex = -1;
        for (int i = 0; i < tail.size(); i++) {
            if (initIndex < 0 && tail.get(i) instanceof LevelInitS2C) {
                initIndex = i;
            }
            if (promptIndex < 0 && tail.get(i) instanceof LevelSavePromptS2C) {
                promptIndex = i;
            }
        }
        assertTrue(initIndex >= 0, "saved world must be sent to the client first");
        assertTrue(promptIndex > initIndex, "prompt must arrive after the saved world is already loaded");
        assertNotNull(server.level(), "saved world should already be the active level");
        int pausedTick = server.level().tickCount();
        Thread.sleep(200);
        assertTrue(server.level().tickCount() - pausedTick <= 1,
                "level simulation must stay frozen while the save dialog is open");

        long initCountBeforeContinue = initCount(clientPackets);
        pair.client().send(new ResumeLevelC2S("pvzce:level_1", "seedworld", false));
        waitForCondition(2_000, () -> pair.client().tick(),
                () -> server.level() != null && server.level().tickCount() > pausedTick);
        assertTrue(initCount(clientPackets) == initCountBeforeContinue,
                "continue keeps the already loaded level instead of recreating it");

        server.stop();
        server.thread().join(3_000);
    }

    @Test
    void restartFromSavePromptWaitsForExplicitSeedSelectionBeforeCreatingFreshLevel() throws Exception {
        PvzcePackets.register();
        Path gameDir = Files.createTempDirectory("pvzce-save-restart-seeds");
        Connection.Pair pair = Connection.createMemoryPair();
        PvzceServer server = new PvzceServer(pair.server(), gameDir, Thread.currentThread().getContextClassLoader());
        server.start();
        List<PvzcePacket> clientPackets = new ArrayList<>();
        pair.client().setListener(clientPackets::add);

        List<String> saved = List.of("pvzce:sun", "pvzce:pea_shooter");
        pair.client().send(new StartLevelC2S("pvzce:level_1", "seedworld", false, saved));
        waitForCondition(5_000, () -> pair.client().tick(), () -> initCount(clientPackets) >= 1);
        pair.client().send(new LeaveLevelC2S());
        waitForCondition(5_000, () -> pair.client().tick(),
                () -> Files.isRegularFile(gameDir.resolve(
                        "saves/seedworld/levels/pvzce__level_1/level.dat")));
        Path saveDir = gameDir.resolve("saves/seedworld/levels/pvzce__level_1");

        // Entering the level loads the save first and then asks continue/restart.
        int baseline = clientPackets.size();
        pair.client().send(new RequestLevelC2S("pvzce:level_1", "seedworld", false));
        waitForCondition(5_000, () -> pair.client().tick(), () -> {
            List<PvzcePacket> tail = clientPackets.subList(baseline, clientPackets.size());
            return tail.stream().anyMatch(LevelInitS2C.class::isInstance)
                    && tail.stream().anyMatch(LevelSavePromptS2C.class::isInstance);
        });
        assertNotNull(server.level());

        // Choosing restart opens the seed chooser client-side; only after a
        // seed submission does the server discard the save and create fresh.
        int beforeRestart = clientPackets.size();
        pair.client().send(new StartLevelC2S("pvzce:level_1", "seedworld", true,
                List.of("pvzce:wall_nut", "pvzce:sunflower")));
        waitForCondition(5_000, () -> pair.client().tick(),
                () -> clientPackets.size() > beforeRestart
                        && clientPackets.subList(beforeRestart, clientPackets.size()).stream()
                        .anyMatch(LevelInitS2C.class::isInstance));
        LevelInitS2C fresh = clientPackets.subList(beforeRestart, clientPackets.size()).stream()
                .filter(LevelInitS2C.class::isInstance)
                .map(LevelInitS2C.class::cast)
                .findFirst()
                .orElseThrow();
        assertEquals(List.of("pvzce:wall_nut", "pvzce:sunflower"),
                fresh.slots().stream().map(SlotInfo::defId).toList(),
                "restart must use the newly chosen card bar");
        assertTrue(Files.notExists(saveDir.resolve("level.dat")),
                "the old running save is deleted when the fresh level starts");
        assertTrue(clientPackets.subList(beforeRestart, clientPackets.size()).stream()
                        .noneMatch(LevelSavePromptS2C.class::isInstance),
                "a fresh level must not show the save prompt again");

        server.stop();
        server.thread().join(3_000);
    }

    @Test
    void pauseGamePacketFreezesAndResumesLevelTicks() throws Exception {
        PvzcePackets.register();
        Path gameDir = Files.createTempDirectory("pvzce-pause");
        Connection.Pair pair = Connection.createMemoryPair();
        PvzceServer server = new PvzceServer(pair.server(), gameDir, Thread.currentThread().getContextClassLoader());
        server.start();
        List<PvzcePacket> clientPackets = new ArrayList<>();
        pair.client().setListener(clientPackets::add);

        pair.client().send(new RequestLevelC2S("pvzce:level_1", "pauseworld", true));
        waitForCondition(5_000, () -> pair.client().tick(),
                () -> clientPackets.stream().anyMatch(LevelInitS2C.class::isInstance));
        waitForCondition(5_000, () -> pair.client().tick(),
                () -> server.level() != null && server.level().tickCount() > 2);

        pair.client().send(new PauseGameC2S(true));
        Thread.sleep(150);
        pair.client().tick();
        long frozenTick = server.level().tickCount();
        Thread.sleep(300);
        pair.client().tick();
        assertEquals(frozenTick, server.level().tickCount(),
                "pause packet must freeze level simulation");

        pair.client().send(new PauseGameC2S(false));
        waitForCondition(3_000, () -> pair.client().tick(),
                () -> server.level().tickCount() > frozenTick);

        server.stop();
        server.thread().join(3_000);
    }

    @Test
    void restartingLevelShutsDownPreviousInstance() throws Exception {
        PvzcePackets.register();
        Path gameDir = Files.createTempDirectory("pvzce-restart-shutdown");
        Connection.Pair pair = Connection.createMemoryPair();
        PvzceServer server = new PvzceServer(pair.server(), gameDir, Thread.currentThread().getContextClassLoader());
        server.start();
        List<PvzcePacket> clientPackets = new ArrayList<>();
        pair.client().setListener(clientPackets::add);

        pair.client().send(new StartLevelC2S("pvzce:level_1", "restartworld", false,
                List.of("pvzce:sun", "pvzce:pea_shooter")));
        waitForCondition(5_000, () -> pair.client().tick(), () -> initCount(clientPackets) >= 1);
        LevelServer previous = server.level();
        assertNotNull(previous);

        pair.client().send(new StartLevelC2S("pvzce:level_1", "restartworld", true,
                List.of("pvzce:wall_nut")));
        waitForCondition(5_000, () -> pair.client().tick(), () -> initCount(clientPackets) >= 2);

        assertTrue(server.level() != previous, "restart must create a new level instance");
        assertEquals("closed", previous.gameState(), "the previous level instance must be shut down");
        assertTrue(previous.entities().isEmpty(), "shut down instance must not keep field entities");

        server.stop();
        server.thread().join(3_000);
    }

    private static long initCount(List<PvzcePacket> packets) {
        return packets.stream().filter(LevelInitS2C.class::isInstance).count();
    }

    private static void waitForCondition(long timeoutMs, Runnable tick, java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < deadline) {
            tick.run();
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Timed out waiting for condition");
    }

    private interface ServerThreadAccess {
    }
}
