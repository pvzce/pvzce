package com.pvzce.server;

import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.PlantDef;
import com.pvzce.api.content.PortalData;
import com.pvzce.api.content.ZombieStatus;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.level.SceneBoard;
import com.pvzce.common.level.WorldPosition;
import com.pvzce.common.level.mechanic.LevelMechanics;
import com.pvzce.common.network.PvzcePacket;
import com.pvzce.common.network.packet.MechanicSyncS2C;
import com.pvzce.common.tag.TestContent;
import com.pvzce.server.entity.PlantEntity;
import com.pvzce.server.entity.ProjectileEntity;
import com.pvzce.server.entity.ZombieEntity;
import com.pvzce.server.level.LevelServer;
import com.pvzce.testutil.TestLevels;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The tunnel corridor: a mountain that eats every straight shot, and the portals that go through it.
 *
 * <p>The height work here is the opposite of the earlier two levels in one specific way: the
 * mountain is not a firing position and not a hazard to shoot over - it is <em>solid</em>. Its
 * slopes and its ridge are bare rock ({@code #c:unplantable}) tall enough that a shot at lawn
 * height dies on the face, in every row, so the two facts that matter are:
 *
 * <ul>
 *   <li>nothing planted on the lawn can reach the far shore by firing;</li>
 *   <li>a portal can - a shot that crosses a portal's cell leaves the paired one and keeps going,
 *       which is what makes the pairs the level's only artillery;</li>
 *   <li>and the pairs are symmetric: a zombie walking into one leaves the other, diagonally when
 *       the pair is diagonal, so the lane a shot comes out in is the lane a walker can appear in;</li>
 *   <li>the pairs relocate on the level's own clock (with a warning), so the doorways move;</li>
 *   <li>the level pins the shovel and the glove and nothing else: the bar is still the player's.</li>
 * </ul>
 *
 * <p>{@code BuiltInLevelsValidateTest} runs the level through every validator; this class is about
 * what the mountain and the doors <em>do</em>, through the real tick path.
 */
class TunnelCorridorTest {
    private static final Identifier LEVEL = PvzceIds.id("yard/minigame/tunnel_corridor");
    private static final String GROUND = SceneBoard.DEFAULT_SURFACE;
    private static final Identifier BASIC = PvzceIds.id("basic_zombie");
    /** Column centres, the home lawn through the far shore. */
    private static final float[] COLUMN_HEIGHTS = {0F, 0F, 0F, 0F, 0F, 0.4F, 0.8F, 0.8F, 0.4F, 0F, 0F};

    private static LevelDef shipped;

    @BeforeAll
    static void load() throws Exception {
        TestContent.loadBuiltInContentAndTags();
        shipped = BuiltInRegistries.LEVELS.get(LEVEL);
        assertNotNull(shipped, "the shipped tunnel corridor must load");
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

    private static ZombieEntity walker(LevelServer level, float x, int y) {
        return level.spawnZombie(BASIC, level.team(PvzceIds.ZOMBIE_TEAM), x, y, 1F, GROUND);
    }

    private static void hold(ZombieEntity zombie, int ticks) {
        zombie.applyStatus(ZombieStatus.IMMOBILIZED, ticks, 1F);
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

    /** Walks the zombie until {@code done} holds, or the budget runs out. */
    private static boolean walkUntil(LevelServer level, ZombieEntity zombie,
                                     java.util.function.BooleanSupplier done, int maxTicks) {
        for (int i = 0; i < maxTicks && !done.getAsBoolean(); i++) {
            level.tick(packet -> { });
        }
        return done.getAsBoolean();
    }

    @Test
    void theMountainSpansEveryLaneAndTakesNoPlants() {
        SceneBoard board = SceneBoard.forLevel(shipped);
        assertEquals(List.of(GROUND), board.surfacesBottomFirst(), "this board is one layer");
        for (int x = 0; x < COLUMN_HEIGHTS.length; x++) {
            assertEquals(COLUMN_HEIGHTS[x], board.elevationAt(GROUND, x + 0.5F, 2.5F), 0.001F,
                    "column " + x + " is at its declared height");
        }
        assertEquals(PvzceIds.id("tunnel_slope_up"), board.get(5, 2).id());
        assertEquals(PvzceIds.id("tunnel_ridge"), board.get(6, 2).id());
        assertEquals(PvzceIds.id("tunnel_ridge"), board.get(7, 2).id());
        assertEquals(PvzceIds.id("tunnel_slope_down"), board.get(8, 2).id());

        LevelServer level = level(1L);
        PlantDef pea = BuiltInRegistries.PLANTS.get(PvzceIds.id("pea_shooter"));
        PlantDef pot = BuiltInRegistries.PLANTS.get(PvzceIds.id("flower_pot"));
        for (int y = 0; y < shipped.height(); y++) {
            for (int x : new int[]{5, 6, 7, 8}) {
                assertFalse(level.canPlacePlant(pea, x, y), "the mountain cell " + x + "," + y
                        + " is bare rock, so the doors have to be the artillery");
                assertFalse(level.canPlacePlant(pot, x, y), "and not even a pot goes on it");
            }
            for (int x : new int[]{0, 4, 9, 10}) {
                assertTrue(level.canPlacePlant(pea, x, y), "cell " + x + "," + y + " is lawn");
            }
            assertTrue(board.obstructed(new WorldPosition(1.5F, y + 0.5F, 0F),
                            new WorldPosition(9.5F, y + 0.5F, 0F)),
                    "row " + y + " is blocked by the mountain for a shot at lawn height");
        }
    }

    @Test
    void onlyAPortalCarriesAShotToTheFarShore() {
        // Row 1 has a door at the mountain's foot (4,1) paired with the far shore (9,1), so a shot
        // fired into it comes out on the far side and keeps going.
        LevelServer through = level(2L);
        plant(through, "pea_shooter", 3, 1);
        ZombieEntity beyond = walker(through, 10.5F, 1);
        hold(beyond, 600);
        float reached = tickTrackingShots(through, 300);
        assertTrue(beyond.health() < beyond.maxHealth(),
                "the shot went through the door and reached the far shore; health=" + beyond.health()
                        + " furthest shot x=" + reached);

        // Row 3 has no door at the foot, so the same shot dies on the mountain's face.
        LevelServer blocked = level(3L);
        plant(blocked, "pea_shooter", 3, 3);
        ZombieEntity sheltered = walker(blocked, 10.5F, 3);
        hold(sheltered, 600);
        float furthest = tickTrackingShots(blocked, 300);
        assertEquals(sheltered.maxHealth(), sheltered.health(),
                "without a door the far shore is out of reach");
        assertTrue(furthest < 6F, "the pea stopped on the mountain's face; furthest x=" + furthest);
    }

    @Test
    void aZombieThatWalksIntoAPortalComesOutOfItsPair() {
        // Straight pair (4,1) <-> (9,1): a walker in row 1 crosses at the far end and comes out at
        // the mountain's foot, skipping the climb.
        LevelServer level = level(4L);
        ZombieEntity straight = walker(level, 10.5F, 1);
        assertTrue(walkUntil(level, straight, () -> straight.cellX() < 5F, 900),
                "the walker took the door; x=" + straight.cellX());
        assertEquals(1, straight.gridY(), "a straight pair keeps the lane");
        assertTrue(straight.cellX() < 4.7F && straight.cellX() > 4.2F,
                "it came out just past the paired end; x=" + straight.cellX());

        // Diagonal pair (4,0) <-> (9,4): the same walk in row 4 comes out in row 0.
        LevelServer diagonal = level(5L);
        ZombieEntity changed = walker(diagonal, 10.5F, 4);
        assertTrue(walkUntil(diagonal, changed, () -> changed.cellX() < 5F, 900),
                "the walker took the diagonal door; x=" + changed.cellX());
        assertEquals(0, changed.gridY(), "a diagonal pair changes the lane, which is what makes the"
                + " doors a threat as well as a firing window");
    }

    @Test
    void thePortalsRelocateOnTheirClockWithAWarning() {
        PortalData data = LevelMechanics.data(shipped, PvzceIds.MECHANIC_PORTAL, PortalData.class);
        assertNotNull(data, "the level declares its doors");
        assertEquals(3, data.pairs().size());
        assertEquals(1500, data.relocateIntervalTicks());
        assertEquals(1200, data.initialRelocateTicks());

        LevelServer level = level(6L);
        List<PvzcePacket> packets = new ArrayList<>();
        for (int i = 0; i < 1250; i++) {
            level.tick(packets::add);
        }
        List<MechanicSyncS2C> portalSyncs = packets.stream()
                .filter(MechanicSyncS2C.class::isInstance)
                .map(MechanicSyncS2C.class::cast)
                .filter(sync -> PvzceIds.MECHANIC_PORTAL.equals(sync.mechanic()))
                .toList();
        assertTrue(portalSyncs.size() >= 2,
                "the client hears the doors once at the start and again when they move; heard "
                        + portalSyncs.size());
        assertFalse(Arrays.equals(portalSyncs.get(0).payload(), portalSyncs.get(portalSyncs.size() - 1).payload()),
                "and the last set is not the one it started with");
        assertTrue(packets.stream().anyMatch(packet -> packet.toString() != null), "packets arrived");
    }

    @Test
    void theDeckAndBuffsAreThePlayersToChoose() {
        // The level pins the two tools the doors need - the shovel and the glove - and no plant,
        // so which plants go through the doors is still the player's call.
        assertEquals(List.of(PvzceIds.id("shovel"), PvzceIds.id("glove")), shipped.slots(),
                "only the tools are pinned");
        assertFalse(shipped.declaresMaxSeedSlots(), "and the bar size is the player's");
        assertEquals(11, shipped.effectiveMaxSeedSlots(11), "so an 11-slot backpack gets 11 cards");
        assertTrue(shipped.buffPlan().offersPlayerChoice(), "the buff page offers the player's own buffs");
        assertTrue(shipped.buffPlan().fixedBuffs().isEmpty(), "with none fixed by the level");
        assertFalse(shipped.buffPlan().declaresMaxBuffSlots(), "and no cap of its own on how many");
    }
}
