package com.pvzce.client;

import com.pvzce.common.PvzceIds;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.level.mechanic.OutpostsMechanic;
import com.pvzce.common.level.mechanic.StagesMechanic;
import com.pvzce.common.network.packet.MechanicSyncS2C;
import com.pvzce.common.tag.TestContent;
import com.pvzce.server.level.LevelServer;
import com.pvzce.testutil.ClientHarness;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** State updates must preserve the running board and agree with the server's frontier. */
class IslandClientFlowTest {
    @Test
    void mirroredOccupationOpensOnlyItsSurfaceAndPhaseSyncReplacesBuffsWithoutResettingTheBoard() throws Exception {
        TestContent.loadBuiltInContentAndTags();
        var def = BuiltInRegistries.LEVELS.get(PvzceIds.id("yard/minigame/tidal_fortress"));
        try (var harness = ClientHarness.create("pvzce-island-flow")) {
            var client = harness.client();
            var level = client.level();
            var listener = new PvzceClientPacketListener(client, level);
            new LevelServer(def, 29L).sendFullState(listener::handle);
            level.setResource(PvzceIds.PLANT_TEAM, PvzceIds.SUN, 725);
            listener.handle(MechanicSyncS2C.of(PvzceIds.MECHANIC_OUTPOSTS, OutpostsMechanic.Status.CODEC,
                    new OutpostsMechanic.Status(List.of(new OutpostsMechanic.PointStatus(1200, true, 0),
                            new OutpostsMechanic.PointStatus(1200, true, 3)))));
            assertTrue(level.inPlacementZone(7, 1));
            assertFalse(level.inPlacementZone(8, 1));
            level.cycleSurface(1);
            assertTrue(level.inPlacementZone(8, 3));
            assertFalse(level.inPlacementZone(9, 3));
            var board = level.sceneBoard();
            listener.handle(MechanicSyncS2C.of(PvzceIds.MECHANIC_STAGES, StagesMechanic.Status.CODEC,
                    new StagesMechanic.Status(1, 6000, false, false, List.of(PvzceIds.BUFF_AUTO_COLLECT.toString()))));
            assertSame(board, level.sceneBoard());
            assertEquals(725, level.resource(PvzceIds.PLANT_TEAM, PvzceIds.SUN));
            assertEquals(List.of(PvzceIds.BUFF_AUTO_COLLECT.toString()), level.activeBuffs());
            listener.handle(MechanicSyncS2C.of(PvzceIds.MECHANIC_OUTPOSTS, OutpostsMechanic.Status.CODEC,
                    new OutpostsMechanic.Status(List.of(new OutpostsMechanic.PointStatus(0, false, 0),
                            new OutpostsMechanic.PointStatus(0, false, 3)))));
            assertFalse(level.inPlacementZone(8, 3));
            assertTrue(level.inPlacementZone(6, 3), "the anchor itself remains plantable for recapture");
        }
    }
}
