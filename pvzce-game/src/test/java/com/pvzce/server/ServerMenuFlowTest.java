package com.pvzce.server;

import com.pvzce.common.network.Connection;
import com.pvzce.common.network.PvzcePacket;
import com.pvzce.common.network.PvzcePackets;
import com.pvzce.common.network.packet.CreateWorldC2S;
import com.pvzce.common.network.packet.LeaveLevelC2S;
import com.pvzce.common.network.packet.LevelInitS2C;
import com.pvzce.common.network.packet.LevelListS2C;
import com.pvzce.common.network.packet.LevelSavePromptS2C;
import com.pvzce.common.network.packet.LevelTabsS2C;
import com.pvzce.common.network.packet.MusicEventS2C;
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
    /**
     * The shipped levels are grouped, and the pages travel with them.
     *
     * <p>The select screen is only as correct as these two facts: every level's id path
     * names a real theme and category, and the tab table has a page for each of them. A level
     * that fell into the unclassified bucket would still be reachable, but it would be in the
     * wrong place with nothing on screen saying so.
     */
    @Test
    void theShippedLevelsCarryTheirThemeAndCategoryAndTheirPages() throws Exception {
        PvzcePackets.register();
        Path gameDir = Files.createTempDirectory("pvzce-level-tabs");
        Connection.Pair pair = Connection.createMemoryPair();
        PvzceServer server = new PvzceServer(pair.server(), gameDir, Thread.currentThread().getContextClassLoader());
        server.start();

        List<PvzcePacket> packets = new ArrayList<>();
        pair.client().setListener(packets::add);
        waitForCondition(5_000, () -> {
            pair.client().send(new RequestLevelListC2S("tabworld"));
            pair.client().tick();
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, () -> packets.stream().anyMatch(p -> p instanceof LevelTabsS2C tabs
                && !tabs.tabs().isEmpty()));

        LevelTabsS2C tabs = packets.stream().filter(LevelTabsS2C.class::isInstance)
                .map(LevelTabsS2C.class::cast).findFirst().orElse(null);
        assertNotNull(tabs);
        // Only pages that have levels: the unclassified bucket is added by the client
        // (`LevelPage.tabs`), which is the one place that knows the screen needs it even
        // when nothing is in it.
        assertTrue(tabs.tabs().stream().anyMatch(tab -> tab.theme().equals("pvzce:yard")
                        && tab.category().equals("pvzce:adventure")),
                "the shipped yard/adventure levels need a page: " + tabs.tabs());
        assertFalse(tabs.tabs().stream().anyMatch(tab -> tab.theme().equals("pvzce:uncategorized")),
                "the server lists levels, not empty pages: " + tabs.tabs());

        LevelListS2C list = packets.stream().filter(LevelListS2C.class::isInstance)
                .map(LevelListS2C.class::cast).findFirst().orElse(null);
        assertNotNull(list);
        for (LevelListS2C.LevelInfo info : list.levels()) {
            assertFalse(info.isUncategorized(), info.id() + " fell out of its theme/category");
            assertEquals("pvzce:yard", info.theme(), info.id());
            assertEquals("pvzce:adventure", info.category(), info.id());
            assertTrue(tabs.tabs().contains(new LevelTabsS2C.Tab(info.theme(), info.category())),
                    "no page for " + info.id() + ": " + tabs.tabs());
        }

        server.stop();
        server.thread().join(3_000);
    }

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
        assertTrue(list.levels().stream().anyMatch(l -> l.id().equals("pvzce:yard/adventure/1_1")));

        pair.client().send(new RequestLevelC2S("pvzce:yard/adventure/1_1", "testworld", false));
        waitForCondition(5_000, () -> pair.client().tick(),
                () -> clientPackets.stream().anyMatch(p -> p instanceof LevelInitS2C init && init.levelId().equals("pvzce:yard/adventure/1_1")));
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
                        "saves/testworld/levels/70767a6365__yard%2Fadventure%2F1_1/level.dat")));
        assertTrue(Files.isRegularFile(gameDir.resolve("saves/testworld/levels/70767a6365__yard%2Fadventure%2F1_1/level.dat")));
        // One save file per level holds teams, cards, scene and entities; the old
        // byte-identical data/level_state.dat and the never-read per-team/per-player
        // side files are gone.
        assertFalse(Files.exists(gameDir.resolve("saves/testworld/levels/70767a6365__yard%2Fadventure%2F1_1/data")),
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

        pair.client().send(new com.pvzce.common.network.packet.CommandC2S("/editor open pvzce:yard/adventure/demo_level"));
        waitForCondition(3_000, () -> pair.client().tick(),
                () -> clientPackets.stream().anyMatch(p -> p instanceof com.pvzce.common.network.packet.OpenEditorS2C e
                        && e.levelId().equals("pvzce:yard/adventure/demo_level")));

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


    /**
     * Writes a level whose bar has free slots, so the seed-selection paths can be tested.
     *
     * <p>It lists two cards and declares four slots: the two cards are pinned and the other
     * two are the player's. The built-in levels cannot serve here - they list their whole
     * pool, so every slot is pinned and there is nothing left to select.
     */
    private static final String FREE_SLOT_LEVEL = "pvzce:test_free_slots";

    private static void writeFreeSlotLevel(Path gameDir) throws Exception {
        Path pack = gameDir.resolve("datapacks/seed_test");
        Path levelFile = pack.resolve("data/pvzce/levels/test_free_slots.json");
        Files.createDirectories(levelFile.getParent());
        Files.writeString(pack.resolve("pack.mcmeta"),
                "{\"pack\":{\"pack_format\":1,\"description\":\"seed selection test\"}}");
        Files.writeString(levelFile, """
                {
                  "id": "pvzce:test_free_slots",
                  "name": "Free Slot Test",
                  "width": 3,
                  "height": 1,
                  "scene": { "pvzce:grass": [ "0,0", "1,0", "2,0" ] },
                  "rules": { "pvzce:day_length": 0, "pvzce:night_length": -1 },
                  "waves": [],
                  "slots": [ "pvzce:pea_shooter", "pvzce:sun" ],
                  "max_seed_slots": 4
                }
                """);
    }

    @Test
    void startLevelUsesSanitizedSelectedSeedCards() throws Exception {
        PvzcePackets.register();
        Path gameDir = Files.createTempDirectory("pvzce-seed-start");
        writeFreeSlotLevel(gameDir);
        Connection.Pair pair = Connection.createMemoryPair();
        PvzceServer server = new PvzceServer(pair.server(), gameDir, Thread.currentThread().getContextClassLoader());
        server.start();
        List<PvzcePacket> clientPackets = new ArrayList<>();
        pair.client().setListener(clientPackets::add);

        unlockEverything(pair, "seedworld");
        pair.client().send(new StartLevelC2S(FREE_SLOT_LEVEL, "seedworld", false,
                List.of("pvzce:sun", "pvzce:not_a_card", "pvzce:wall_nut", "pvzce:sunflower",
                        "pvzce:chomper")));
        waitForCondition(5_000, () -> pair.client().tick(),
                () -> clientPackets.stream().anyMatch(LevelInitS2C.class::isInstance));
        LevelInitS2C init = clientPackets.stream()
                .filter(LevelInitS2C.class::isInstance)
                .map(LevelInitS2C.class::cast)
                .findFirst()
                .orElseThrow();
        assertEquals(List.of("pvzce:pea_shooter", "pvzce:sun", "pvzce:wall_nut", "pvzce:sunflower"),
                init.slots().stream().map(SlotInfo::defId).toList(),
                "the level's two cards come first, then picks fill the two free slots;"
                        + " an unknown card and a card the level already pinned are dropped");
        assertEquals(4, init.maxSeedSlots());
        assertTrue(init.seedPool().size() >= 2, "level list should carry the seed pool metadata");

        pair.client().send(new LeaveLevelC2S());
        waitForCondition(5_000, () -> pair.client().tick(),
                () -> Files.isRegularFile(gameDir.resolve(
                        "saves/seedworld/levels/70767a6365__test_free_slots/level.dat")));
        server.stop();
        server.thread().join(3_000);
    }

    @Test
    void startLevelAllowsEmptySeedSelection() throws Exception {
        PvzcePackets.register();
        Path gameDir = Files.createTempDirectory("pvzce-seed-empty");
        writeFreeSlotLevel(gameDir);
        Connection.Pair pair = Connection.createMemoryPair();
        PvzceServer server = new PvzceServer(pair.server(), gameDir, Thread.currentThread().getContextClassLoader());
        server.start();
        List<PvzcePacket> clientPackets = new ArrayList<>();
        pair.client().setListener(clientPackets::add);

        pair.client().send(new StartLevelC2S(FREE_SLOT_LEVEL, "seedworld", false, List.of()));
        waitForCondition(5_000, () -> pair.client().tick(),
                () -> clientPackets.stream().anyMatch(LevelInitS2C.class::isInstance));
        LevelInitS2C init = clientPackets.stream()
                .filter(LevelInitS2C.class::isInstance)
                .map(LevelInitS2C.class::cast)
                .findFirst()
                .orElseThrow();
        assertEquals(List.of("pvzce:pea_shooter", "pvzce:sun"),
                init.slots().stream().map(SlotInfo::defId).toList(),
                "the level's cards are granted even when the player picks nothing,"
                        + " and the free slots stay empty rather than being auto-filled");

        server.stop();
        server.thread().join(3_000);
    }

    @Test
    void continueSaveRestoresPreviouslyChosenSeedCards() throws Exception {
        PvzcePackets.register();
        Path gameDir = Files.createTempDirectory("pvzce-seed-continue");
        writeFreeSlotLevel(gameDir);
        Connection.Pair pair = Connection.createMemoryPair();
        PvzceServer server = new PvzceServer(pair.server(), gameDir, Thread.currentThread().getContextClassLoader());
        server.start();
        List<PvzcePacket> clientPackets = new ArrayList<>();
        pair.client().setListener(clientPackets::add);

        // The bar the level actually starts with: its own two cards, then the two picks.
        List<String> bar = List.of("pvzce:pea_shooter", "pvzce:sun", "pvzce:wall_nut", "pvzce:sunflower");
        unlockEverything(pair, "seedworld");
        pair.client().send(new StartLevelC2S(FREE_SLOT_LEVEL, "seedworld", false,
                List.of("pvzce:wall_nut", "pvzce:sunflower")));
        waitForCondition(5_000, () -> pair.client().tick(), () -> initCount(clientPackets) >= 1);
        pair.client().send(new LeaveLevelC2S());
        waitForCondition(5_000, () -> pair.client().tick(),
                () -> Files.isRegularFile(gameDir.resolve(
                        "saves/seedworld/levels/70767a6365__test_free_slots/level.dat")));

        pair.client().send(new ResumeLevelC2S(FREE_SLOT_LEVEL, "seedworld", false));
        waitForCondition(5_000, () -> pair.client().tick(), () -> initCount(clientPackets) >= 2);
        LevelInitS2C resumed = clientPackets.stream()
                .filter(LevelInitS2C.class::isInstance)
                .map(LevelInitS2C.class::cast)
                .skip(1)
                .findFirst()
                .orElseThrow();
        assertEquals(bar, resumed.slots().stream().map(SlotInfo::defId).toList(),
                "continue must restore the saved card bar, not the level's default");

        server.stop();
        server.thread().join(3_000);
    }

    @Test
    void startLevelFillsOnlyTheFreeSlotsFromThePlayersPicks() throws Exception {
        PvzcePackets.register();
        Path gameDir = Files.createTempDirectory("pvzce-seed-clamp");
        writeFreeSlotLevel(gameDir);
        Connection.Pair pair = Connection.createMemoryPair();
        PvzceServer server = new PvzceServer(pair.server(), gameDir, Thread.currentThread().getContextClassLoader());
        server.start();
        List<PvzcePacket> clientPackets = new ArrayList<>();
        pair.client().setListener(clientPackets::add);

        unlockEverything(pair, "seedworld");
        pair.client().send(new StartLevelC2S(FREE_SLOT_LEVEL, "seedworld", false, List.of(
                "pvzce:wall_nut", "pvzce:kernel_pult", "pvzce:cherry_bomb", "pvzce:chomper",
                "pvzce:lily_pad", "pvzce:flower_pot")));
        waitForCondition(5_000, () -> pair.client().tick(), () -> initCount(clientPackets) >= 1);
        LevelInitS2C init = clientPackets.stream()
                .filter(LevelInitS2C.class::isInstance)
                .map(LevelInitS2C.class::cast)
                .findFirst()
                .orElseThrow();
        assertEquals(4, init.slots().size(), "the level declares four slots");
        assertEquals(List.of("pvzce:pea_shooter", "pvzce:sun", "pvzce:wall_nut", "pvzce:kernel_pult"),
                init.slots().stream().map(SlotInfo::defId).toList(),
                "only the two free slots take picks, in submission order");

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
        pair.client().send(new StartLevelC2S("pvzce:yard/adventure/1_1", "seedworld", false, chosen));
        waitForCondition(5_000, () -> pair.client().tick(), () -> initCount(clientPackets) >= 1);
        pair.client().send(new LeaveLevelC2S());
        waitForCondition(5_000, () -> pair.client().tick(),
                () -> Files.isRegularFile(gameDir.resolve(
                        "saves/seedworld/levels/70767a6365__yard%2Fadventure%2F1_1/level.dat")));

        int baseline = clientPackets.size();
        pair.client().send(new StartLevelC2S("pvzce:yard/adventure/1_1", "seedworld", false, chosen));
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
        pair.client().send(new ResumeLevelC2S("pvzce:yard/adventure/1_1", "seedworld", false));
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
        // Written before the server starts: it scans `datapacks/` during startup, so a pack
        // created afterwards is never seen and the level would not exist.
        writeFreeSlotLevel(gameDir);
        Connection.Pair pair = Connection.createMemoryPair();
        PvzceServer server = new PvzceServer(pair.server(), gameDir, Thread.currentThread().getContextClassLoader());
        server.start();
        List<PvzcePacket> clientPackets = new ArrayList<>();
        pair.client().setListener(clientPackets::add);

        pair.client().send(new StartLevelC2S(FREE_SLOT_LEVEL, "seedworld", false,
                List.of("pvzce:wall_nut", "pvzce:sunflower")));
        waitForCondition(5_000, () -> pair.client().tick(), () -> initCount(clientPackets) >= 1);
        pair.client().send(new LeaveLevelC2S());
        waitForCondition(5_000, () -> pair.client().tick(),
                () -> Files.isRegularFile(gameDir.resolve(
                        "saves/seedworld/levels/70767a6365__test_free_slots/level.dat")));
        Path saveDir = gameDir.resolve("saves/seedworld/levels/70767a6365__test_free_slots");

        // Entering the level loads the save first and then asks continue/restart.
        int baseline = clientPackets.size();
        pair.client().send(new RequestLevelC2S(FREE_SLOT_LEVEL, "seedworld", false));
        waitForCondition(5_000, () -> pair.client().tick(), () -> {
            List<PvzcePacket> tail = clientPackets.subList(baseline, clientPackets.size());
            return tail.stream().anyMatch(LevelInitS2C.class::isInstance)
                    && tail.stream().anyMatch(LevelSavePromptS2C.class::isInstance);
        });
        assertNotNull(server.level());

        // Choosing restart opens the seed chooser client-side; only after a
        // seed submission does the server discard the save and create fresh.
        int beforeRestart = clientPackets.size();
        unlockEverything(pair, "seedworld");
        pair.client().send(new StartLevelC2S(FREE_SLOT_LEVEL, "seedworld", true,
                List.of("pvzce:kernel_pult", "pvzce:chomper")));
        waitForCondition(5_000, () -> pair.client().tick(),
                () -> clientPackets.size() > beforeRestart
                        && clientPackets.subList(beforeRestart, clientPackets.size()).stream()
                        .anyMatch(LevelInitS2C.class::isInstance));
        LevelInitS2C fresh = clientPackets.subList(beforeRestart, clientPackets.size()).stream()
                .filter(LevelInitS2C.class::isInstance)
                .map(LevelInitS2C.class::cast)
                .findFirst()
                .orElseThrow();
        assertEquals(List.of("pvzce:pea_shooter", "pvzce:sun", "pvzce:kernel_pult", "pvzce:chomper"),
                fresh.slots().stream().map(SlotInfo::defId).toList(),
                "restart must use the newly chosen picks in the free slots");
        assertTrue(Files.notExists(saveDir.resolve("level.dat")),
                "the old running save is deleted when the fresh level starts");
        assertTrue(clientPackets.subList(beforeRestart, clientPackets.size()).stream()
                        .noneMatch(LevelSavePromptS2C.class::isInstance),
                "a fresh level must not show the save prompt again");

        server.stop();
        server.thread().join(3_000);
    }

    /**
     * A pushed level-list refresh (the {@code /reload} an editor test ends with) must describe
     * the world the client was viewing.
     *
     * <p>It used to be built from the server's own world, which is null between levels and
     * therefore always sanitized to {@code "world"}. A client sitting in another world got
     * that world's 进行中 labels, and those labels decide whether entering a level loads a
     * save or opens the seed chooser - so a level that did have a save could be sent down the
     * seed-chooser path and only reach its save prompt afterwards.
     */
    @Test
    void pushedLevelListRefreshDescribesTheWorldTheClientWasViewing() throws Exception {
        PvzcePackets.register();
        Path gameDir = Files.createTempDirectory("pvzce-push-world");
        Connection.Pair pair = Connection.createMemoryPair();
        PvzceServer server = new PvzceServer(pair.server(), gameDir, Thread.currentThread().getContextClassLoader());
        server.start();
        List<PvzcePacket> clientPackets = new ArrayList<>();
        pair.client().setListener(clientPackets::add);

        // A save in "alphaworld" only: in "world" (the server's fallback) there is none.
        pair.client().send(new StartLevelC2S("pvzce:yard/adventure/1_1", "alphaworld", false,
                List.of("pvzce:sun", "pvzce:pea_shooter")));
        waitForCondition(5_000, () -> pair.client().tick(), () -> initCount(clientPackets) >= 1);
        pair.client().send(new LeaveLevelC2S());
        waitForCondition(5_000, () -> pair.client().tick(),
                () -> Files.isRegularFile(gameDir.resolve(
                        "saves/alphaworld/levels/70767a6365__yard%2Fadventure%2F1_1/level.dat")));

        // The client is looking at "alphaworld", so that is the world it lists.
        pair.client().send(new RequestLevelListC2S("alphaworld"));
        waitForCondition(5_000, () -> pair.client().tick(), () -> !levelLists(clientPackets).isEmpty());
        assertTrue(levelStatusOf(lastLevelList(clientPackets), "pvzce:yard/adventure/1_1").equals("in_progress"),
                "the requested list describes the world with the save");

        int before = clientPackets.size();
        server.refreshLevelList();
        waitForCondition(5_000, () -> pair.client().tick(), () -> clientPackets.size() > before
                && clientPackets.subList(before, clientPackets.size()).stream()
                .anyMatch(LevelListS2C.class::isInstance));
        LevelListS2C pushed = clientPackets.subList(before, clientPackets.size()).stream()
                .filter(LevelListS2C.class::isInstance)
                .map(LevelListS2C.class::cast)
                .findFirst()
                .orElseThrow();
        assertEquals("in_progress", levelStatusOf(pushed, "pvzce:yard/adventure/1_1"),
                "the pushed refresh must still describe the world the client is viewing");

        server.stop();
        server.thread().join(3_000);
    }

    private static List<LevelListS2C> levelLists(List<PvzcePacket> packets) {
        return packets.stream().filter(LevelListS2C.class::isInstance)
                .map(LevelListS2C.class::cast).toList();
    }

    private static LevelListS2C lastLevelList(List<PvzcePacket> packets) {
        List<LevelListS2C> lists = levelLists(packets);
        return lists.get(lists.size() - 1);
    }

    private static String levelStatusOf(LevelListS2C list, String levelId) {
        return list.levels().stream().filter(level -> level.id().equals(levelId))
                .map(LevelListS2C.LevelInfo::status).findFirst().orElseThrow();
    }

    /**
     * A new level instance silences the level tracks the client was playing, and a resync of
     * the running one leaves them alone.
     *
     * <p>Reported symptom: restarting a level played the old run's music under the new run's
     * track. A level's timeline moves from the background track to the battle track at a wave,
     * and only leaving a level stopped it - a restart from the save prompt or from the editor's
     * test button never leaves one, so the server has to say so itself.
     */
    @Test
    void aNewLevelInstanceResetsTheClientsMusicAndAResyncDoesNot() throws Exception {
        PvzcePackets.register();
        Path gameDir = Files.createTempDirectory("pvzce-music-reset");
        Connection.Pair pair = Connection.createMemoryPair();
        PvzceServer server = new PvzceServer(pair.server(), gameDir, Thread.currentThread().getContextClassLoader());
        server.start();
        List<PvzcePacket> clientPackets = new ArrayList<>();
        pair.client().setListener(clientPackets::add);

        pair.client().send(new StartLevelC2S("pvzce:yard/adventure/1_1", "musicworld", false,
                List.of("pvzce:sun", "pvzce:pea_shooter")));
        waitForCondition(5_000, () -> pair.client().tick(), () -> initCount(clientPackets) >= 1);

        // Restart: a new instance must clear what the previous run was playing.
        int beforeRestart = clientPackets.size();
        pair.client().send(new StartLevelC2S("pvzce:yard/adventure/1_1", "musicworld", true, List.of("pvzce:sun")));
        waitForCondition(5_000, () -> pair.client().tick(),
                () -> clientPackets.subList(beforeRestart, clientPackets.size()).stream()
                        .anyMatch(LevelInitS2C.class::isInstance));
        List<PvzcePacket> restartTail = clientPackets.subList(beforeRestart, clientPackets.size());
        LevelInitS2C restartInit = restartTail.stream().filter(LevelInitS2C.class::isInstance)
                .map(LevelInitS2C.class::cast).findFirst().orElseThrow();
        int restartInitIndex = restartTail.indexOf(restartInit);
        List<String> stoppedBeforeInit = restartTail.subList(0, restartInitIndex).stream()
                .filter(MusicEventS2C.class::isInstance)
                .map(MusicEventS2C.class::cast)
                .filter(MusicEventS2C::stop)
                .map(MusicEventS2C::track)
                .toList();
        assertTrue(stoppedBeforeInit.contains(MusicEventS2C.TRACK_BATTLE),
                "the previous run's battle track must be stopped before the new level starts: "
                        + stoppedBeforeInit);
        assertTrue(stoppedBeforeInit.contains(MusicEventS2C.TRACK_BACKGROUND),
                "and so must its background track: " + stoppedBeforeInit);

        // Resync of the running level: its music is current, so nothing may be stopped.
        int beforeResync = clientPackets.size();
        pair.client().send(new RequestLevelC2S("pvzce:yard/adventure/1_1", "musicworld", false));
        waitForCondition(5_000, () -> pair.client().tick(),
                () -> clientPackets.subList(beforeResync, clientPackets.size()).stream()
                        .anyMatch(LevelInitS2C.class::isInstance));
        assertTrue(clientPackets.subList(beforeResync, clientPackets.size()).stream()
                        .noneMatch(MusicEventS2C.class::isInstance),
                "resyncing the running level must not touch its music");

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

        pair.client().send(new RequestLevelC2S("pvzce:yard/adventure/1_1", "pauseworld", true));
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

        pair.client().send(new StartLevelC2S("pvzce:yard/adventure/1_1", "restartworld", false,
                List.of("pvzce:sun", "pvzce:pea_shooter")));
        waitForCondition(5_000, () -> pair.client().tick(), () -> initCount(clientPackets) >= 1);
        LevelServer previous = server.level();
        assertNotNull(previous);

        pair.client().send(new StartLevelC2S("pvzce:yard/adventure/1_1", "restartworld", true,
                List.of("pvzce:pea_shooter", "pvzce:sun")));
        waitForCondition(5_000, () -> pair.client().tick(), () -> initCount(clientPackets) >= 2);

        assertTrue(server.level() != previous, "restart must create a new level instance");
        assertEquals("closed", previous.gameState(), "the previous level instance must be shut down");
        assertTrue(previous.entities().isEmpty(), "shut down instance must not keep field entities");

        server.stop();
        server.thread().join(3_000);
    }

    /**
     * Makes a world a sandbox, so every card is pickable.
     *
     * <p>These tests are about seed sanitization, not about the backpack: without
     * this the freshly created world owns only the starter cards and every pick in
     * this file would be filtered out before sanitization ever ran. Sent before the
     * level start because the server handles client packets in order, and a world
     * creation both writes the profile and fills the server's profile cache.
     */
    private static void unlockEverything(Connection.Pair pair, String world) {
        pair.client().send(new CreateWorldC2S(world, true));
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
