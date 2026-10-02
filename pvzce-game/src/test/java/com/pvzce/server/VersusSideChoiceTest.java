package com.pvzce.server;

import com.pvzce.common.PvzceIds;
import com.pvzce.common.network.packet.LevelInitS2C;
import com.pvzce.common.network.packet.LevelListS2C;
import com.pvzce.common.network.packet.PlayLevelC2S;
import com.pvzce.common.network.packet.RequestLevelListC2S;
import com.pvzce.common.network.packet.SlotInfo;
import com.pvzce.testutil.ServerHarness;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Picking a side, through the real protocol.
 *
 * <p>The one thing a client can say about a versus match before it starts is which end it is playing
 * from, and it says it in the entry packet ({@code PlayLevelC2S.humanTeam}). This drives that packet
 * through a real server and reads the answer back out of {@code LevelInitS2C}: the seat
 * ({@code controlledTeamId}) and the bar (the side's own cards). Everything about the choice is one
 * fact travelling one way, so a test that only checked the seat could still ship a match where the
 * zombie side was handed the plant cards.
 */
class VersusSideChoiceTest {
    private static final String LEVEL = "pvzce:yard/versus/duel_1";

    @Test
    void theChosenSideSeatsThePlayerAndFillsTheirBar(@TempDir Path gameDir) throws Exception {
        try (ServerHarness server = ServerHarness.createWithWorld(gameDir, "world", true)) {
            // The level offers both sides, which is what makes the choice a question at all.
            server.send(new RequestLevelListC2S("world"));
            LevelListS2C list = server.awaitPacket(LevelListS2C.class, 5_000);
            LevelListS2C.LevelInfo info = list.levels().stream()
                    .filter(level -> LEVEL.equals(level.id()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(LEVEL + " is in the list"));
            assertEquals(List.of(PvzceIds.PLANT_TEAM.toString(), PvzceIds.ZOMBIE_TEAM.toString()),
                    info.teams().stream().filter(LevelListS2C.TeamInfo::playable)
                            .map(LevelListS2C.TeamInfo::id).toList(),
                    "a versus level offers both ends");

            // The zombie side, with no cards chosen: a versus level's deck is its own.
            server.send(new PlayLevelC2S(LEVEL, "world", true, List.of(), List.of(),
                    PvzceIds.ZOMBIE_TEAM.toString()));
            LevelInitS2C init = server.awaitPacket(LevelInitS2C.class, 10_000);

            assertEquals(PvzceIds.ZOMBIE_TEAM.toString(), init.controlledTeamId(),
                    "the player is seated on the side they picked");
            List<String> cards = init.slots().stream().map(SlotInfo::defId).toList();
            assertTrue(cards.contains("pvzce:basic_zombie"),
                    "the zombie side's bar holds zombie cards: " + cards);
            assertFalse(cards.contains("pvzce:sunflower"),
                    "and not the plant side's: " + cards);
            // The sun card is not in a zombie deck: a zombie player's sun is the mode's, not a
            // pickup, and a bar slot for it would be a card that can never do anything.
            assertFalse(cards.contains("pvzce:sun"), "no collection card on the zombie side");
            assertEquals(PvzceIds.ZOMBIE_TEAM, server.server().level().humanTeamId());
        }
    }

    @Test
    void theOtherSideGetsTheOtherBar(@TempDir Path gameDir) throws Exception {
        try (ServerHarness server = ServerHarness.createWithWorld(gameDir, "world", true)) {
            server.send(new PlayLevelC2S(LEVEL, "world", true, List.of(), List.of(),
                    PvzceIds.PLANT_TEAM.toString()));
            LevelInitS2C init = server.awaitPacket(LevelInitS2C.class, 10_000);

            assertEquals(PvzceIds.PLANT_TEAM.toString(), init.controlledTeamId());
            List<String> cards = init.slots().stream().map(SlotInfo::defId).toList();
            assertTrue(cards.contains("pvzce:sun"),
                    "the plant side needs the collection card to race for its goal: " + cards);
            assertTrue(cards.contains("pvzce:sunflower"), cards.toString());
            assertFalse(cards.contains("pvzce:basic_zombie"), cards.toString());
        }
    }

    @Test
    void noAnswerMeansTheLevelsOwnDefault(@TempDir Path gameDir) throws Exception {
        try (ServerHarness server = ServerHarness.createWithWorld(gameDir, "world", true)) {
            // What a caller that never showed the setup screen sends - the smoke hook, the editor's
            // 测试. The level's first playable side stands.
            server.requestLevel(LEVEL, "world", true);
            LevelInitS2C init = server.awaitPacket(LevelInitS2C.class, 10_000);
            assertEquals(PvzceIds.PLANT_TEAM.toString(), init.controlledTeamId());
        }
    }
}
