package com.pvzce.server;

import com.pvzce.common.network.PvzcePacket;
import com.pvzce.common.network.packet.CommandC2S;
import com.pvzce.common.network.packet.ContinueLevelC2S;
import com.pvzce.common.network.packet.CreateWorldC2S;
import com.pvzce.common.network.packet.LeaveLevelC2S;
import com.pvzce.common.network.packet.LevelInitS2C;
import com.pvzce.common.network.packet.LevelListS2C;
import com.pvzce.common.network.packet.LevelSavePromptS2C;
import com.pvzce.common.network.packet.LevelTabsS2C;
import com.pvzce.common.network.packet.MusicEventS2C;
import com.pvzce.common.network.packet.OpenEditorS2C;
import com.pvzce.common.network.packet.PauseGameC2S;
import com.pvzce.common.network.packet.PlayLevelC2S;
import com.pvzce.common.network.packet.RequestLevelListC2S;
import com.pvzce.common.network.packet.RestartLevelC2S;
import com.pvzce.common.network.packet.ServerMessageS2C;
import com.pvzce.common.network.packet.SlotInfo;
import com.pvzce.common.network.packet.SuggestionsS2C;
import com.pvzce.common.network.packet.TimeOfDayS2C;
import com.pvzce.common.network.packet.WaveProgressS2C;
import com.pvzce.server.level.LevelServer;
import com.pvzce.testutil.ServerHarness;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
        try (ServerHarness server = ServerHarness.create(Files.createTempDirectory("pvzce-level-tabs"))) {
            server.send(new RequestLevelListC2S("tabworld"));
            LevelTabsS2C tabs = server.awaitPacket(LevelTabsS2C.class, 5_000);
            // Only pages that have levels: the unclassified bucket is added by the client
            // (`LevelPage.tabs`), which is the one place that knows the screen needs it even
            // when nothing is in it.
            assertTrue(tabs.tabs().stream().anyMatch(tab -> tab.theme().equals("pvzce:yard")
                            && tab.category().equals("pvzce:adventure")),
                    "the shipped yard/adventure levels need a page: " + tabs.tabs());
            assertTrue(tabs.tabs().stream().anyMatch(tab -> tab.theme().equals("pvzce:yard")
                            && tab.category().equals("pvzce:minigame")),
                    "the shipped yard/minigame level needs a page too: " + tabs.tabs());
            assertFalse(tabs.tabs().stream().anyMatch(tab -> tab.theme().equals("pvzce:uncategorized")),
                    "the server lists levels, not empty pages: " + tabs.tabs());

            LevelListS2C list = server.awaitPacket(LevelListS2C.class, 5_000);
            for (LevelListS2C.LevelInfo info : list.levels()) {
                assertFalse(info.isUncategorized(), info.id() + " fell out of its theme/category");
                assertEquals("pvzce:yard", info.theme(), info.id());
                assertTrue(tabs.tabs().contains(new LevelTabsS2C.Tab(info.theme(), info.category())),
                        "no page for " + info.id() + ": " + tabs.tabs());
            }
        }
    }

    @Test
    void requestLevelListThenEnterLevelCreatesWorldSave() throws Exception {
        try (ServerHarness server = ServerHarness.create(Files.createTempDirectory("pvzce-menu-flow"))) {
            LevelListS2C list = server.levelList("testworld");
            assertTrue(list.levels().stream().anyMatch(l -> l.id().equals("pvzce:yard/adventure/1_1")));

            server.send(new ContinueLevelC2S("pvzce:yard/adventure/1_1", "testworld"));
            server.waitFor(p -> p instanceof LevelInitS2C init
                    && init.levelId().equals("pvzce:yard/adventure/1_1"), 5_000);
            server.awaitPacket(WaveProgressS2C.class, 5_000);
            server.awaitPacket(TimeOfDayS2C.class, 5_000);

            server.send(new CommandC2S("/time set 600"));
            server.waitFor(p -> p instanceof ServerMessageS2C m
                    && m.message().equals("已将时间设置为 600"), 5_000);
            server.send(new CommandC2S("/tick query"));
            server.waitFor(p -> p instanceof ServerMessageS2C m
                    && m.message().startsWith("服务器状态:"), 5_000);

            server.send(new LeaveLevelC2S());
            Path save = server.gameDir().resolve("saves/testworld/levels/70767a6365__yard%2Fadventure%2F1_1");
            server.waitForFile(save.resolve("level.dat"), 5_000);
            // One save file per level holds teams, cards, scene and entities; the old
            // byte-identical data/level_state.dat and the never-read per-team/per-player
            // side files are gone.
            assertFalse(Files.exists(save.resolve("data")),
                    "the duplicated side files under data/ must not be written");
            assertTrue(Files.isRegularFile(server.gameDir().resolve("saves/testworld/session.lock")));
        }
    }

    @Test
    void brigadierCommandsExecute() throws Exception {
        try (ServerHarness server = ServerHarness.create(Files.createTempDirectory("pvzce-commands"))) {
            server.send(new CommandC2S("/tick"));
            server.waitFor(p -> p instanceof ServerMessageS2C m && m.message().startsWith("游戏 tick="), 5_000);

            server.send(new CommandC2S("/tick freeze"));
            server.waitFor(p -> p instanceof ServerMessageS2C m && m.message().equals("服务器已冻结。"), 3_000);
            server.send(new CommandC2S("/tick query"));
            server.waitFor(p -> p instanceof ServerMessageS2C m && m.message().equals("服务器状态: 已冻结"), 3_000);
            server.send(new CommandC2S("/tick unfreeze"));
            server.waitFor(p -> p instanceof ServerMessageS2C m && m.message().equals("服务器已恢复运行。"), 3_000);

            server.send(new CommandC2S("/editor open pvzce:yard/adventure/demo_level"));
            server.waitFor(p -> p instanceof OpenEditorS2C editor
                    && editor.levelId().equals("pvzce:yard/adventure/demo_level"), 3_000);
        }
    }

    @Test
    void brigadierSuggestionsCoverSubcommands() throws Exception {
        try (ServerHarness server = ServerHarness.create(Files.createTempDirectory("pvzce-suggestions"))) {
            server.server().sendSuggestions("/level ", 1);
            server.waitFor(p -> p instanceof SuggestionsS2C, 3_000);
            List<String> texts = suggestions(server.packets());
            assertTrue(texts.contains("load"), "suggestions=" + texts);

            server.server().sendSuggestions("/spawn z", 2);
            server.waitFor(p -> p instanceof SuggestionsS2C s
                    && s.suggestions().stream().anyMatch(entry -> entry.text().contains("zombie")), 3_000);
            assertTrue(suggestions(server.packets()).contains("rules"));
        }
    }

    private static List<String> suggestions(List<PvzcePacket> packets) {
        return packets.stream()
                .filter(SuggestionsS2C.class::isInstance)
                .map(SuggestionsS2C.class::cast)
                .flatMap(packet -> packet.suggestions().stream())
                .map(SuggestionsS2C.Suggestion::text)
                .toList();
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
        Path gameDir = Files.createTempDirectory("pvzce-seed-start");
        writeFreeSlotLevel(gameDir);
        try (ServerHarness server = ServerHarness.createWithWorld(gameDir, "seedworld", true)) {
            server.send(new PlayLevelC2S(FREE_SLOT_LEVEL, "seedworld", false,
                    List.of("pvzce:sun", "pvzce:not_a_card", "pvzce:wall_nut", "pvzce:sunflower",
                            "pvzce:chomper")));
            LevelInitS2C init = server.awaitPacket(LevelInitS2C.class, 5_000);
            assertEquals(List.of("pvzce:pea_shooter", "pvzce:sun", "pvzce:wall_nut", "pvzce:sunflower"),
                    init.slots().stream().map(SlotInfo::defId).toList(),
                    "the level's two cards come first, then picks fill the two free slots;"
                            + " an unknown card and a card the level already pinned are dropped");
            assertEquals(4, init.maxSeedSlots());
            assertTrue(init.seedPool().size() >= 2, "level list should carry the seed pool metadata");

            server.send(new LeaveLevelC2S());
            server.waitForFile(gameDir.resolve(
                    "saves/seedworld/levels/70767a6365__test_free_slots/level.dat"), 5_000);
        }
    }

    @Test
    void startLevelAllowsEmptySeedSelection() throws Exception {
        Path gameDir = Files.createTempDirectory("pvzce-seed-empty");
        writeFreeSlotLevel(gameDir);
        try (ServerHarness server = ServerHarness.create(gameDir)) {
            server.send(new PlayLevelC2S(FREE_SLOT_LEVEL, "seedworld", false, List.of()));
            LevelInitS2C init = server.awaitPacket(LevelInitS2C.class, 5_000);
            assertEquals(List.of("pvzce:pea_shooter", "pvzce:sun"),
                    init.slots().stream().map(SlotInfo::defId).toList(),
                    "the level's cards are granted even when the player picks nothing,"
                            + " and the free slots stay empty rather than being auto-filled");
        }
    }

    @Test
    void continueSaveRestoresPreviouslyChosenSeedCards() throws Exception {
        Path gameDir = Files.createTempDirectory("pvzce-seed-continue");
        writeFreeSlotLevel(gameDir);
        try (ServerHarness server = ServerHarness.createWithWorld(gameDir, "seedworld", true)) {
            // The bar the level actually starts with: its own two cards, then the two picks.
            List<String> bar = List.of("pvzce:pea_shooter", "pvzce:sun", "pvzce:wall_nut", "pvzce:sunflower");
            server.send(new PlayLevelC2S(FREE_SLOT_LEVEL, "seedworld", false,
                    List.of("pvzce:wall_nut", "pvzce:sunflower")));
            server.waitForCondition(() -> initCount(server.packets()) >= 1, 5_000);
            server.send(new LeaveLevelC2S());
            server.waitForFile(gameDir.resolve(
                    "saves/seedworld/levels/70767a6365__test_free_slots/level.dat"), 5_000);

            server.send(new ContinueLevelC2S(FREE_SLOT_LEVEL, "seedworld"));
            server.waitForCondition(() -> initCount(server.packets()) >= 2, 5_000);
            assertEquals(bar, lastInit(server.packets()).slots().stream().map(SlotInfo::defId).toList(),
                    "continue must restore the saved card bar, not the level's default");
        }
    }

    @Test
    void existingSaveIsLoadedBeforePromptAndContinueDoesNotCreateAnotherLevel() throws Exception {
        try (ServerHarness server = ServerHarness.create(Files.createTempDirectory("pvzce-save-prompt"))) {
            List<String> chosen = List.of("pvzce:sun", "pvzce:pea_shooter");
            server.send(new PlayLevelC2S("pvzce:yard/adventure/1_1", "seedworld", false, chosen));
            server.waitForCondition(() -> initCount(server.packets()) >= 1, 5_000);
            server.send(new LeaveLevelC2S());
            server.waitForFile(server.gameDir().resolve(
                    "saves/seedworld/levels/70767a6365__yard%2Fadventure%2F1_1/level.dat"), 5_000);

            int baseline = server.packets().size();
            server.send(new PlayLevelC2S("pvzce:yard/adventure/1_1", "seedworld", false, chosen));
            server.waitForCondition(() -> tailHas(server.packets(), baseline, LevelInitS2C.class)
                    && tailHas(server.packets(), baseline, LevelSavePromptS2C.class), 5_000);

            assertTrue(initIndexAfter(server.packets(), baseline) >= 0,
                    "saved world must be sent to the client first");
            assertTrue(promptIndexAfter(server.packets(), baseline) > initIndexAfter(server.packets(), baseline),
                    "prompt must arrive after the saved world is already loaded");
            assertNotNull(server.server().level(), "saved world should already be the active level");
            int pausedTick = server.server().level().tickCount();
            Thread.sleep(200);
            assertTrue(server.server().level().tickCount() - pausedTick <= 1,
                    "level simulation must stay frozen while the save dialog is open");

            long initCountBeforeContinue = initCount(server.packets());
            server.send(new ContinueLevelC2S("pvzce:yard/adventure/1_1", "seedworld"));
            server.waitForCondition(() -> server.server().level() != null
                    && server.server().level().tickCount() > pausedTick, 2_000);
            assertEquals(initCountBeforeContinue, initCount(server.packets()),
                    "continue keeps the already loaded level instead of recreating it");
        }
    }

    @Test
    void restartFromSavePromptWaitsForExplicitSeedSelectionBeforeCreatingFreshLevel() throws Exception {
        Path gameDir = Files.createTempDirectory("pvzce-save-restart-seeds");
        // Written before the server starts: it scans `datapacks/` during startup, so a pack
        // created afterwards is never seen and the level would not exist.
        writeFreeSlotLevel(gameDir);
        try (ServerHarness server = ServerHarness.create(gameDir)) {
            server.send(new PlayLevelC2S(FREE_SLOT_LEVEL, "seedworld", false,
                    List.of("pvzce:wall_nut", "pvzce:sunflower")));
            server.waitForCondition(() -> initCount(server.packets()) >= 1, 5_000);
            server.send(new LeaveLevelC2S());
            Path saveDir = gameDir.resolve("saves/seedworld/levels/70767a6365__test_free_slots");
            server.waitForFile(saveDir.resolve("level.dat"), 5_000);

            // Entering the level loads the save first and then asks continue/restart. This is the
            // level list's path, so it is the seed chooser's packet (restart=false) that carries
            // it: the chooser is submitted before any save is known about, and the save it then
            // discovers is asked about rather than silently resumed.
            int baseline = server.packets().size();
            server.send(new PlayLevelC2S(FREE_SLOT_LEVEL, "seedworld", false,
                    List.of("pvzce:wall_nut", "pvzce:sunflower")));
            server.waitForCondition(() -> tailHas(server.packets(), baseline, LevelInitS2C.class)
                    && tailHas(server.packets(), baseline, LevelSavePromptS2C.class), 5_000);
            assertNotNull(server.server().level());

            // Choosing restart opens the seed chooser client-side; only after a
            // seed submission does the server discard the save and create fresh.
            int beforeRestart = server.packets().size();
            server.send(new CreateWorldC2S("seedworld", true));
            server.send(new PlayLevelC2S(FREE_SLOT_LEVEL, "seedworld", true,
                    List.of("pvzce:kernel_pult", "pvzce:chomper")));
            LevelInitS2C fresh = awaitInitAfter(server, beforeRestart);
            assertEquals(List.of("pvzce:pea_shooter", "pvzce:sun", "pvzce:kernel_pult", "pvzce:chomper"),
                    fresh.slots().stream().map(SlotInfo::defId).toList(),
                    "restart must use the newly chosen picks in the free slots");
            assertTrue(Files.notExists(saveDir.resolve("level.dat")),
                    "the old running save is deleted when the fresh level starts");
            assertFalse(tailHas(server.packets(), beforeRestart, LevelSavePromptS2C.class),
                    "a fresh level must not show the save prompt again");
        }
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
        try (ServerHarness server = ServerHarness.create(Files.createTempDirectory("pvzce-push-world"))) {
            // A save in "alphaworld" only: in "world" (the server's fallback) there is none.
            server.send(new PlayLevelC2S("pvzce:yard/adventure/1_1", "alphaworld", false,
                    List.of("pvzce:sun", "pvzce:pea_shooter")));
            server.waitForCondition(() -> initCount(server.packets()) >= 1, 5_000);
            server.send(new LeaveLevelC2S());
            server.waitForFile(server.gameDir().resolve(
                    "saves/alphaworld/levels/70767a6365__yard%2Fadventure%2F1_1/level.dat"), 5_000);

            // The client is looking at "alphaworld", so that is the world it lists.
            LevelListS2C listed = server.levelList("alphaworld");
            assertEquals("in_progress", levelStatusOf(listed, "pvzce:yard/adventure/1_1"),
                    "the requested list describes the world with the save");

            int before = server.packets().size();
            server.server().refreshLevelList();
            LevelListS2C pushed = awaitLatestListAfter(server, before);
            assertEquals("in_progress", levelStatusOf(pushed, "pvzce:yard/adventure/1_1"),
                    "the pushed refresh must still describe the world the client is viewing");
        }
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
        try (ServerHarness server = ServerHarness.create(Files.createTempDirectory("pvzce-music-reset"))) {
            server.send(new PlayLevelC2S("pvzce:yard/adventure/1_1", "musicworld", false,
                    List.of("pvzce:sun", "pvzce:pea_shooter")));
            server.waitForCondition(() -> initCount(server.packets()) >= 1, 5_000);

            // Restart: a new instance must clear what the previous run was playing.
            int beforeRestart = server.packets().size();
            server.send(new PlayLevelC2S("pvzce:yard/adventure/1_1", "musicworld", true,
                    List.of("pvzce:sun")));
            LevelInitS2C restartInit = awaitInitAfter(server, beforeRestart);
            List<PvzcePacket> restartTail = server.packets().subList(beforeRestart, server.packets().size());
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
            //
            // Wait for the new instance to have ticked first. A level's opening music cue is
            // sent by `processMusicCues` on its first tick, which is *after* the init it was
            // created with - so a baseline taken between the two would count that cue as "the
            // resync changed the music" and fail roughly one run in four.
            server.waitForCondition(() -> server.server().level() != null
                    && server.server().level().tickCount() > 1, 5_000);
            int beforeResync = server.packets().size();
            server.send(new ContinueLevelC2S("pvzce:yard/adventure/1_1", "musicworld"));
            awaitInitAfter(server, beforeResync);
            assertFalse(tailHas(server.packets(), beforeResync, MusicEventS2C.class),
                    "resyncing the running level must not touch its music");
        }
    }

    @Test
    void pauseGamePacketFreezesAndResumesLevelTicks() throws Exception {
        try (ServerHarness server = ServerHarness.create(Files.createTempDirectory("pvzce-pause"))) {
            server.send(new RestartLevelC2S("pvzce:yard/adventure/1_1", "pauseworld", List.of()));
            server.awaitPacket(LevelInitS2C.class, 5_000);
            server.waitForCondition(() -> server.server().level() != null
                    && server.server().level().tickCount() > 2, 5_000);

            server.send(new PauseGameC2S(true));
            Thread.sleep(150);
            server.pair().client().tick();
            long frozenTick = server.server().level().tickCount();
            Thread.sleep(300);
            server.pair().client().tick();
            assertEquals(frozenTick, server.server().level().tickCount(),
                    "pause packet must freeze level simulation");

            server.send(new PauseGameC2S(false));
            server.waitForCondition(() -> server.server().level().tickCount() > frozenTick, 3_000);
        }
    }

    private static long initCount(List<PvzcePacket> packets) {
        return packets.stream().filter(LevelInitS2C.class::isInstance).count();
    }

    /** The latest {@code LevelInitS2C} received so far. */
    private static LevelInitS2C lastInit(List<PvzcePacket> packets) {
        return packets.stream().filter(LevelInitS2C.class::isInstance)
                .map(LevelInitS2C.class::cast).reduce((first, second) -> second).orElseThrow();
    }

    /** True when anything of {@code type} arrived after {@code baseline}. */
    private static boolean tailHas(List<PvzcePacket> packets, int baseline, Class<?> type) {
        return packets.subList(baseline, packets.size()).stream().anyMatch(type::isInstance);
    }

    /** Waits for a level to start after {@code baseline} and returns its init packet. */
    private static LevelInitS2C awaitInitAfter(ServerHarness server, int baseline) throws Exception {
        server.waitForCondition(() -> tailHas(server.packets(), baseline, LevelInitS2C.class), 5_000);
        return server.packets().subList(baseline, server.packets().size()).stream()
                .filter(LevelInitS2C.class::isInstance)
                .map(LevelInitS2C.class::cast)
                .findFirst()
                .orElseThrow();
    }

    /** Waits for a pushed level list after {@code baseline} and returns it. */
    private static LevelListS2C awaitLatestListAfter(ServerHarness server, int baseline) throws Exception {
        server.waitForCondition(() -> tailHas(server.packets(), baseline, LevelListS2C.class), 5_000);
        return server.packets().subList(baseline, server.packets().size()).stream()
                .filter(LevelListS2C.class::isInstance)
                .map(LevelListS2C.class::cast)
                .findFirst()
                .orElseThrow();
    }

    private static int initIndexAfter(List<PvzcePacket> packets, int baseline) {
        return indexAfter(packets, baseline, LevelInitS2C.class);
    }

    private static int promptIndexAfter(List<PvzcePacket> packets, int baseline) {
        return indexAfter(packets, baseline, LevelSavePromptS2C.class);
    }

    /** Where the first packet of {@code type} sits after {@code baseline}, or -1. */
    private static int indexAfter(List<PvzcePacket> packets, int baseline, Class<?> type) {
        for (int i = baseline; i < packets.size(); i++) {
            if (type.isInstance(packets.get(i))) {
                return i - baseline;
            }
        }
        return -1;
    }

    private static String levelStatusOf(LevelListS2C list, String levelId) {
        return list.levels().stream().filter(level -> level.id().equals(levelId))
                .map(LevelListS2C.LevelInfo::status).findFirst().orElseThrow();
    }
}
