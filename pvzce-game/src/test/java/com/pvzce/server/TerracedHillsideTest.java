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
 * The terraced hillside: one hillside layer with slopes in it, and the ground shelf underneath.
 *
 * <p>The level is two surfaces and no ramps between them. The hillside is the <em>default</em>
 * surface - the backyard (0), a bank up to the middle terrace (0.4), a second bank up to the top
 * one (0.8), the transition being the slopes rather than a step, exactly what a level can do with
 * per-cell element profiles ({@code SceneElementDef.profile}). The second surface is the shelf
 * under the terraces, at 0, holding the two columns of sunflower lawn; it is reached by switching
 * the operation layer, not by walking, so nothing that walks ever gets there.
 *
 * <p>Six facts, each of them why the level exists:
 *
 * <ul>
 *   <li>the hillside is one layer whose height is a column profile, and the shelf sorts below it;</li>
 *   <li>the banks take nothing at all while the terraces and the beds plant like a lawn;</li>
 *   <li>one cell holds a sunflower on the shelf and a shooter on the terrace above it;</li>
 *   <li>fire cannot climb a bank: a lower shooter's pea dies on the slope (and a lob <em>can</em>
 *       clear it, which is what makes the pult the level's one piece of cross-height fire);</li>
 *   <li>a walker's feet follow the profile down the banks;</li>
 *   <li>the deck and the buffs are the player's own to choose.</li>
 * </ul>
 *
 * <p>{@code BuiltInLevelsValidateTest} runs the level through every validator; this class is about
 * what the terrain <em>does</em>, through the real tick path.
 */
class TerracedHillsideTest {
    private static final Identifier LEVEL = PvzceIds.id("yard/minigame/terraced_hillside");
    private static final String GROUND = SceneBoard.DEFAULT_SURFACE;
    private static final String SHELF = "pvzce:under_terrace";
    private static final Identifier BASIC = PvzceIds.id("basic_zombie");
    /** Column centres, backyard through top terrace. */
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
        return plant(level, id, x, y, GROUND);
    }

    private static PlantEntity plant(LevelServer level, String id, int x, int y, String surface) {
        PlantDef def = BuiltInRegistries.PLANTS.get(PvzceIds.id(id));
        assertNotNull(def, "missing plant " + id);
        return level.spawnPlant(def, level.team(PvzceIds.PLANT_TEAM), x, y, surface);
    }

    private static ZombieEntity zombie(LevelServer level, float x, int y) {
        return level.spawnZombie(BASIC, level.team(PvzceIds.ZOMBIE_TEAM), x, y, 1F, GROUND);
    }

    private static void tick(LevelServer level, int ticks) {
        for (int i = 0; i < ticks; i++) {
            level.tick(packet -> { });
        }
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
    void theHillsideIsOneLayerOfTerracesAndTheShelfSitsUnderIt() {
        SceneBoard board = SceneBoard.forLevel(shipped);
        assertEquals(List.of(SHELF, GROUND), board.surfacesBottomFirst(),
                "the shelf is drawn under the hillside, not above it");
        for (int x = 0; x < COLUMN_HEIGHTS.length; x++) {
            assertEquals(COLUMN_HEIGHTS[x], board.elevationAt(GROUND, x + 0.5F, 2.5F), 0.001F,
                    "column " + x + " of the hillside is at its declared height");
        }
        assertEquals(PvzceIds.id("hillside_bank_low"), board.get(3, 2).id());
        assertEquals(PvzceIds.id("hillside_bank_high"), board.get(6, 2).id());
        assertEquals(PvzceIds.id("hillside_mid"), board.get(5, 2).id());
        assertEquals(PvzceIds.id("hillside_high"), board.get(8, 2).id());
        // The rockslide on the top terrace is an overlay on the terrace's own lawn.
        assertEquals(PvzceIds.CRATER, board.get(7, 2).id());
        // The shelf exists only under the terraces, and it is level with the backyard.
        assertEquals(0F, board.elevationAt(SHELF, 4.5F, 2.5F), 0.001F);
        assertEquals(0F, board.elevationAt(SHELF, 8.5F, 2.5F), 0.001F);
        assertNull(board.cell(SHELF, 3, 2), "the shelf starts under the terraces, not under the bank");
        assertNull(board.cell(SHELF, 0, 2), "and nothing is under the backyard");
    }

    @Test
    void theBanksTakeNothingWhileTheTerracesAndTheBedsPlantLikeALawn() {
        LevelServer level = level(1L);
        PlantDef pea = BuiltInRegistries.PLANTS.get(PvzceIds.id("pea_shooter"));
        PlantDef pot = BuiltInRegistries.PLANTS.get(PvzceIds.id("flower_pot"));
        for (int y = 0; y < shipped.height(); y++) {
            assertFalse(level.canPlacePlant(pea, 3, y, GROUND), "the lower bank is too steep for a plant");
            assertFalse(level.canPlacePlant(pea, 6, y, GROUND), "the upper bank is too steep for a plant");
            assertFalse(level.canPlacePlant(pot, 3, y, GROUND), "nor for a pot: a bank is not ground");
            assertFalse(level.canPlacePlant(pot, 6, y, GROUND), "nor for a pot: a bank is not ground");
            for (int x : new int[]{0, 1, 2, 4, 5, 7, 8}) {
                boolean crater = PvzceIds.CRATER.equals(level.sceneAt(x, y).id());
                assertEquals(!crater, level.canPlacePlant(pea, x, y, GROUND),
                        "hillside cell " + x + "," + y + " is plantable unless the rockslide is in it");
            }
            assertTrue(level.canPlacePlant(pea, 4, y, SHELF), "column 4 of the shelf is a sunflower bed");
            assertTrue(level.canPlacePlant(pea, 5, y, SHELF), "column 5 of the shelf is a sunflower bed");
            assertFalse(level.canPlacePlant(pea, 6, y, SHELF), "the far end of the shelf is bare earth");
            assertTrue(level.canPlacePlant(pot, 6, y, SHELF), "and bare earth takes a pot");
            assertFalse(level.canPlacePlant(pea, 0, y, SHELF), "the shelf does not reach the backyard");
        }
    }

    @Test
    void oneCellHoldsASunflowerBelowAndAShooterAbove() {
        LevelServer level = level(2L);
        var sunflower = plant(level, "sunflower", 4, 2, SHELF);
        var shooter = plant(level, "pea_shooter", 4, 2, GROUND);
        level.flushPending(packet -> { });
        assertEquals(0F, sunflower.height(), 0.001F);
        assertEquals(0.4F, shooter.height(), 0.001F);
        assertSame(sunflower, level.plantAt(4, 2, SHELF));
        assertSame(shooter, level.plantAt(4, 2, GROUND));
    }

    @Test
    void aFlatShotCannotClimbTheHillAndDiesOnTheBank() {
        SceneBoard board = SceneBoard.forLevel(shipped);
        assertTrue(board.obstructed(new WorldPosition(1.5F, 2.5F, 0F), new WorldPosition(7.5F, 2.5F, 0F)),
                "a shot at backyard height runs into the first bank");
        assertFalse(board.obstructed(new WorldPosition(4.5F, 2.5F, 0.4F), new WorldPosition(5.5F, 2.5F, 0.4F)),
                "a shot along one terrace has nothing in its way");

        LevelServer level = level(3L);
        plant(level, "pea_shooter", 1, 2);
        ZombieEntity above = zombie(level, 5.5F, 2);
        above.applyStatus(ZombieStatus.IMMOBILIZED, 600, 1F);
        float furthest = tickTrackingShots(level, 300);
        assertEquals(above.maxHealth(), above.health(),
                "a zombie a terrace up is out of a backyard shooter's reach");
        assertTrue(furthest < 3.5F,
                "every pea stopped on the bank rather than flying over it; furthest x=" + furthest);
    }

    @Test
    void eachTerracesOwnShooterHitsItAndAPultThrowsOverTheBank() {
        LevelServer level = level(4L);
        plant(level, "pea_shooter", 4, 2);
        ZombieEntity same = zombie(level, 5.5F, 2);
        same.applyStatus(ZombieStatus.IMMOBILIZED, 600, 1F);
        tickTrackingShots(level, 300);
        assertTrue(same.health() < same.maxHealth(),
                "the middle terrace defends itself; health=" + same.health());

        LevelServer lobbing = level(5L);
        plant(lobbing, "cabbage_pult", 1, 2);
        ZombieEntity above = zombie(lobbing, 5.5F, 2);
        above.applyStatus(ZombieStatus.IMMOBILIZED, 900, 1F);
        tickTrackingShots(lobbing, 500);
        assertTrue(above.health() < above.maxHealth(),
                "a lobbed shot crosses the bank, which is the level's one way up; health=" + above.health());
    }

    @Test
    void aZombieWalksDownTheBanks() {
        LevelServer level = level(6L);
        ZombieEntity walker = zombie(level, 7.5F, 2);
        level.flushPending(packet -> { });
        assertEquals(0.8F, walker.height(), 0.001F, "it starts on the top terrace");

        // ~1.8 cells of walking at 0.23 cells/s: enough to be down the upper bank and onto the
        // middle terrace.
        tick(level, 520);
        assertTrue(walker.cellX() < 6F, "it crossed the upper bank; x=" + walker.cellX());
        assertEquals(level.surfaceHeight(GROUND, walker.cellX(), walker.cellY()), walker.height(), 0.001F,
                "its feet follow whichever part of the profile it is standing on");
        assertEquals(0.4F, walker.height(), 0.02F, "which by now is the middle terrace");
    }

    @Test
    void theDeckAndTheBuffsAreThePlayersToChoose() {
        // The level pins no cards and no buffs: both pages of the card screen are the player's own
        // backpack, and neither count is capped by the level (an unwritten max follows the profile).
        assertTrue(shipped.slots().isEmpty(), "no card of this level is forced onto the bar");
        assertFalse(shipped.declaresMaxSeedSlots(), "and its bar size is the player's, not the level's");
        assertEquals(11, shipped.effectiveMaxSeedSlots(11), "so an 11-slot backpack gets 11 cards");
        assertTrue(shipped.buffPlan().offersPlayerChoice(), "the buff page offers the player's own buffs");
        assertTrue(shipped.buffPlan().fixedBuffs().isEmpty(), "with none fixed by the level");
        assertFalse(shipped.buffPlan().declaresMaxBuffSlots(), "and no cap of its own on how many");
        assertEquals(3, shipped.effectiveMaxBuffSlots(3), "so a 3-buff backpack gets 3");
    }
}
