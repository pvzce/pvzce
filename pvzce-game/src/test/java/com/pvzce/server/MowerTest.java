package com.pvzce.server;

import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.LevelRewards;
import com.pvzce.api.content.LevelUnlock;
import com.pvzce.api.content.MowerData;
import com.pvzce.api.content.TeamDef;
import com.pvzce.api.content.mechanic.TypedMechanic;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceConstants;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.level.mechanic.MowerMechanic;
import com.pvzce.common.network.PvzcePacket;
import com.pvzce.common.network.packet.EffectEventS2C;
import com.pvzce.common.network.packet.GameStateS2C;
import com.pvzce.common.network.packet.MechanicSyncS2C;
import com.pvzce.common.tag.TestContent;
import com.pvzce.server.entity.ZombieEntity;
import com.pvzce.server.level.LevelServer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lawn mowers: one per row by default, spent once, and the row is open afterwards.
 *
 * <p>What these pin is the rule the original plays by, because every part of it is a design
 * statement rather than an implementation detail: the mower is the <em>first</em> zombie's
 * problem and not the second's, it destroys what walks and not what flies or digs, and a
 * level that wants none says so.
 */
class MowerTest {
    private static final Identifier PLANT_TEAM = Identifier.withDefaultNamespace("plant_team");
    private static final Identifier ZOMBIE_TEAM = Identifier.withDefaultNamespace("zombie_team");
    private static final Identifier BASIC_ZOMBIE = Identifier.withDefaultNamespace("basic_zombie");
    private static final Identifier BUCKETHEAD = Identifier.withDefaultNamespace("buckethead_zombie");
    private static final Identifier BALLOON = Identifier.withDefaultNamespace("balloon_zombie");

    @BeforeAll
    static void load() throws Exception {
        TestContent.loadBuiltInContentAndTags();
    }

    @Test
    void everyRowHasAMowerWithoutTheLevelAsking() {
        // "Silence means every row" is asserted against the mechanic registry in
        // LevelMechanicTest::anOrdinaryLevelFallsBackToTheDeck; this is what the server makes
        // of it - one sync per level, every row parked at the same spot.
        LevelServer level = new LevelServer(level(List.of()));
        CapturingBridge bridge = new CapturingBridge();
        level.tick(bridge);
        MowerMechanic.State state = bridge.mowerState;
        assertNotNull(state, "the mowers are announced on the first tick");
        assertEquals(5, state.rows().size(), "one per row of the default block, all five of them");
        assertEquals(List.of(0, 1, 2, 3, 4), MowerData.EVERY_ROW.rowsFor(5));
        for (MowerMechanic.Row row : state.rows()) {
            assertEquals(MowerMechanic.STATE_READY, row.state());
            assertEquals(MowerMechanic.IDLE_X, row.x(), 0.0001F);
        }
    }

    @Test
    void theFirstZombieToReachTheHouseIsMowedInsteadOfEndingTheLevel() {
        LevelServer level = new LevelServer(level(List.of()));
        CapturingBridge bridge = new CapturingBridge();
        ZombieEntity zombie = spawn(level, bridge, BASIC_ZOMBIE, 1.0F, 2);

        assertTrue(tickUntil(level, bridge, () -> zombie.isDying(), 2_000),
                "the mower has to reach the zombie walking into it");
        assertEquals(GameStateS2C.RUNNING, level.gameState(),
                "a mower is the last line of defence, not a loss");
        assertEquals(0, level.aliveZombieCount());
        assertTrue(bridge.packets.stream().anyMatch(packet -> packet instanceof EffectEventS2C effect
                        && com.pvzce.common.PvzceSounds.EFFECT_LAWNMOWER.toString().equals(effect.sound())),
                "and it announces itself with the original's sound");
        assertEquals(MowerMechanic.STATE_ROLLING, mowerRow(bridge, 2).state(),
                "the mower is on its way across the row it just saved");
    }

    @Test
    void aMowerDestroysArmourWithTheZombieWearingIt() {
        LevelServer level = new LevelServer(level(List.of()));
        CapturingBridge bridge = new CapturingBridge();
        // A buckethead: 200 health behind 1100 points of armour. A projectile would need
        // three hits; the mower is not a projectile.
        ZombieEntity zombie = spawn(level, bridge, BUCKETHEAD, 1.0F, 1);

        assertTrue(tickUntil(level, bridge, () -> zombie.isRemoved(), 2_000),
                "one pass has to be enough for a buckethead");
        assertEquals(GameStateS2C.RUNNING, level.gameState());
    }

    @Test
    void aSpentMowerLeavesItsRowOpen() {
        LevelServer level = new LevelServer(level(List.of()));
        CapturingBridge bridge = new CapturingBridge();
        ZombieEntity first = spawn(level, bridge, BASIC_ZOMBIE, 1.0F, 2);
        assertTrue(tickUntil(level, bridge, () -> first.isRemoved(), 2_000));
        // Let it finish the row and leave the board: it is gone, not parked at the far end.
        assertTrue(tickUntil(level, bridge,
                () -> mowerRow(bridge, 2).state() == MowerMechanic.STATE_USED, 1_200),
                "the mower has to leave the board when it is done");
        assertTrue(mowerRow(bridge, 2).x() > level.width(),
                "and the x it stopped at is past the last column");

        ZombieEntity second = spawn(level, bridge, BASIC_ZOMBIE, 1.0F, 2);
        assertTrue(tickUntil(level, bridge,
                        () -> !GameStateS2C.RUNNING.equals(level.gameState()), 2_000),
                "the second zombie in that row has nothing left to stop it");
        assertEquals(PvzceIds.ZOMBIE_TEAM, level.winner());
        assertFalse(second.isRemoved(), "nothing mowed it: the row was open");
    }

    @Test
    void rowsTheLevelDidNotListHaveNoMower() {
        LevelServer level = new LevelServer(level(List.of(2)));
        CapturingBridge bridge = new CapturingBridge();
        level.tick(bridge);
        assertEquals(1, bridge.mowerState.rows().size(), "row 2 only");

        ZombieEntity zombie = spawn(level, bridge, BASIC_ZOMBIE, 1.0F, 0);
        assertTrue(tickUntil(level, bridge,
                        () -> !GameStateS2C.RUNNING.equals(level.gameState()), 2_000),
                "row 0 has no mower, so the level ends as it did before mowers existed");
        assertFalse(zombie.isRemoved());
    }

    @Test
    void aLevelCanDeclareThatItHasNoMowersAtAll() {
        LevelServer level = new LevelServer(level(List.of(), true));
        CapturingBridge bridge = new CapturingBridge();
        level.tick(bridge);
        assertEquals(List.of(), bridge.mowerState.rows(), "an empty row list means none, not all");

        level.spawnZombie(BASIC_ZOMBIE, level.team(ZOMBIE_TEAM), 1.0F, 0);
        level.flushPending(bridge);
        assertTrue(tickUntil(level, bridge,
                () -> !GameStateS2C.RUNNING.equals(level.gameState()), 2_000));
    }

    @Test
    void whatFliesAndWhatDigsIsNotMowed() {
        LevelServer level = new LevelServer(level(List.of()));
        CapturingBridge bridge = new CapturingBridge();
        // A balloon zombie flies over the mower; the mower is a ground machine, and the
        // original's answer for fliers is the player's, not the lawn's.
        ZombieEntity flier = spawn(level, bridge, BALLOON, 1.0F, 3);
        assertTrue(tickUntil(level, bridge,
                        () -> !GameStateS2C.RUNNING.equals(level.gameState()), 2_000),
                "a flier reaching the house still ends the level");
        assertFalse(flier.isRemoved(), "and the mower never touched it");
        assertEquals(MowerMechanic.STATE_READY, mowerRow(bridge, 3).state(),
                "it did not even start");
    }

    @Test
    void aSpentMowerIsStillSpentAfterASave() {
        LevelServer level = new LevelServer(level(List.of()));
        CapturingBridge bridge = new CapturingBridge();
        ZombieEntity zombie = spawn(level, bridge, BASIC_ZOMBIE, 1.0F, 4);
        assertTrue(tickUntil(level, bridge, () -> zombie.isRemoved(), 2_000));
        assertTrue(tickUntil(level, bridge,
                () -> mowerRow(bridge, 4).state() == MowerMechanic.STATE_USED, 1_200));
        float spentAt = mowerRow(bridge, 4).x();

        LevelServer restored = new LevelServer(level(List.of()));
        restored.restore(level.save());
        CapturingBridge restoredBridge = new CapturingBridge();
        restored.tick(restoredBridge);
        MowerMechanic.Row row = mowerRow(restoredBridge, 4);
        assertEquals(MowerMechanic.STATE_USED, row.state(), "a used mower must not come back");
        assertEquals(spentAt, row.x(), 0.0001F);
        assertEquals(MowerMechanic.STATE_READY, mowerRow(restoredBridge, 0).state(),
                "and the rows that were never used are still ready");
    }

    // ------------------------------------------------------------------
    // Harness
    // ------------------------------------------------------------------

    /** A 9x5 level with no waves: the test drives the simulation, not the wave clock. */
    private static LevelDef level(List<Integer> mowerRows) {
        return level(mowerRows, false);
    }

    /** As above, where an empty {@code mowerRows} can mean "none" instead of "declared none". */
    private static LevelDef level(List<Integer> mowerRows, boolean declareNone) {
        LevelDef demo = BuiltInRegistries.LEVELS.get(
                Identifier.withDefaultNamespace("yard/adventure/demo_level"));
        List<TypedMechanic> mechanics = new java.util.ArrayList<>();
        if (!mowerRows.isEmpty() || declareNone) {
            mechanics.add(TypedMechanic.of(PvzceIds.MECHANIC_MOWER,
                    new MowerData(Optional.of(mowerRows))));
        }
        return new LevelDef(
                Identifier.withDefaultNamespace("mower_test"), "小推车测试", "", 9, 5,
                demo.scene(),
                List.of(new TeamDef(PLANT_TEAM, "植物方", "survive_waves"),
                        new TeamDef(ZOMBIE_TEAM, "僵尸方", "plant_side_lost")),
                PLANT_TEAM, Map.of(), Map.of(), List.of(), 1F,
                List.of(Identifier.withDefaultNamespace("pea_shooter"), Identifier.withDefaultNamespace("sun")),
                Map.of(), PvzceConstants.INITIAL_SUN, LevelDef.LevelMusicDef.DEFAULT, List.of(),
                6, LevelRewards.NONE, LevelUnlock.NONE, mechanics,
                com.pvzce.api.content.LevelDialogue.EMPTY);
    }

    @Test
    void thePlayerCanSendAParkedMowerByHand() {
        // The original lets a held mouse send a mower early, and it is a real decision: one
        // spent on a wave that was already handled is a row that is open for the rest of the
        // level. So the hand release has to be the *same* mower, with the same sound and the
        // same consequences - not a second, weaker kind of mower.
        LevelServer level = new LevelServer(level(List.of()));
        CapturingBridge bridge = new CapturingBridge();
        level.tick(bridge);

        assertTrue(level.releaseMower(3), "a parked mower goes when asked");
        assertEquals(MowerMechanic.STATE_READY, mowerRow(bridge, 3).state(),
                "the state change is only on the wire after the next tick");

        level.tick(bridge);
        assertEquals(MowerMechanic.STATE_ROLLING, mowerRow(bridge, 3).state());
        assertEquals(4, level.readyMowerCount(), "and it is no longer one of the survivors");
        assertTrue(bridge.packets.stream().anyMatch(packet -> packet instanceof EffectEventS2C effect
                        && com.pvzce.common.PvzceSounds.EFFECT_LAWNMOWER.toString().equals(effect.sound())),
                "it announces itself exactly like the automatic one");
    }

    @Test
    void aMowerGoesOnlyOnce() {
        // Two packets for one row - a double click, a replayed request, a held button -
        // must not produce two mowers, and a row that has none at all must not invent one.
        LevelServer level = new LevelServer(level(List.of(0, 4)));
        CapturingBridge bridge = new CapturingBridge();
        level.tick(bridge);

        assertTrue(level.releaseMower(0));
        assertFalse(level.releaseMower(0), "the same row cannot go twice");
        assertFalse(level.releaseMower(2), "and a row with no mower has nothing to send");
        assertFalse(level.releaseMower(9), "nor does a row off the board");
        level.tick(bridge);
        assertEquals(MowerMechanic.STATE_ROLLING, mowerRow(bridge, 0).state());
    }

    @Test
    void aMowerIsNotSentForAFinishedLevel() {
        // The payout counts the parked mowers as survivors, so a request that arrives after
        // the level is over must not quietly spend one of them. Row 4 has no mower, so the
        // zombie that walks in there ends the level with row 0's mower still parked - which
        // is exactly the state a late request arrives in.
        LevelServer level = new LevelServer(level(List.of(0, 1, 2, 3)));
        CapturingBridge bridge = new CapturingBridge();
        level.tick(bridge);
        ZombieEntity zombie = spawn(level, bridge, BASIC_ZOMBIE, 2.0F, 4);
        assertTrue(tickUntil(level, bridge,
                () -> !level.gameState().equals(GameStateS2C.RUNNING), 3_000),
                "the level has to end for this test to mean anything");
        assertEquals(4, level.readyMowerCount(), "the parked mowers are the survivors");

        assertFalse(level.releaseMower(0), "a finished level has no mowers to send");
        assertEquals(4, level.readyMowerCount(),
                "and the request did not quietly spend one of the survivors");
    }

    @Test
    void aHandSentMowerStillMows() {
        // The whole point: it is not a decorative launch. Sending row 2 early has to destroy
        // what later walks into it, otherwise the long press would waste a mower.
        LevelServer level = new LevelServer(level(List.of()));
        CapturingBridge bridge = new CapturingBridge();
        level.tick(bridge);
        assertTrue(level.releaseMower(2));

        // Far to the right, so the mower has to reach it rather than the zombie walking in.
        ZombieEntity zombie = spawn(level, bridge, BASIC_ZOMBIE, 6.0F, 2);
        assertTrue(tickUntil(level, bridge, () -> zombie.isDying(), 2_000),
                "a hand-sent mower mows exactly like an automatic one");
        assertEquals(GameStateS2C.RUNNING, level.gameState());
    }

    /** Spawns a zombie and hands back the entity that landed on the board. */
    private static ZombieEntity spawn(LevelServer level, CapturingBridge bridge,
                                      Identifier type, float x, int row) {
        level.spawnZombie(type, level.team(ZOMBIE_TEAM), x, row);
        level.flushPending(bridge);
        List<ZombieEntity> inRow = level.zombiesInRow(row);
        return inRow.get(inRow.size() - 1);
    }

    /** One row's mower as the client last saw it; the bridge decodes every sync it sees. */
    private static MowerMechanic.Row mowerRow(CapturingBridge bridge, int row) {
        MowerMechanic.State state = bridge.mowerState;
        assertNotNull(state, "the level has not announced its mowers");
        for (MowerMechanic.Row candidate : state.rows()) {
            if (candidate.row() == row) {
                return candidate;
            }
        }
        throw new AssertionError("no mower in row " + row + ": " + state.rows());
    }

    private static boolean tickUntil(LevelServer level, CapturingBridge bridge,
                                     BooleanSupplier condition, int maxTicks) {
        for (int i = 0; i < maxTicks; i++) {
            if (condition.getAsBoolean()) {
                return true;
            }
            level.tick(bridge);
        }
        return condition.getAsBoolean();
    }

    /** Collects packets, and decodes the mower syncs as a client would. */
    private static final class CapturingBridge implements LevelServer.ServerBridge {
        final List<PvzcePacket> packets = new ArrayList<>();
        MowerMechanic.State mowerState;

        @Override
        public void send(PvzcePacket packet) {
            packets.add(packet);
            if (packet instanceof MechanicSyncS2C sync
                    && PvzceIds.MECHANIC_MOWER.equals(sync.mechanic())) {
                mowerState = MowerMechanic.State.CODEC.decode(sync.payloadBuffer());
            }
        }
    }
}
