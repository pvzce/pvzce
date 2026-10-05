package com.pvzce.server;

import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.PlantDef;
import com.pvzce.api.content.ZombieStatus;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.level.SceneBoard;
import com.pvzce.common.level.WorldPosition;
import com.pvzce.common.tag.TestContent;
import com.pvzce.server.entity.PlantEntity;
import com.pvzce.server.entity.ProjectileEntity;
import com.pvzce.server.entity.ZombieEntity;
import com.pvzce.server.level.LevelServer;
import com.pvzce.testutil.TestLevels;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The terraced hillside: what a custom height profile does to fire, planting and walking.
 *
 * <p>The level is the first one whose ground is not flat and not a roof or a pool: three terraces
 * (0.0 / 0.4 / 0.8 cells) joined by two earth banks, all of it the default surface, all of it
 * declared in the level's own scene with elements that carry a {@code profile}. That makes four
 * facts worth pinning, because each of them is the reason the level exists:
 *
 * <ul>
 *   <li>the heights are the ones the scene declares, sampled per column, ground included;</li>
 *   <li>the banks accept nothing at all while the terraces plant like a lawn;</li>
 *   <li>a flat shot cannot climb: a lower terrace's pea misses a zombie a terrace up, and dies on
 *       the bank rather than flying over it;</li>
 *   <li>a lobbed shot can: the pult is the level's one piece of cross-terrace fire;</li>
 *   <li>and a zombie walks down the terraces, its feet following the surface it is standing on.</li>
 * </ul>
 *
 * <p>{@code BuiltInLevelsValidateTest} already runs the level through every validator; this class
 * is about what the terrain <em>does</em>, through the real tick path.
 */
class TerracedHillsideTest {
    private static final Identifier LEVEL = PvzceIds.id("yard/minigame/terraced_hillside");
    /** Column centres, low terrace through top terrace. */
    private static final float[] COLUMN_HEIGHTS = {0F, 0F, 0F, 0.2F, 0.4F, 0.4F, 0.6F, 0.8F, 0.8F};

    private static LevelDef shipped;

    @BeforeAll
    static void load() throws Exception {
        TestContent.loadBuiltInContentAndTags();
        shipped = BuiltInRegistries.LEVELS.get(LEVEL);
        assertNotNull(shipped, "the shipped terraced hillside must load");
    }

    /** The shipped board with no waves, so only what a test places is on it. */
    private static LevelServer level(long seed) {
        return new LevelServer(TestLevels.withWaves(shipped, List.of()), seed);
    }

    private static PlantEntity plant(LevelServer level, String id, int x, int y) {
        PlantDef def = BuiltInRegistries.PLANTS.get(PvzceIds.id(id));
        assertNotNull(def, "missing plant " + id);
        return level.spawnPlant(def, level.team(PvzceIds.PLANT_TEAM), x, y);
    }

    private static ZombieEntity zombie(LevelServer level, String id, float x, int y) {
        return level.spawnZombie(PvzceIds.id(id), level.team(PvzceIds.ZOMBIE_TEAM), x, y);
    }

    /** Ticks the level and returns the rightmost x any live projectile reached. */
    private static float tickTrackingShots(LevelServer level, int ticks) {
        float furthest = -Float.MAX_VALUE;
        for (int i = 0; i < ticks; i++) {
            level.tick(packet -> { });
            for (var entity : level.entities()) {
                if (entity instanceof ProjectileEntity shot) {
                    furthest = Math.max(furthest, shot.cellX());
                }
            }
        }
        return furthest;
    }

    @Test
    void theSceneDeclaresThreeTerracesAndTwoBanks() {
        SceneBoard board = SceneBoard.forLevel(shipped);
        for (int x = 0; x < COLUMN_HEIGHTS.length; x++) {
            assertEquals(COLUMN_HEIGHTS[x],
                    board.elevationAt(SceneBoard.DEFAULT_SURFACE, x + 0.5F, 2.5F), 0.001F,
                    "column " + x + " is at its declared height");
        }
        assertEquals(PvzceIds.id("hillside_bank_low"), board.get(3, 2).id());
        assertEquals(PvzceIds.id("hillside_bank_high"), board.get(6, 2).id());
        assertEquals(PvzceIds.id("hillside_mid"), board.get(5, 2).id());
        assertEquals(PvzceIds.id("hillside_high"), board.get(8, 2).id());
        // The 9x5 board is 0.8 cells tall at its highest, which is the front lawn's own top margin:
        // the top row's tiles reach the top of the backdrop's dirt and no further.
        assertEquals(0.8F, board.elevationAt(SceneBoard.DEFAULT_SURFACE, 8.5F, 0.5F), 0.001F);
    }

    @Test
    void theBanksTakeNothingWhileTheTerracesPlantLikeALawn() {
        LevelServer level = level(1L);
        PlantDef pea = BuiltInRegistries.PLANTS.get(PvzceIds.id("pea_shooter"));
        PlantDef pot = BuiltInRegistries.PLANTS.get(PvzceIds.id("flower_pot"));
        for (int y = 0; y < shipped.height(); y++) {
            assertFalse(level.canPlacePlant(pea, 3, y), "the lower bank is too steep for a plant");
            assertFalse(level.canPlacePlant(pea, 6, y), "the upper bank is too steep for a plant");
            assertFalse(level.canPlacePlant(pot, 3, y), "nor for a pot: a bank is not ground");
            assertFalse(level.canPlacePlant(pot, 6, y), "nor for a pot: a bank is not ground");
            for (int x : new int[]{4, 5, 7, 8}) {
                boolean crater = PvzceIds.CRATER.equals(level.sceneAt(x, y).id());
                assertEquals(!crater, level.canPlacePlant(pea, x, y),
                        "cell " + x + "," + y + " is plantable unless the rockslide is in it");
            }
        }
    }

    @Test
    void aFlatShotCannotClimbTheHillAndDiesOnTheBank() {
        SceneBoard board = SceneBoard.forLevel(shipped);
        assertTrue(board.obstructed(new WorldPosition(1.5F, 2.5F, 0F), new WorldPosition(7.5F, 2.5F, 0F)),
                "a shot at lawn height runs into the first bank");
        assertFalse(board.obstructed(new WorldPosition(4.5F, 2.5F, 0.4F), new WorldPosition(5.5F, 2.5F, 0.4F)),
                "a shot along one terrace has nothing in its way");

        LevelServer level = level(2L);
        plant(level, "pea_shooter", 1, 2);
        ZombieEntity above = zombie(level, "basic_zombie", 5.5F, 2);
        above.applyStatus(ZombieStatus.IMMOBILIZED, 600, 1F);
        float furthest = tickTrackingShots(level, 300);
        assertEquals(above.maxHealth(), above.health(),
                "a zombie a terrace up is out of a lawn shooter's reach");
        assertTrue(furthest < 3.5F,
                "every pea stopped on the bank rather than flying over it; furthest x=" + furthest);
    }

    @Test
    void aShooterOnATerraceHitsItsOwnTerrace() {
        LevelServer level = level(3L);
        plant(level, "pea_shooter", 4, 2);
        ZombieEntity same = zombie(level, "basic_zombie", 5.5F, 2);
        same.applyStatus(ZombieStatus.IMMOBILIZED, 600, 1F);
        tickTrackingShots(level, 300);
        assertTrue(same.health() < same.maxHealth(),
                "the middle terrace defends itself; health=" + same.health());
    }

    @Test
    void aPultThrowsOverTheBank() {
        LevelServer level = level(4L);
        plant(level, "cabbage_pult", 1, 2);
        ZombieEntity above = zombie(level, "basic_zombie", 5.5F, 2);
        above.applyStatus(ZombieStatus.IMMOBILIZED, 900, 1F);
        tickTrackingShots(level, 500);
        assertTrue(above.health() < above.maxHealth(),
                "a lobbed shot crosses the bank; health=" + above.health());
    }

    @Test
    void aZombieWalksDownTheTerraces() {
        LevelServer level = level(5L);
        ZombieEntity walker = zombie(level, "basic_zombie", 7.5F, 2);
        level.flushPending(packet -> { });
        assertEquals(0.8F, walker.height(), 0.001F, "it starts on the top terrace");

        // ~1.8 cells of walking at 0.23 cells/s: enough to cross the upper bank, not enough to
        // reach the middle one.
        for (int i = 0; i < 480; i++) {
            level.tick(packet -> { });
        }
        assertTrue(walker.cellX() < 6.5F, "it crossed the upper bank; x=" + walker.cellX());
        assertEquals(level.surfaceHeight(SceneBoard.DEFAULT_SURFACE, walker.cellX(), walker.cellY()),
                walker.height(), 0.001F, "its feet follow whichever terrace it is standing on");
        assertEquals(0.4F, walker.height(), 0.02F, "which by now is the middle terrace");
    }
}
