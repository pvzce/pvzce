package com.pvzce.server;

import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.PlantDef;
import com.pvzce.api.content.ZombieStatus;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.level.SceneBoard;
import com.pvzce.common.level.mechanic.LevelMechanics;
import com.pvzce.common.level.mechanic.SurfaceLinksData;
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
 * The terraced hillside: three stacked layers, one one-way ramp per row between each pair.
 *
 * <p>This is the first shipped level that is <em>all</em> layers rather than one flat board with a
 * bridge over it: the backyard (0), a middle terrace (0.4) and a top terrace (0.8), each an
 * independently occupied surface, each joined to the one below by a ramp at a fixed column. That
 * makes six facts worth pinning, because each of them is why the level exists:
 *
 * <ul>
 *   <li>the three layers stack in the board's own order and at their declared heights, and the
 *       ramp columns exist on <em>both</em> the layer above and the layer below (a one-way ramp
 *       whose landing cell did not exist would drop a walker through the deck);</li>
 *   <li>the ground under the terraces is two columns of lawn for sunflowers, with bare earth on
 *       the landing strip in front of it so the walkers never cross the beds;</li>
 *   <li>one cell can hold a sunflower below and a shooter above - the layers overlap;</li>
 *   <li>a zombie walks down both ramps on its own, its feet following whichever layer it is on;</li>
 *   <li>fire does not cross a layer at all: a shooter only sees its own surface, so a lower
 *       layer's plants cannot so much as fire at the terrace above (and a lob is no way up
 *       either);</li>
 *   <li>each terrace defends its own band.</li>
 * </ul>
 *
 * <p>{@code BuiltInLevelsValidateTest} runs the level through every validator; this class is about
 * what the layers <em>do</em>, through the real tick path.
 */
class TerracedHillsideTest {
    private static final Identifier LEVEL = PvzceIds.id("yard/minigame/terraced_hillside");
    private static final String GROUND = SceneBoard.DEFAULT_SURFACE;
    private static final String MID = "pvzce:terrace_mid";
    private static final String HIGH = "pvzce:terrace_high";
    private static final Identifier BASIC = PvzceIds.id("basic_zombie");

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

    private static ZombieEntity zombie(LevelServer level, float x, int y, String surface) {
        return level.spawnZombie(BASIC, level.team(PvzceIds.ZOMBIE_TEAM), x, y, 1F, surface);
    }

    private static void tick(LevelServer level, int ticks) {
        for (int i = 0; i < ticks; i++) {
            level.tick(packet -> { });
        }
    }

    /** Ticks the level and reports how many shots the plants fired. */
    private static int shotsFired(LevelServer level, int ticks) {
        int seen = 0;
        java.util.Set<Integer> ids = new java.util.HashSet<>();
        for (int i = 0; i < ticks; i++) {
            level.tick(packet -> { });
            for (var entity : level.entities()) {
                if (entity instanceof ProjectileEntity shot && ids.add(shot.id())) {
                    seen++;
                }
            }
        }
        return seen;
    }

    @Test
    void theBoardIsThreeStackedLayersAtTheirDeclaredHeights() {
        SceneBoard board = SceneBoard.forLevel(shipped);
        assertEquals(List.of(GROUND, MID, HIGH), board.surfacesBottomFirst(),
                "the backyard is the floor of the hill, the terraces stack above it");
        assertEquals(0F, board.elevationAt(GROUND, 2.5F, 2.5F), 0.001F);
        assertEquals(0.4F, board.elevationAt(MID, 4.5F, 2.5F), 0.001F);
        assertEquals(0.8F, board.elevationAt(HIGH, 7.5F, 2.5F), 0.001F);
        // The rockslide the top terrace's own scene paints survives as an overlay on its lawn.
        assertEquals(PvzceIds.CRATER, board.get(HIGH, 8, 1).id());
        assertEquals(PvzceIds.GRASS, board.get(HIGH, 8, 0).id());
        // The terraces are sparse: nothing exists off their own columns...
        assertNull(board.cell(MID, 6, 2), "the middle terrace stops where it lands the walkers");
        assertNull(board.cell(HIGH, 4, 2), "and the top terrace stops at its own ramp column");
        // ...except at the ramp column, which exists on both sides of the drop - that overlap is
        // what the one-way link needs, and what keeps a walker from falling through the deck.
        assertNotNull(board.cell(MID, 5, 2), "the upper ramp's landing cell exists on the middle terrace");
        assertNotNull(board.cell(HIGH, 5, 2), "and on the top terrace it is left from");
    }

    @Test
    void theGroundUnderTheTerracesHasTwoLawnColumns() {
        LevelServer level = level(1L);
        PlantDef pea = BuiltInRegistries.PLANTS.get(PvzceIds.id("pea_shooter"));
        PlantDef pot = BuiltInRegistries.PLANTS.get(PvzceIds.id("flower_pot"));
        for (int y = 0; y < shipped.height(); y++) {
            assertTrue(level.canPlacePlant(pea, 0, y, GROUND), "the backyard is lawn");
            assertTrue(level.canPlacePlant(pea, 4, y, GROUND), "column 4 is a sunflower bed");
            assertTrue(level.canPlacePlant(pea, 5, y, GROUND), "column 5 is a sunflower bed");
            assertFalse(level.canPlacePlant(pea, 3, y, GROUND),
                    "the strip the walkers land on is bare earth, not a bed");
            assertTrue(level.canPlacePlant(pot, 3, y, GROUND), "and bare earth takes a pot");
            assertFalse(level.canPlacePlant(pea, 6, y, GROUND), "so is the far end");
            assertTrue(level.canPlacePlant(pea, 4, y, MID), "the middle terrace is a lawn of its own");
            assertFalse(level.canPlacePlant(pea, 5, y, MID), "except the bare ramp foot on it");
            assertTrue(level.canPlacePlant(pea, 6, y, HIGH), "and the top terrace is one too");
            assertFalse(level.canPlacePlant(pea, 6, y, "pvzce:not_a_layer"), "a missing layer takes nothing");
        }
    }

    @Test
    void oneCellHoldsASunflowerBelowAndAShooterAbove() {
        LevelServer level = level(2L);
        var sunflower = plant(level, "sunflower", 4, 2, GROUND);
        var shooter = plant(level, "pea_shooter", 4, 2, MID);
        level.flushPending(packet -> { });
        assertEquals(0F, sunflower.height(), 0.001F);
        assertEquals(0.4F, shooter.height(), 0.001F);
        assertSame(sunflower, level.plantAt(4, 2, GROUND));
        assertSame(shooter, level.plantAt(4, 2, MID));
    }

    @Test
    void theRampsLandOnBareEarthInFrontOfTheBeds() {
        SurfaceLinksData links = LevelMechanics.data(shipped, PvzceIds.MECHANIC_SURFACE_LINKS,
                SurfaceLinksData.class);
        assertNotNull(links, "the level is joined by explicit one-way ramps");
        for (int y = 0; y < shipped.height(); y++) {
            final int row = y;
            assertTrue(links.connections().stream().anyMatch(link ->
                            link.from().equals(HIGH) && link.to().equals(MID) && link.y() == row && link.x() == 5),
                    "row " + y + " has its upper ramp at column 5");
            assertTrue(links.connections().stream().anyMatch(link ->
                            link.from().equals(MID) && link.to().equals(GROUND) && link.y() == row && link.x() == 3),
                    "row " + y + " lands on column 3, in front of the sunflower beds");
        }
    }

    @Test
    void aZombieWalksDownBothRampsByItself() {
        LevelServer level = level(3L);
        ZombieEntity walker = zombie(level, 7.5F, 2, HIGH);
        level.flushPending(packet -> { });
        assertEquals(HIGH, walker.surfaceId());
        assertEquals(0.8F, walker.height(), 0.001F, "it starts on the top terrace");

        // Walk until the upper ramp takes it, then look at where it landed: column 5, the cell the
        // two terraces share. Polling rather than a fixed tick count so a walk-speed change cannot
        // turn this into "it happened to still be up there".
        assertTrue(walkUntil(level, walker, () -> walker.surfaceId().equals(MID), 900),
                "the upper ramp takes it down one terrace; x=" + walker.cellX());
        assertTrue(walker.cellX() >= 5F && walker.cellX() < 6F,
                "it lands on the shared cell at column 5; x=" + walker.cellX());
        assertEquals(0.4F, walker.height(), 0.001F);

        assertTrue(walkUntil(level, walker, () -> walker.surfaceId().equals(GROUND), 900),
                "the lower ramp takes it into the backyard; x=" + walker.cellX());
        assertTrue(walker.cellX() >= 3F && walker.cellX() < 4F,
                "and it lands on the bare strip at column 3, in front of the sunflower beds; x="
                        + walker.cellX());
        assertEquals(0F, walker.height(), 0.001F);
    }

    /** Walks the zombie until {@code done} holds, or the budget runs out. */
    private static boolean walkUntil(LevelServer level, ZombieEntity walker,
                                     java.util.function.BooleanSupplier done, int maxTicks) {
        for (int i = 0; i < maxTicks && !done.getAsBoolean(); i++) {
            level.tick(packet -> { });
        }
        return done.getAsBoolean();
    }

    @Test
    void fireDoesNotCrossALayer() {
        // A shooter only ever looks at its own surface, so a backyard plant does not even fire at
        // the terrace above - and the zombie up there never takes a scratch.
        LevelServer level = level(4L);
        plant(level, "pea_shooter", 1, 2, GROUND);
        ZombieEntity above = zombie(level, 4.5F, 2, MID);
        above.applyStatus(ZombieStatus.IMMOBILIZED, 900, 1F);
        assertEquals(0, shotsFired(level, 300), "a lower layer's shooter never fires up a terrace");
        assertEquals(above.maxHealth(), above.health());

        // A lob is no way up either: the pult does not see the layer above, and if it did, its arc
        // would burst on the deck's underside (the deck has thickness).
        LevelServer lobbing = level(5L);
        plant(lobbing, "cabbage_pult", 1, 2, GROUND);
        ZombieEntity sheltered = zombie(lobbing, 4.5F, 2, MID);
        sheltered.applyStatus(ZombieStatus.IMMOBILIZED, 900, 1F);
        assertEquals(0, shotsFired(lobbing, 500), "nor does a pult lob up there");
        assertEquals(sheltered.maxHealth(), sheltered.health());

        // And the layer filter is symmetric: a terrace shooter does not fire down at the backyard
        // either. Its battles are its own.
        LevelServer top = level(6L);
        plant(top, "pea_shooter", 4, 2, MID);
        ZombieEntity below = zombie(top, 1.5F, 2, GROUND);
        below.applyStatus(ZombieStatus.IMMOBILIZED, 900, 1F);
        assertEquals(0, shotsFired(top, 300), "a terrace shooter does not fire down at the backyard");
        assertEquals(below.maxHealth(), below.health());
    }

    @Test
    void eachTerraceDefendsItsOwnBand() {
        LevelServer level = level(7L);
        plant(level, "pea_shooter", 4, 2, MID);
        ZombieEntity same = zombie(level, 5.0F, 2, MID);
        same.applyStatus(ZombieStatus.IMMOBILIZED, 900, 1F);
        shotsFired(level, 300);
        assertTrue(same.health() < same.maxHealth(),
                "a shooter on the middle terrace reaches a zombie on it; health=" + same.health());

        LevelServer top = level(8L);
        plant(top, "pea_shooter", 6, 2, HIGH);
        ZombieEntity above = zombie(top, 7.0F, 2, HIGH);
        above.applyStatus(ZombieStatus.IMMOBILIZED, 900, 1F);
        shotsFired(top, 300);
        assertTrue(above.health() < above.maxHealth(),
                "and the top terrace likewise; health=" + above.health());
    }
}
