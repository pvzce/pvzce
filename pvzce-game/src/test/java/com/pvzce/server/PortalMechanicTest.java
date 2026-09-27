package com.pvzce.server;

import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.PortalData;
import com.pvzce.api.content.mechanic.TypedMechanic;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.network.PvzcePacket;
import com.pvzce.common.tag.TestContent;
import com.pvzce.server.entity.ZombieEntity;
import com.pvzce.server.level.LevelServer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 斗转星移: a zombie that walks into one portal comes out of the other.
 *
 * <p>Three facts, and the third is the one that makes the mechanic usable rather than a trap: it
 * travels, it arrives in the lane the other end names, and it does not immediately fall back
 * through the portal it just came out of.
 */
class PortalMechanicTest {
    private static final Identifier ZOMBIE_TEAM = PvzceIds.ZOMBIE_TEAM;
    private static final Identifier BASIC_ZOMBIE = PvzceIds.id("basic_zombie");

    @BeforeAll
    static void load() throws Exception {
        TestContent.loadBuiltInContentAndTags();
    }

    /** Five lanes, no waves, and one pair: (6,0) sends a zombie to (2,3). */
    private static LevelServer level(PortalData portals) {
        LevelDef demo = BuiltInRegistries.LEVELS.get(PvzceIds.id("yard/adventure/demo_level"));
        assertNotNull(demo, "demo_level must load");
        List<String> cells = new ArrayList<>();
        for (int y = 0; y < 5; y++) {
            for (int x = 0; x < 9; x++) {
                cells.add(x + "," + y);
            }
        }
        java.util.Map<Identifier, com.google.gson.JsonElement> rules =
                new java.util.LinkedHashMap<>(demo.rules());
        rules.put(PvzceIds.RULE_SUN_SPAWN_INTERVAL_MIN, new com.google.gson.JsonPrimitive(0));
        rules.put(PvzceIds.RULE_SUN_SPAWN_INTERVAL_MAX, new com.google.gson.JsonPrimitive(0));
        LevelDef def = com.pvzce.testutil.TestLevels.copy(demo)
                .height(5)
                .scene(java.util.Map.of(PvzceIds.GRASS, cells))
                .rules(rules)
                .slots(List.of())
                .initialSun(0)
                .waves(List.of())
                .mechanics(List.of(new TypedMechanic(PvzceIds.MECHANIC_PORTAL, portals)))
                .build();
        return new LevelServer(def);
    }

    private static final class CapturingBridge implements LevelServer.ServerBridge {
        final List<PvzcePacket> packets = new ArrayList<>();

        @Override
        public void send(PvzcePacket packet) {
            packets.add(packet);
        }
    }

    private static void tick(LevelServer level, CapturingBridge bridge, int ticks) {
        for (int i = 0; i < ticks; i++) {
            level.tick(bridge);
            level.flushPending(bridge);
        }
    }

    private static ZombieEntity zombie(LevelServer level, CapturingBridge bridge, float x, int row) {
        ZombieEntity spawned = level.spawnZombie(BASIC_ZOMBIE, level.team(ZOMBIE_TEAM), x, row);
        level.flushPending(bridge);
        return spawned;
    }

    private static int rowOf(ZombieEntity zombie) {
        return zombie.gridY();
    }

    @Test
    void aZombieThatWalksInComesOutOfTheOtherEnd() {
        LevelServer level = level(new PortalData(List.of(new PortalData.Pair(6, 0, 2, 3))));
        CapturingBridge bridge = new CapturingBridge();
        ZombieEntity walker = zombie(level, bridge, 8.0F, 0);

        tick(level, bridge, 600);

        assertEquals(3, rowOf(walker), "the zombie came out in the lane the other end names");
        assertTrue(walker.cellX() < 2.5F, "and to the left of that end's centre: " + walker.cellX());
    }

    /** The other end works the same way: which way a pair is used is which end is walked into. */
    @Test
    void thePairWorksInBothDirections() {
        LevelServer level = level(new PortalData(List.of(new PortalData.Pair(6, 0, 2, 3))));
        CapturingBridge bridge = new CapturingBridge();
        ZombieEntity walker = zombie(level, bridge, 4.5F, 3);

        tick(level, bridge, 600);

        assertEquals(0, rowOf(walker), "walking into (2,3) sends it to (6,0)");
        assertTrue(walker.cellX() < 6.5F, "and past that end's centre: " + walker.cellX());
    }

    /**
     * The zombie does not fall back through the portal it just came out of.
     *
     * <p>The exit sits left of its own trigger, so without the immunity a zombie would be sent
     * straight back the moment it arrived - the pair would be a wall rather than a door.
     */
    @Test
    void aTeleportedZombieDoesNotBounceStraightBack() {
        LevelServer level = level(new PortalData(List.of(new PortalData.Pair(6, 0, 2, 3))));
        CapturingBridge bridge = new CapturingBridge();
        ZombieEntity walker = zombie(level, bridge, 8.0F, 0);

        tick(level, bridge, 600);

        assertEquals(3, rowOf(walker), "it stays in the lane it arrived in");
    }

    /** A pair that names a cell off the board is a data mistake, and it is reported as one. */
    @Test
    void aPortalOffTheBoardIsRefusedByTheValidator() {
        com.pvzce.common.level.mechanic.PortalMechanic mechanic =
                new com.pvzce.common.level.mechanic.PortalMechanic();
        LevelDef def = level(new PortalData(List.of())).def();
        List<String> errors = mechanic.validate(def,
                new PortalData(List.of(new PortalData.Pair(9, 0, 2, 3))));
        assertTrue(errors.stream().anyMatch(message -> message.contains("off a")),
                "a portal outside the board is reported: " + errors);
    }

    /**
     * The shipped mini-game's own portals: the level the player picks is the one that travels.
     *
     * <p>Everything above builds a board to test the mechanic with. These two load
     * {@code yard/minigame/portal_combat} as it ships and walk a zombie through <em>its</em>
     * pairs, because a level whose portals were mis-numbered would still pass every test above -
     * the mechanic would be working perfectly on a board whose two ends are in the same lane,
     * which is a level that plays like an ordinary one.
     *
     * <p>One board per door, and each is walked for the shortest time that gets a zombie through:
     * a zombie that reaches the house trips the mower, the lawn goes clear and the level ends, and
     * a second zombie spawned before that simply stops moving. Two doors on one board therefore
     * measure the first door twice.
     */
    private static LevelServer shippedPortalLevel() {
        LevelDef shipped = BuiltInRegistries.LEVELS.get(PvzceIds.id("yard/minigame/portal_combat"));
        assertNotNull(shipped, "portal_combat must load");
        return new LevelServer(com.pvzce.testutil.TestLevels.copy(shipped)
                .waves(List.of())
                .build());
    }

    /** Where a zombie that starts at {@code x} in {@code row} of the shipped level comes out. */
    private static ZombieEntity walkThroughTheShippedDoor(int row, float x) {
        LevelServer level = shippedPortalLevel();
        CapturingBridge bridge = new CapturingBridge();
        ZombieEntity walker = zombie(level, bridge, x, row);
        // Long enough to cross the door and come out the far side, short enough that the walker
        // is still on the lawn: reaching the house would end the level.
        tick(level, bridge, 600);
        return walker;
    }

    @Test
    void theShippedLevelsFirstDoorSendsRowZeroIntoRowThree() {
        ZombieEntity walker = walkThroughTheShippedDoor(0, 8.0F);

        assertEquals(3, rowOf(walker), "a zombie in row 0 leaves through row 3");
        assertTrue(walker.cellX() < 2.5F, "past the exit ring's centre: " + walker.cellX());
    }

    @Test
    void theShippedLevelsSecondDoorSendsRowOneIntoRowFour() {
        ZombieEntity walker = walkThroughTheShippedDoor(1, 8.5F);

        assertEquals(4, rowOf(walker), "a zombie in row 1 leaves through row 4");
        assertTrue(walker.cellX() < 3.5F, "past the exit ring's centre: " + walker.cellX());
    }

    /** Row 2 is the lane the level leaves alone, which is what makes the doors a choice. */
    @Test
    void theShippedLevelPairsFourRingsAndLeavesTheMiddleLaneAlone() {
        LevelDef shipped = BuiltInRegistries.LEVELS.get(PvzceIds.id("yard/minigame/portal_combat"));
        assertNotNull(shipped, "portal_combat must load");
        PortalData portals = shipped.mechanics().stream()
                .filter(block -> block.type().equals(PvzceIds.MECHANIC_PORTAL))
                .map(block -> (PortalData) block.value())
                .findFirst()
                .orElseThrow(() -> new AssertionError("portal_combat declares no portal mechanic"));
        assertEquals(2, portals.pairs().size(), "the level pairs four rings into two doors");
        for (PortalData.Pair pair : portals.pairs()) {
            assertTrue(pair.ay() != 2 && pair.by() != 2,
                    "no ring stands in the middle lane: " + pair);
        }
    }
}
