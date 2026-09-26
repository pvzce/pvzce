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
}
