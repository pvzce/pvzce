package com.pvzce.server;

import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.MowerData;
import com.pvzce.api.content.PlantDef;
import com.pvzce.api.content.ZombieStatus;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.level.SceneBoard;
import com.pvzce.common.level.WorldPosition;
import com.pvzce.common.level.mechanic.LevelMechanics;
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
 * The reef passage: an all-sea board where the only height is the rock the swimmers climb.
 *
 * <p>Every row of this level holds water, which is what makes it a swimming level - a walker has
 * nowhere to arrive, so the roster is the five swimmers and the water rows' mower is the pool
 * cleaner. The terrain is one surface with per-cell profiles again, but pointed the other way from
 * the terraced hillside: the high ground belongs to the <em>reefs</em> (a two-column top at 0.45,
 * a higher ridge at 0.85), the player's water is the low ground, and the swim is the approach.
 *
 * <p>Six facts, each of them why the level exists:
 *
 * <ul>
 *   <li>the sea is one layer whose columns rise into the two reefs at their declared heights;</li>
 *   <li>the reef tops are the only plantable land in the middle, the slopes take nothing, and the
 *       sea takes lilies;</li>
 *   <li>a shot at sea level dies on the reef's rock (so the water behind a reef is not covered
 *       from the near side) and the climber on top is above its hit band;</li>
 *   <li>a shooter on a reef top does cover the water beyond it, and the taller ridge blocks even
 *       that;</li>
 *   <li>a swimmer walks over both reefs, its feet following the rock;</li>
 *   <li>the deck and the buffs are the player's own to choose.</li>
 * </ul>
 *
 * <p>{@code BuiltInLevelsValidateTest} runs the level through every validator; this class is about
 * what the sea and the rock <em>do</em>, through the real tick path.
 */
class ReefPassageTest {
    private static final Identifier LEVEL = PvzceIds.id("yard/minigame/reef_passage");
    private static final String GROUND = SceneBoard.DEFAULT_SURFACE;
    private static final Identifier DUCKY = PvzceIds.id("ducky_tube_zombie");
    /** Column centres, the harbor through the far sea. */
    private static final float[] COLUMN_HEIGHTS = {0F, 0F, 0F, 0.2F, 0.45F, 0.45F, 0.2F, 0F, 0F, 0.425F, 0F};

    private static LevelDef shipped;

    @BeforeAll
    static void load() throws Exception {
        TestContent.loadBuiltInContentAndTags();
        shipped = BuiltInRegistries.LEVELS.get(LEVEL);
        assertNotNull(shipped, "the shipped reef passage must load");
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

    private static ZombieEntity swimmer(LevelServer level, float x, int y) {
        return level.spawnZombie(DUCKY, level.team(PvzceIds.ZOMBIE_TEAM), x, y, 1F, GROUND);
    }

    private static void hold(ZombieEntity zombie, int ticks) {
        zombie.applyStatus(ZombieStatus.IMMOBILIZED, ticks, 1F);
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

    /** Walks the swimmer until {@code done} holds, or the budget runs out. */
    private static boolean swimUntil(LevelServer level, ZombieEntity swimmer,
                                     java.util.function.BooleanSupplier done, int maxTicks) {
        for (int i = 0; i < maxTicks && !done.getAsBoolean(); i++) {
            level.tick(packet -> { });
        }
        return done.getAsBoolean();
    }

    @Test
    void theSeaIsOneLayerWithTwoReefsInIt() {
        SceneBoard board = SceneBoard.forLevel(shipped);
        for (int x = 0; x < COLUMN_HEIGHTS.length; x++) {
            assertEquals(COLUMN_HEIGHTS[x], board.elevationAt(GROUND, x + 0.5F, 2.5F), 0.001F,
                    "column " + x + " of the sea is at its declared height");
        }
        assertEquals(PvzceIds.id("reef_slope_down"), board.get(3, 2).id());
        assertEquals(PvzceIds.id("reef_top"), board.get(4, 2).id());
        assertEquals(PvzceIds.id("reef_top"), board.get(5, 2).id());
        assertEquals(PvzceIds.id("reef_slope_up"), board.get(6, 2).id());
        assertEquals(PvzceIds.id("reef_ridge"), board.get(9, 2).id());
        assertEquals(PvzceIds.id("water"), board.get(2, 2).id());
        assertEquals(PvzceIds.id("grass"), board.get(0, 2).id());
    }

    @Test
    void everyRowHoldsWaterSoTheRosterIsSwimmersAndTheMowersArePoolCleaners() {
        LevelServer level = level(1L);
        for (int y = 0; y < shipped.height(); y++) {
            assertTrue(level.rowIsWater(y), "row " + y + " holds the sea, so a walker has nowhere to arrive");
        }
        MowerData mowers = LevelMechanics.data(shipped, PvzceIds.MECHANIC_MOWER, MowerData.class);
        assertNotNull(mowers, "the level declares its water-row mowers");
        for (int y = 0; y < shipped.height(); y++) {
            MowerData.MowerKind kind = mowers.kindFor(y);
            assertNotNull(kind, "row " + y + " has a mower");
            assertEquals(PvzceIds.id("pool_cleaner"), kind.kind(), "row " + y + " gets a pool cleaner");
        }
    }

    @Test
    void theReefTopsAreTheOnlyLandInTheMiddleAndTheSeaTakesLilies() {
        LevelServer level = level(2L);
        PlantDef pea = BuiltInRegistries.PLANTS.get(PvzceIds.id("pea_shooter"));
        PlantDef lily = BuiltInRegistries.PLANTS.get(PvzceIds.id("lily_pad"));
        for (int y = 0; y < shipped.height(); y++) {
            assertTrue(level.canPlacePlant(pea, 0, y), "the harbor is land");
            assertTrue(level.canPlacePlant(pea, 4, y), "the reef top is land to plant on");
            assertTrue(level.canPlacePlant(pea, 5, y), "both of its columns are");
            for (int x : new int[]{3, 6, 9}) {
                assertFalse(level.canPlacePlant(pea, x, y),
                        "the reef slope at column " + x + " is bare rock");
            }
            assertFalse(level.canPlacePlant(pea, 2, y), "open sea is not plantable by a land plant");
            assertTrue(level.canPlacePlant(lily, 2, y), "but a lily pad goes on it");
        }
    }

    @Test
    void aShotAtSeaLevelDiesOnTheReefAndTheClimberIsAboveIt() {
        SceneBoard board = SceneBoard.forLevel(shipped);
        assertTrue(board.obstructed(new WorldPosition(1.5F, 2.5F, 0F), new WorldPosition(9.5F, 2.5F, 0F)),
                "a shot at sea level runs into the first reef");
        assertTrue(board.obstructed(new WorldPosition(4.5F, 2.5F, 0.45F), new WorldPosition(9.5F, 2.5F, 0.45F)),
                "and a shot from the reef top is stopped by the taller ridge");
        assertFalse(board.obstructed(new WorldPosition(4.5F, 2.5F, 0.45F), new WorldPosition(5.5F, 2.5F, 0.45F)),
                "while the top's own two columns are clear");

        LevelServer level = level(3L);
        plant(level, "pea_shooter", 0, 2);
        ZombieEntity climber = swimmer(level, 5.5F, 2);
        hold(climber, 600);
        float furthest = tickTrackingShots(level, 300);
        assertEquals(climber.maxHealth(), climber.health(),
                "a swimmer on top of the reef is above a harbor shooter's reach");
        assertTrue(furthest < 3.5F,
                "its pea stopped on the reef's rock face; furthest x=" + furthest);
    }

    @Test
    void aReefTopShooterCoversTheWaterBeyondItButNotBeyondTheRidge() {
        LevelServer level = level(4L);
        plant(level, "pea_shooter", 4, 2);
        ZombieEntity beyond = swimmer(level, 7.5F, 2);
        hold(beyond, 600);
        tickTrackingShots(level, 300);
        assertTrue(beyond.health() < beyond.maxHealth(),
                "the reef top covers the water on its far side; health=" + beyond.health());

        LevelServer far = level(5L);
        plant(far, "pea_shooter", 4, 2);
        ZombieEntity behindRidge = swimmer(far, 9.5F, 2);
        hold(behindRidge, 600);
        tickTrackingShots(far, 300);
        assertEquals(behindRidge.maxHealth(), behindRidge.health(),
                "but the taller ridge shelters what is behind it");
    }

    @Test
    void aSwimmerWalksOverBothReefs() {
        LevelServer level = level(6L);
        ZombieEntity swimmer = swimmer(level, 10.5F, 2);
        level.flushPending(packet -> { });
        assertTrue(swimUntil(level, swimmer, () -> swimmer.height() > 0.7F, 900),
                "it climbs the tall ridge; x=" + swimmer.cellX() + " height=" + swimmer.height());
        assertTrue(swimUntil(level, swimmer, () -> swimmer.cellX() < 8.5F, 900),
                "then drops into the sea beyond it; x=" + swimmer.cellX());
        assertEquals(level.surfaceHeight(GROUND, swimmer.cellX(), swimmer.cellY()), swimmer.height(), 0.001F,
                "its feet follow the rock wherever it is");
        assertTrue(swimUntil(level, swimmer, () -> swimmer.height() > 0.4F && swimmer.cellX() < 7F, 1200),
                "and it climbs the middle reef too; x=" + swimmer.cellX() + " height=" + swimmer.height());
        assertEquals(0.45F, swimmer.height(), 0.02F, "up onto its top");
    }

    @Test
    void theDeckAndTheBuffsAreThePlayersToChoose() {
        assertTrue(shipped.slots().isEmpty(), "no card of this level is forced onto the bar");
        assertFalse(shipped.declaresMaxSeedSlots(), "and its bar size is the player's, not the level's");
        assertEquals(11, shipped.effectiveMaxSeedSlots(11), "so an 11-slot backpack gets 11 cards");
        assertTrue(shipped.buffPlan().offersPlayerChoice(), "the buff page offers the player's own buffs");
        assertTrue(shipped.buffPlan().fixedBuffs().isEmpty(), "with none fixed by the level");
        assertFalse(shipped.buffPlan().declaresMaxBuffSlots(), "and no cap of its own on how many");
        assertEquals(3, shipped.effectiveMaxBuffSlots(3), "so a 3-buff backpack gets 3");
    }
}
