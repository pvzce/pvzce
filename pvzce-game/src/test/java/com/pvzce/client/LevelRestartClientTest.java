package com.pvzce.client;

import com.pvzce.api.util.Identifier;
import com.pvzce.client.gui.Screen;
import com.pvzce.client.gui.screens.ChooseSeedsScreen;
import com.pvzce.client.gui.screens.InGameScreen;
import com.pvzce.client.gui.screens.LevelSelectScreen;
import com.pvzce.common.network.Connection;
import com.pvzce.common.network.PvzcePacket;
import com.pvzce.common.network.PvzcePackets;
import com.pvzce.common.network.packet.LevelInitS2C;
import com.pvzce.common.network.packet.LevelListS2C;
import com.pvzce.common.network.packet.LevelPayload;
import com.pvzce.common.network.packet.SceneSyncS2C;
import com.pvzce.common.network.packet.SeedOption;
import com.pvzce.common.network.packet.SlotInfo;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Restarting a level must leave nothing of the previous run behind on the client.
 *
 * <p>Reported symptom: starting a level, opening the pause menu and choosing
 * "restart" started a new level without the previous one being torn down. The
 * server side is covered by {@code ServerMenuFlowTest.restartingLevelShutsDownPreviousInstance};
 * these cases pin the client half - the level mirror and the screen stack.
 */
class LevelRestartClientTest {
    @BeforeAll
    static void register() {
        PvzcePackets.register();
    }

    private static LevelInitS2C initPacket(String levelId, String teamId, String teamName) {
        LevelPayload payload = new LevelPayload(9, 5,
                List.of(new SeedOption("pvzce:pea_shooter", "plant", "pvzce:pea_shooter",
                        "pvzce:textures/entities/pea_shooter", 100)),
                6, List.of("pvzce:basic_zombie"),
                List.of(new SceneSyncS2C.Cell(0, 0, "pvzce:grass")));
        return new LevelInitS2C(levelId,
                List.of(new SlotInfo(0, "pvzce:pea_shooter", "plant", 100, 0, true)),
                List.of("small", "final"), payload, teamId, teamName, PvzcePackets.PROTOCOL_VERSION);
    }

    /** A headless client plus a recorder for the packets it sends. */
    private record Fixture(PvzceClient client, Connection.Pair pair, List<PvzcePacket> sent) {
        /** Drains the server end so packets the client sent are recorded. */
        List<PvzcePacket> sentPackets() {
            pair.server().tick();
            return List.copyOf(sent);
        }
    }

    private static Fixture newClient() throws Exception {
        Path gameDir = Files.createTempDirectory("pvzce-restart-client");
        Connection.Pair pair = Connection.createMemoryPair();
        List<PvzcePacket> sent = new ArrayList<>();
        pair.server().setListener(sent::add);
        PvzceClient client = new PvzceClient(pair.client(), gameDir,
                Thread.currentThread().getContextClassLoader());
        return new Fixture(client, pair, sent);
    }

    /** A stand-in for any screen the player may have opened (pause menu, seed chooser). */
    private static final class DummyScreen extends Screen {
        DummyScreen(PvzceClient client) {
            super(client);
        }

        @Override
        public void render() {
        }
    }

    /**
     * A second {@code LevelInitS2C} for the same level must wipe every per-level
     * value in the mirror, not merge the new level into the old one.
     */
    @Test
    void aSecondLevelInitLeavesNoStateFromTheFirst() throws Exception {
        Fixture fixture = newClient();
        PvzceClient client = fixture.client();
        ClientLevel level = client.level();
        PvzceClientPacketListener listener = new PvzceClientPacketListener(client, level);

        listener.handle(initPacket("pvzce:level_1", "pvzce:plant_team", "植物方"));
        level.addEntity(new ClientEntity(7, "zombie", "pvzce:basic_zombie", 5.5F, 2.5F, 200,
                com.pvzce.api.entity.EntityLayers.GROUND,
                com.pvzce.api.entity.EntityAnimations.WALK, 0F, "pvzce:zombie_team"));
        level.addMessage("旧关卡的消息");
        level.setResource(Identifier.of("pvzce", "plant_team"), Identifier.of("pvzce", "sun"), 999);
        level.setWaveProgress(3, 5, 0.5F, true, true);
        assertEquals(1, level.entities().size());

        // Restart: the same level is entered again with the other team controlled.
        listener.handle(initPacket("pvzce:level_1", "pvzce:zombie_team", "僵尸方"));

        assertTrue(level.entities().isEmpty(),
                "entities from the previous run must not survive a restart");
        assertTrue(level.messages().isEmpty(),
                "messages from the previous run must not survive a restart");
        assertTrue(level.resources().isEmpty(),
                "resource balances from the previous run must not survive a restart");
        assertEquals(0, level.currentWave(), "wave progress must reset");
        assertEquals(0F, level.waveProgress(), 0.0001F);
        assertEquals("pvzce:zombie_team", level.controlledTeam(),
                "the team carried by the new level state must be applied");
        assertEquals("僵尸方", level.controlledTeamName());
        assertEquals(1, level.slots().size(), "the card bar must be replaced, not appended to");
    }

    /**
     * The restart screen must replace the whole screen stack. The pause menu pushes
     * the seed chooser on top of the running level, so a "replace" that only popped
     * the top screen would leave the old in-game screen (and its pause dialog) alive
     * underneath the new level.
     */
    @Test
    void aRestartReplacesTheWholeScreenStack() throws Exception {
        PvzceClient client = newClient().client();
        PvzceClientPacketListener listener = new PvzceClientPacketListener(client, client.level());

        listener.handle(initPacket("pvzce:level_1", "pvzce:plant_team", "植物方"));
        assertInstanceOf(InGameScreen.class, client.currentScreen());

        // Pause menu pushes the seed chooser over the running game.
        DummyScreen seedChooser = new DummyScreen(client);
        client.openScreen(seedChooser);
        assertEquals(seedChooser, client.currentScreen());

        listener.handle(initPacket("pvzce:level_1", "pvzce:plant_team", "植物方"));

        assertInstanceOf(InGameScreen.class, client.currentScreen(),
                "the new level must be shown on a fresh in-game screen");
        assertEquals(1, client.screenDepth(),
                "the old in-game screen and the seed chooser must be gone, not stacked under the new one");
    }

    /**
     * The pause menu's 重新开始 must close the running level and then enter the normal
     * seed-chooser flow - not layer the chooser on top of the live level.
     *
     * <p>Reported symptom: pause menu -> 重新开始 -> seed chooser, then ESC dropped the
     * player straight back into the old level's pause menu, because that level had never
     * been closed.
     */
    @Test
    void pauseMenuRestartClosesTheLevelBeforeChoosingSeeds() throws Exception {
        Fixture fixture = newClient();
        PvzceClient client = fixture.client();
        ClientLevel level = client.level();
        PvzceClientPacketListener listener = new PvzceClientPacketListener(client, level);

        listener.handle(initPacket("pvzce:level_1", "pvzce:plant_team", "植物方"));
        // The level list snapshot the pause menu needs to rebuild the chooser.
        client.setLevelList(List.of(levelInfo("pvzce:level_1")));
        level.addEntity(new ClientEntity(5, "plant", "pvzce:pea_shooter", 1.5F, 2.5F, 300,
                com.pvzce.api.entity.EntityLayers.PLANT,
                com.pvzce.api.entity.EntityAnimations.IDLE, 0F, "pvzce:plant_team"));
        assertInstanceOf(InGameScreen.class, client.currentScreen());

        client.restartCurrentLevel();

        // The old level must be gone, not merely covered.
        assertTrue(level.entities().isEmpty(), "the closed level's field must be cleared");
        assertTrue(fixture.sentPackets().stream()
                        .anyMatch(com.pvzce.common.network.packet.LeaveLevelC2S.class::isInstance),
                "restarting must tell the server to close the running level");
        assertInstanceOf(ChooseSeedsScreen.class, client.currentScreen(),
                "restarting enters the seed chooser");
        assertEquals(1, client.screenDepth(),
                "the old in-game screen must not survive under the chooser");

        // ESC / 返回 from that chooser must go to the level list, never back into the
        // level that was just closed.
        client.currentScreen().requestClose();
        assertFalse(client.currentScreen() instanceof InGameScreen,
                "backing out must not return to the closed level's pause menu");
        assertInstanceOf(LevelSelectScreen.class, client.currentScreen());
    }

    /**
     * Testing a level from the editor must offer the seed chooser, exactly like opening it
     * from the level list.
     *
     * <p>The test button used to send {@code RequestLevelC2S} directly, which asks the server
     * to start the level - so testing skipped the chooser that the same level shows when it is
     * opened from the list. It now saves, asks for a reload and waits for the refreshed level
     * list, because the chooser has to describe the definition that was just written rather
     * than the one from before the save.
     */
    @Test
    void testingAnEditedLevelOpensTheSeedChooserAfterTheReloadedListArrives() throws Exception {
        Fixture fixture = newClient();
        PvzceClient client = fixture.client();

        client.testEditedLevel("pvzce:level_1");
        assertTrue(fixture.sentPackets().stream()
                        .anyMatch(com.pvzce.common.network.packet.CommandC2S.class::isInstance),
                "a test run asks the server to reload the saved level");
        assertFalse(client.currentScreen() instanceof ChooseSeedsScreen,
                "the chooser waits for the reloaded list; it must not open on stale data");

        // The list the server sends after the reload carries the saved definition.
        client.setLevelList(List.of(levelInfo("pvzce:level_1")));

        assertInstanceOf(ChooseSeedsScreen.class, client.currentScreen(),
                "a test run must reach the same seed chooser as opening the level from the list");
    }

    /** A level list that does not contain the edited level must not open a chooser for it. */
    @Test
    void testingALevelThatIsNotInTheListOpensNothing() throws Exception {
        Fixture fixture = newClient();
        PvzceClient client = fixture.client();

        client.testEditedLevel("pvzce:level_1");
        client.setLevelList(List.of(levelInfo("pvzce:other_level")));

        assertFalse(client.currentScreen() instanceof ChooseSeedsScreen,
                "a chooser with no options would be worse than not opening one");
    }

    private static LevelListS2C.LevelInfo levelInfo(String levelId) {
        LevelPayload payload = new LevelPayload(9, 5,
                List.of(new SeedOption("pvzce:pea_shooter", "plant", "pvzce:pea_shooter",
                        "pvzce:textures/entities/pea_shooter", 100)),
                6, List.of("pvzce:basic_zombie"),
                List.of(new SceneSyncS2C.Cell(0, 0, "pvzce:grass")));
        return LevelListS2C.LevelInfo.of(levelId, "第一关", "描述", "pvzce:plant_team",
                List.of(new LevelListS2C.TeamInfo("pvzce:plant_team", "植物方", "survive_waves")),
                "in_progress", "day", "pvzce:yard", "pvzce:adventure", true, payload,
                LevelListS2C.UnlockInfo.OPEN);
    }

    /** Exiting a level clears the mirror so the next entry starts from nothing. */
    @Test
    void leavingALevelClearsTheMirror() throws Exception {
        Fixture fixture = newClient();
        PvzceClient client = fixture.client();
        ClientLevel level = client.level();
        PvzceClientPacketListener listener = new PvzceClientPacketListener(client, level);

        listener.handle(initPacket("pvzce:level_1", "pvzce:plant_team", "植物方"));
        level.addEntity(new ClientEntity(3, "plant", "pvzce:pea_shooter", 1.5F, 2.5F, 300,
                com.pvzce.api.entity.EntityLayers.PLANT,
                com.pvzce.api.entity.EntityAnimations.IDLE, 0F, "pvzce:plant_team"));
        assertEquals(1, level.entities().size());

        client.leaveLevel();

        assertTrue(level.entities().isEmpty(), "leaving must clear the field");
        assertTrue(level.slots().isEmpty(), "leaving must clear the card bar");
        assertEquals(0, level.sun(), "leaving must clear the wallet");
    }
}
