package com.pvzce.server;

import com.pvzce.api.content.LevelDef;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.core.SeedOptions;
import com.pvzce.common.level.SceneBoard;
import com.pvzce.common.network.packet.GameStateS2C;
import com.pvzce.common.tag.TestContent;
import com.pvzce.server.entity.ZombieEntity;
import com.pvzce.server.level.LevelServer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Mixed grave surfaces, reserved respawn cells and the finite run's observable pacing. */
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
    void theShippedLevelUsesThePlayersBackpackForItsChooserAndStartingBar() {
        assertFalse(definition.declaresMaxSeedSlots(), "this minigame must not pin eight slots");
        for (int backpackSlots : new int[]{6, 10}) {
            int resolved = definition.effectiveMaxSeedSlots(backpackSlots);
            assertEquals(backpackSlots, resolved);
            assertEquals(backpackSlots, definition.seedPlan(SeedOptions.allCards(), resolved).maxSlots());
            assertEquals(backpackSlots, definition.defaultSeedSelection(SeedOptions.allCards(), resolved).size());
        }
    }

    @Test
    void playersCanChooseOwnedBuffsWithinTheirBackpacksCapacity() {
        assertTrue(definition.offersBuffChoice());
        assertFalse(definition.buffPlan().declaresMaxBuffSlots());
        assertEquals(1, definition.effectiveMaxBuffSlots(1));
        assertEquals(3, definition.effectiveMaxBuffSlots(3));
        var choice = id("auto_collect");
        assertTrue(LevelBuffSelection.chooserPool(definition, choice::equals).stream()
                .anyMatch(option -> option.slotId().equals(choice.toString()) && option.costSun() != SeedOptions.LOCKED_OPTION));
        assertEquals(List.of(choice), LevelBuffSelection.sanitize(definition, 1, List.of(choice), choice::equals));
        assertEquals(List.of(), LevelBuffSelection.sanitize(definition, 1, List.of(choice), buff -> false));
    }

    @Test
    void rightHandIslandAndGravesPreserveTheirSurfacesAndPlacementRules() {
        LevelServer level = new LevelServer(definition, 41L);
        assertEquals(6, level.graveCells().size());
        assertTrue(definition.width() > 9, "six rows need a wider board to cover the lawn");
        for (int y = 0; y < definition.height(); y++) {
            for (int x = 0; x < definition.width(); x++) {
                boolean island = (x == 7 || x == 8) && (y == 2 || y == 3);
                assertEquals(id(x < 2 || island ? "grass" : "water"),
                        level.sceneBoard().cell(SceneBoard.DEFAULT_SURFACE, x, y).base(),
                        "only the left shore and central right-hand island are grass");
            }
        }
        assertTrue(definition.initialEntities().isEmpty(), "players build their own defence");
        int landGraves = 0;
        for (var grave : level.graveCells()) {
            assertTrue(grave.x() >= 7, "all sources must leave more room to build the defence");
            boolean land = grave.y() == 2 || grave.y() == 3;
            if (land) landGraves++;
            assertEquals(id(land ? "grass" : "water"), level.sceneBoard().cell(SceneBoard.DEFAULT_SURFACE, grave.x(), grave.y()).base());
            assertFalse(level.canPlacePlant(BuiltInRegistries.PLANTS.get(id("lily_pad")), grave.x(), grave.y()));
            assertFalse(level.canPlacePlant(BuiltInRegistries.PLANTS.get(id("tangle_kelp")), grave.x(), grave.y()));
            assertTrue(level.canPlacePlant(BuiltInRegistries.PLANTS.get(id("grave_buster")), grave.x(), grave.y()));
        }
        assertEquals(2, landGraves);
        assertTrue(level.canPlacePlant(BuiltInRegistries.PLANTS.get(id("pea_shooter")), 8, 2));
        assertTrue(level.canPlacePlant(BuiltInRegistries.PLANTS.get(id("pea_shooter")), 7, 3));
        assertFalse(level.placeGrave(id("grave"), 3, 0), "ordinary graves must remain land-only");
        var swimmer = level.raiseZombieFromGrave(id("ducky_tube_zombie"), 7, 2);
        tick(level, 220);
        assertTrue(swimmer.isAlive());
        assertTrue(swimmer.cellX() < 7, "a swimmer raised on the island must walk into the sea alive");
        assertEquals(0, swimmer.riseTicks());
    }

    @Test
    void clearingAndResumingGravesKeepsTheirLandOrWaterAndRemainingRespawnDelay() {
        LevelServer level = new LevelServer(definition, 41L);
        int[] rows = {0, 2};
        for (int row : rows) {
            assertTrue(level.clearGrave(7, row));
            assertEquals(id(row == 0 ? "water" : "grass"), level.sceneIdAt(7, row));
            assertFalse(level.canPlacePlant(BuiltInRegistries.PLANTS.get(id(row == 0 ? "lily_pad" : "pea_shooter")), 7, row), "the source remains reserved");
        }
        tick(level, 240);
        var save = level.save();
        LevelServer resumed = new LevelServer(definition, 41L);
        resumed.restore(save);
        tick(resumed, 359);
        for (int row : rows) assertFalse(resumed.isGrave(7, row));
        tick(resumed, 1);
        for (int row : rows) {
            assertTrue(resumed.isGrave(7, row));
            assertEquals(id(row == 0 ? "water" : "grass"), resumed.sceneBoard().cell(SceneBoard.DEFAULT_SURFACE, 7, row).base());
            assertEquals(id(row == 0 ? "water_grave" : "grave"), resumed.sceneIdAt(7, row));
        }
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
        assertEquals(2400, first, "players must get the promised 40 seconds to build");
        assertEquals(25, roadCount, "right-edge arrivals should remain occasional");
        assertTrue(graveCount < 110, "grave supply must be substantially reduced from the previous 208");
        assertTrue(share > .70 && share < .85, "the right-hand sources must carry most pressure");
        for (int lane : lanes) assertTrue(lane > 0, "every shore lane must be threatened");
        assertEquals(GameStateS2C.WON, level.gameState());
        assertTrue(level.tickCount() <= 16000, "an empty final field must finish instead of refilling forever");
    }

}
