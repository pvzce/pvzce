package com.pvzce.client;

import com.pvzce.client.gui.screens.ChooseSeedsScreen;
import com.pvzce.client.gui.screens.LevelSetupScreen;
import com.pvzce.common.network.packet.LevelListS2C;
import com.pvzce.common.network.packet.LevelPayload;
import com.pvzce.common.network.packet.ContinueLevelC2S;
import com.pvzce.common.network.packet.SceneSyncS2C;
import com.pvzce.common.network.packet.SeedOption;
import com.pvzce.testutil.ClientHarness;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Entering a level from a menu: which pre-game flow it takes, and what the level list does
 * with its selection while the list is refreshed.
 *
 * <p>Reported symptom: entering a level that already had progress went through the seed
 * chooser first, and the "发现存档" continue/restart prompt only appeared once cards had been
 * picked - the list's 下一步 button sent every level through 关卡准备 and the chooser, and the
 * chooser's submit is what made the server load the save and ask. A run that is about to be
 * resumed must not ask the player to pick cards for it.
 */
class LevelEntryFlowTest {
    private final List<ClientHarness> harnesses = new ArrayList<>();

    @AfterEach
    void closeHarnesses() {
        harnesses.forEach(ClientHarness::close);
    }

    private ClientHarness newClient() throws Exception {
        ClientHarness harness = ClientHarness.create("pvzce-entry-flow");
        harnesses.add(harness);
        return harness;
    }

    private static LevelListS2C.LevelInfo levelInfo(String levelId, String status) {
        return levelInfo(levelId, status, LevelListS2C.LevelInfo.IN_PROGRESS.equals(status));
    }

    private static LevelListS2C.LevelInfo levelInfo(String levelId, String status, boolean runningSave) {
        LevelPayload payload = new LevelPayload(9, 5,
                List.of(new SeedOption("pvzce:pea_shooter", "plant", "pvzce:pea_shooter",
                        "pvzce:textures/entities/pea_shooter", 100)),
                6, List.of("pvzce:basic_zombie"),
                List.of(new SceneSyncS2C.Cell(0, 0, "pvzce:grass")), List.of(), List.of());
        return LevelListS2C.LevelInfo.of(levelId, "第一关", "描述", "pvzce:plant_team",
                List.of(new LevelListS2C.TeamInfo("pvzce:plant_team", "植物方", "survive_waves")),
                status, "day", "pvzce:yard", "pvzce:adventure", runningSave, payload,
                LevelListS2C.UnlockInfo.OPEN);
    }

    /**
     * A level with a resumable save is entered directly: the server loads the whole save and
     * then asks continue/restart over it, so no seed chooser may appear on the way in.
     */
    @Test
    void anInProgressLevelIsEnteredDirectlyWithoutTheSeedChooser() throws Exception {
        ClientHarness fixture = newClient();
        PvzceClient client = fixture.client();
        LevelListS2C.LevelInfo info = levelInfo("pvzce:level_1", LevelListS2C.LevelInfo.IN_PROGRESS);
        client.setLevelList(List.of(info));

        client.enterLevelFromMenu(info);

        assertFalse(client.currentScreen() instanceof ChooseSeedsScreen,
                "resuming must not ask for a card bar that the save already has");
        assertTrue(fixture.sentPackets().stream()
                        .filter(ContinueLevelC2S.class::isInstance)
                        .map(ContinueLevelC2S.class::cast)
                        .anyMatch(request -> request.levelId().equals("pvzce:level_1")),
                "entering an in-progress level asks the server to load it, never to restart it");
    }

    /** A level with nothing to resume still picks its cards first, exactly as before. */
    @Test
    void aFreshLevelStillGoesThroughTheSeedChooser() throws Exception {
        ClientHarness fixture = newClient();
        PvzceClient client = fixture.client();
        LevelListS2C.LevelInfo info = levelInfo("pvzce:level_1", "");
        client.setLevelList(List.of(info));

        client.enterLevelFromMenu(info);

        assertInstanceOf(ChooseSeedsScreen.class, client.currentScreen(),
                "a level with no save is started by choosing its cards");
        assertTrue(fixture.sentPackets().stream().noneMatch(ContinueLevelC2S.class::isInstance),
                "the seed chooser decides when the level actually starts");
    }

    /** 已通关 is not a run to resume: the save was cleared when the level was finished. */
    @Test
    void aCompletedLevelIsAFreshRun() throws Exception {
        PvzceClient client = newClient().client();
        LevelListS2C.LevelInfo info = levelInfo("pvzce:level_1", LevelListS2C.LevelInfo.COMPLETED);
        client.setLevelList(List.of(info));

        client.enterLevelFromMenu(info);

        assertInstanceOf(ChooseSeedsScreen.class, client.currentScreen(),
                "a completed level is replayed from a fresh card selection");
    }

    /**
     * A cleared level whose replay was abandoned is both: cleared <em>and</em> resumable.
     *
     * <p>The two facts are separate fields for exactly this case. Reading the save out of
     * the status string sent the player through the seed chooser, and the abandoned run only
     * announced itself in the continue/restart dialog that appeared <em>after</em> they had
     * submitted their cards - so the run they were resuming had already been replaced by the
     * bar they just picked.
     */
    @Test
    void aClearedLevelWithAnAbandonedReplayResumesInsteadOfChoosingCards() throws Exception {
        ClientHarness fixture = newClient();
        PvzceClient client = fixture.client();
        LevelListS2C.LevelInfo info = levelInfo("pvzce:level_1",
                LevelListS2C.LevelInfo.COMPLETED, true);
        client.setLevelList(List.of(info));

        client.enterLevelFromMenu(info);

        assertFalse(client.currentScreen() instanceof ChooseSeedsScreen,
                "a run is waiting, so the card bar must not be chosen again");
        assertTrue(fixture.sentPackets().stream()
                        .filter(ContinueLevelC2S.class::isInstance)
                        .map(ContinueLevelC2S.class::cast)
                        .anyMatch(request -> request.levelId().equals("pvzce:level_1")),
                "the abandoned run is loaded, and the question is asked over it");
    }

    /**
     * A level that offers one side skips 关卡准备.
     *
     * <p>The choice the screen asks for is the level's to declare ({@code playable_teams}), and
     * one playable team is not a choice: the entry flow goes straight on to the seed chooser,
     * exactly as if the player had clicked the only panel there was. Every built-in level
     * declares the plant side alone, so this is what a player actually sees.
     */
    @Test
    void aLevelWithOnePlayableTeamSkipsTheTeamScreen() throws Exception {
        ClientHarness fixture = newClient();
        PvzceClient client = fixture.client();
        LevelListS2C.LevelInfo info = levelInfo("pvzce:level_1", "");
        client.setLevelList(List.of(info));

        client.enterLevelFromMenu(info);

        assertInstanceOf(ChooseSeedsScreen.class, client.currentScreen(),
                "one playable side is not a question, so nothing asks it");
    }

    /** Two playable sides do get the screen: that is the level saying the choice is real. */
    @Test
    void aLevelWithTwoPlayableTeamsAsksWhichOne() throws Exception {
        ClientHarness fixture = newClient();
        PvzceClient client = fixture.client();
        LevelPayload payload = new LevelPayload(9, 5,
                List.of(new SeedOption("pvzce:pea_shooter", "plant", "pvzce:pea_shooter",
                        "pvzce:textures/entities/pea_shooter", 100)),
                6, List.of("pvzce:basic_zombie"),
                List.of(new SceneSyncS2C.Cell(0, 0, "pvzce:grass")), List.of(), List.of());
        LevelListS2C.LevelInfo info = LevelListS2C.LevelInfo.of("pvzce:level_1", "对战", "描述",
                "pvzce:plant_team",
                List.of(new LevelListS2C.TeamInfo("pvzce:plant_team", "植物方", "survive_waves", true),
                        new LevelListS2C.TeamInfo("pvzce:zombie_team", "僵尸方", "plant_side_lost", true)),
                "", "day", "pvzce:yard", "pvzce:adventure", false, payload,
                LevelListS2C.UnlockInfo.OPEN);
        client.setLevelList(List.of(info));

        client.enterLevelFromMenu(info);

        assertInstanceOf(LevelSetupScreen.class, client.currentScreen(),
                "two sides is a question the player has to answer");
    }

    /**
     * The cached list belongs to one world; entering another must not keep showing (or acting
     * on) the previous world's 进行中 labels.
     */
    @Test
    void changingWorldDropsTheCachedLevelList() throws Exception {
        PvzceClient client = newClient().client();
        client.setLevelList(List.of(levelInfo("pvzce:level_1", LevelListS2C.LevelInfo.IN_PROGRESS)));
        client.setCurrentWorld("demo");

        assertTrue(client.levelList().isEmpty(), "another world's save labels must not survive");

        client.setLevelList(List.of(levelInfo("pvzce:level_1", LevelListS2C.LevelInfo.IN_PROGRESS)));
        client.setCurrentWorld("demo");
        assertFalse(client.levelList().isEmpty(), "re-setting the same world keeps the list");
    }
}
