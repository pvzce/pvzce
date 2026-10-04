package com.pvzce.server;

import com.pvzce.api.content.LevelDef;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.level.SceneBoard;
import com.pvzce.common.network.packet.GameStateS2C;
import com.pvzce.common.tag.TestContent;
import com.pvzce.server.entity.ZombieEntity;
import com.pvzce.server.level.LevelServer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;

/** Water overlays, reserved respawn cells and the finite run's observable pacing. */
class IslandRaidersTest {
    private static LevelDef definition;
    private static final LevelServer.ServerBridge BRIDGE = packet -> {};
    private static Identifier id(String path) { return PvzceIds.id(path); }

    @BeforeAll
    static void load() throws Exception {
        TestContent.loadBuiltInContentAndTags();
        definition = BuiltInRegistries.LEVELS.get(id("yard/minigame/island_raiders"));
        assertNotNull(definition);
    }

    private static void tick(LevelServer level, int count) {
        for (int i = 0; i < count; i++) {
            level.tick(BRIDGE);
            level.flushPending(BRIDGE);
        }
    }

    @Test
    void waterGravesKeepTheSeaAndRejectLilyPadsWhileAcceptingGraveBusters() {
        LevelServer level = new LevelServer(definition, 41L);
        assertEquals(6, level.graveCells().size());
        assertTrue(definition.initialEntities().isEmpty(), "players build their own defence");
        for (var grave : level.graveCells()) {
            assertEquals(id("water"), level.sceneBoard().cell(SceneBoard.DEFAULT_SURFACE, grave.x(), grave.y()).base());
            assertFalse(level.canPlacePlant(BuiltInRegistries.PLANTS.get(id("lily_pad")), grave.x(), grave.y()));
            assertFalse(level.canPlacePlant(BuiltInRegistries.PLANTS.get(id("tangle_kelp")), grave.x(), grave.y()));
            assertTrue(level.canPlacePlant(BuiltInRegistries.PLANTS.get(id("grave_buster")), grave.x(), grave.y()));
        }
        assertFalse(level.placeGrave(id("grave"), 3, 0), "ordinary graves must remain land-only");
        var swimmer = level.raiseZombieFromGrave(id("ducky_tube_zombie"), 4, 0);
        tick(level, 65);
        assertTrue(swimmer.isAlive());
        assertTrue(swimmer.cellX() < 4.5F, "a risen swimmer must resume moving across the sea");
        assertEquals(0, swimmer.riseTicks());
    }

    @Test
    void clearingAndResumingAGraveKeepsItsWaterAndOnlyTheRemainingRespawnDelay() {
        LevelServer level = new LevelServer(definition, 41L);
        assertTrue(level.clearGrave(4, 0));
        assertEquals(id("water"), level.sceneIdAt(4, 0));
        assertFalse(level.canPlacePlant(BuiltInRegistries.PLANTS.get(id("lily_pad")), 4, 0), "the source remains reserved");
        tick(level, 240);
        var save = level.save();
        LevelServer resumed = new LevelServer(definition, 41L);
        resumed.restore(save);
        tick(resumed, 359);
        assertFalse(resumed.isGrave(4, 0));
        tick(resumed, 1);
        assertTrue(resumed.isGrave(4, 0));
        assertEquals(id("water"), resumed.sceneBoard().cell(SceneBoard.DEFAULT_SURFACE, 4, 0).base());
    }

    @Test
    void aFullRunHasMostlyGraveEnemiesAndStopsSpawningForTheFinalClear() {
        LevelServer level = new LevelServer(definition, 41L);
        var seen = new HashSet<Integer>();
        int graveCount = 0, roadCount = 0, first = -1;
        int[] lanes = new int[6];
        // Remove each new zombie to measure authored supply, independent of a chosen defence.
        // This also exercises the real wave director's empty-board pacing and ending.
        for (int t = 0; t < 16000 && GameStateS2C.RUNNING.equals(level.gameState()); t++) {
            tick(level, 1);
            for (var entity : level.entities()) {
                if (!(entity instanceof ZombieEntity zombie) || !seen.add(entity.id())) continue;
                if (first < 0) first = level.tickCount();
                if (zombie.riseTicks() > 0) graveCount++; else roadCount++;
                lanes[zombie.gridY()]++;
                zombie.remove();
            }
        }
        double share = graveCount / (double) (graveCount + roadCount);
        System.out.printf("ISLAND supply: first=%.2fs, end=%.2fs, graves=%d, road=%d, graveShare=%.2f%%, lanes=%s%n",
                first / 60.0, level.tickCount() / 60.0, graveCount, roadCount, share * 100, java.util.Arrays.toString(lanes));
        assertEquals(1800, first, "players must get the promised 30 seconds to build");
        assertTrue(share > .75 && share < .85, "the sea's central sources must carry most pressure");
        for (int lane : lanes) assertTrue(lane > 0, "every shore lane must be threatened");
        assertEquals(GameStateS2C.WON, level.gameState());
        assertTrue(level.tickCount() <= 15000, "an empty final field must finish instead of refilling forever");
    }

}
