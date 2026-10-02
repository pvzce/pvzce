package com.pvzce.server;

import com.pvzce.api.content.LevelDef;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.jev.AiSettings;
import com.pvzce.common.network.PvzcePacket;
import com.pvzce.common.tag.TestContent;
import com.pvzce.server.ai.JevBrain;
import com.pvzce.server.entity.ZombieEntity;
import com.pvzce.server.level.LevelServer;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The real endpoint, driving a real level. Skipped unless a credential is supplied.
 *
 * <p>Everything else about Jev is tested against a local stand-in, because a test suite may not
 * call a paid service. This one exists because that leaves one question no stub can answer: whether
 * the request this game builds is a request the service accepts, and whether what comes back is
 * what {@code JevDecision} expects. The two bodies captured from the live endpoint are the fixtures
 * in {@code JevProtocolTest}; this is the same claim, end to end, against the thing itself.
 *
 * <p>Supply a credential through the launch arguments or the environment
 * ({@code -Dpvzce.jev.key=...} / {@code PVZCE_JEV_KEY=...}, with the URL and model beside it) and it
 * runs; otherwise it skips, which is what a build machine does. It drives the shipped duel_1 with
 * the player on the plant side, so the opponent is the zombie side, and asks for a decision
 * every three seconds of game time until one has been played.
 */
class JevLiveTest {
    private static final int MAX_TICKS = 60 * 60 * 4;

    @BeforeAll
    static void load() throws Exception {
        TestContent.loadBuiltInContentAndTags();
    }

    @Test
    void aRealEndpointPlaysTheOpponent() {
        AiSettings settings = AiSettings.jev();
        Assumptions.assumeTrue(settings.configured(),
                "no Jev credential supplied; see AiSettings.PROPERTY_KEY / ENV_KEY");
        // The commander is optional here on purpose: without a credential this is the same test it
        // has always been (one tier, end to end), and with one it also proves the two tiers talk.
        AiSettings commander = AiSettings.commander();

        LevelDef def = BuiltInRegistries.LEVELS.get(
                Identifier.withDefaultNamespace("yard/versus/duel_1"));
        assertTrue(def != null, "the shipped versus level is registered");
        LevelServer level = new LevelServer(def, def.slots(), LevelServer.SeedContext.all(def), null,
                null, Identifier.parse("pvzce:plant_team"));
        level.setAiSettings(settings);
        level.setCommanderSettings(commander);
        CapturingBridge bridge = new CapturingBridge();
        level.tick(bridge);

        // Paced by the wall clock as well as by the tick count, and that is the point: the
        // simulation runs thousands of ticks per second while a real round trip takes about one
        // second, so a loop that only counted ticks would burn the whole match in a few
        // milliseconds and conclude, wrongly, that nothing ever answered.
        int ticks = 0;
        long deadline = System.nanoTime() + 120_000_000_000L;
        while (ticks < MAX_TICKS && System.nanoTime() < deadline
                && level.jevBrain().status() != JevBrain.Status.JEV
                && "running".equals(level.gameState())) {
            for (int i = 0; i < 30; i++) {
                level.tick(bridge);
                ticks++;
            }
            try {
                Thread.sleep(5L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        System.out.println("JEV-LIVE: ticks=" + ticks + " status=" + level.jevBrain().status()
                + " last=" + level.jevBrain().lastCardId() + " at "
                + level.jevBrain().lastRow() + "," + level.jevBrain().lastColumn()
                + " commander=" + (commander.configured() ? "configured" : "none")
                + " plan=" + (level.jevBrain().commanderPlan().isEmpty()
                        ? "<none yet>" : level.jevBrain().commanderPlan()));
        if (commander.configured()) {
            assertFalse(level.jevBrain().commanderPlan().isEmpty(),
                    "the commander answered within the same run (it is asked after thirty seconds "
                            + "of game time, and this loop paced itself to the wall clock)");
        }
        assertEquals(JevBrain.Status.JEV, level.jevBrain().status(),
                "the real endpoint answered with a move this level could execute");
        assertTrue(level.jevBrain().lastCardId().startsWith("pvzce:"),
                "the move named a card: " + level.jevBrain().lastCardId());
        assertTrue(level.entities().stream().anyMatch(ZombieEntity.class::isInstance),
                "and it is on the board");
    }

    private static final class CapturingBridge implements LevelServer.ServerBridge {
        final List<PvzcePacket> packets = new ArrayList<>();

        @Override
        public void send(PvzcePacket packet) {
            packets.add(packet);
        }
    }
}
