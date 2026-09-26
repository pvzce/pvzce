package com.pvzce.server;

import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.PlantDef;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.network.PvzcePacket;
import com.pvzce.common.tag.TestContent;
import com.pvzce.server.entity.PlantEntity;
import com.pvzce.server.level.LevelServer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two nuts 坚果保龄球2 deals: one that rolls through everything, one that blows up on contact.
 *
 * <p>The explosion is the whole difference between the two cards, and it is the half that is easy
 * to get subtly wrong: a ball that damages what it touches would already look right in a
 * screenshot, and what makes the explosive nut the explosive nut is that the zombie <em>next to</em>
 * the one it hit goes down too. So the test puts a zombie in the lane and another in the row beside
 * it, and asserts the difference between the two nuts on exactly that zombie.
 */
class BowlingNutTest {
    private static final Identifier PLANT_TEAM = PvzceIds.PLANT_TEAM;
    private static final Identifier ZOMBIE_TEAM = PvzceIds.ZOMBIE_TEAM;
    private static final Identifier BASIC_ZOMBIE = PvzceIds.id("basic_zombie");
    private static final Identifier GIANT_NUT = PvzceIds.id("giant_nut");
    private static final Identifier EXPLOSIVE_NUT = PvzceIds.id("explosive_nut");

    private static LevelDef board;

    @BeforeAll
    static void load() throws Exception {
        TestContent.loadBuiltInContentAndTags();
    }

    /** Three rows, no waves: the ball and the zombies are the whole board, driven by the test. */
    private static LevelDef board() {
        if (board != null) {
            return board;
        }
        LevelDef demo = BuiltInRegistries.LEVELS.get(PvzceIds.id("yard/adventure/demo_level"));
        assertNotNull(demo, "demo_level must load");
        List<String> cells = new ArrayList<>();
        for (int y = 0; y < 3; y++) {
            for (int x = 0; x < 9; x++) {
                cells.add(x + "," + y);
            }
        }
        java.util.Map<Identifier, com.google.gson.JsonElement> rules =
                new java.util.LinkedHashMap<>(demo.rules());
        // No sky sun and no graves: nothing on this board but what the test puts there.
        rules.put(PvzceIds.RULE_SUN_SPAWN_INTERVAL_MIN, new com.google.gson.JsonPrimitive(0));
        rules.put(PvzceIds.RULE_SUN_SPAWN_INTERVAL_MAX, new com.google.gson.JsonPrimitive(0));
        board = com.pvzce.testutil.TestLevels.copy(demo)
                .height(3)
                .scene(java.util.Map.of(PvzceIds.GRASS, cells))
                .rules(rules)
                .slots(List.of())
                .initialSun(0)
                .mechanics(List.of())
                // No waves either: a wave that arrives mid-test is a third zombie to explain, and
                // the first version of this fixture failed on exactly that (the demo board's own
                // wave table spawns one at around tick 500).
                .waves(List.of())
                .build();
        return board;
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

    private static PlantEntity nut(LevelServer level, Identifier id, int x, int y) {
        PlantDef def = BuiltInRegistries.PLANTS.get(id);
        assertNotNull(def, id + " must be registered");
        return level.spawnPlant(def, level.team(PLANT_TEAM), x, y);
    }

    private static void zombie(LevelServer level, CapturingBridge bridge, float x, int row) {
        level.spawnZombie(BASIC_ZOMBIE, level.team(ZOMBIE_TEAM), x, row);
        level.flushPending(bridge);
    }

    /** A ball planted at column 0 has to reach column 5 and stop; 600 ticks is ample. */
    private static final int ROLL_TICKS = 600;

    @Test
    void theExplosiveNutTakesTheRowBesideTheOneItHits() {
        LevelServer level = new LevelServer(board());
        CapturingBridge bridge = new CapturingBridge();
        nut(level, EXPLOSIVE_NUT, 0, 1);
        level.flushPending(bridge);
        zombie(level, bridge, 5.0F, 1);
        // The one that matters: beside the target, out of the ball's own reach. A ball that only
        // damaged what it touched would leave this one standing (see the giant-nut case below).
        zombie(level, bridge, 5.0F, 2);

        tick(level, bridge, ROLL_TICKS);

        assertEquals(0, level.aliveZombieCount(),
                "the blast covers the cell beside the one it hit");
    }

    /**
     * The giant nut is the plain one: it flattens what it rolls into and nothing else.
     *
     * <p>Asserted as the difference from the explosive nut rather than on its own, so the two
     * cannot both drift into "somehow kills everything" without one of them failing.
     */
    @Test
    void theGiantNutOnlyTakesWhatItRollsInto() {
        LevelServer level = new LevelServer(board());
        CapturingBridge bridge = new CapturingBridge();
        nut(level, GIANT_NUT, 0, 1);
        level.flushPending(bridge);
        zombie(level, bridge, 5.0F, 1);
        zombie(level, bridge, 5.0F, 2);

        tick(level, bridge, ROLL_TICKS);

        assertEquals(1, level.aliveZombieCount(),
                "a giant nut runs over its own lane and leaves the next one standing");
    }

    /** An explosive nut is spent by its own blast, like every other explosive in the game. */
    @Test
    void theExplosiveNutIsSpentByTheHit() {
        LevelServer level = new LevelServer(board());
        CapturingBridge bridge = new CapturingBridge();
        PlantEntity ball = nut(level, EXPLOSIVE_NUT, 0, 1);
        level.flushPending(bridge);
        zombie(level, bridge, 5.0F, 1);

        tick(level, bridge, ROLL_TICKS);
        // One more second: the blast leaves the nut drawn for its `linger_ticks`, then it goes.
        tick(level, bridge, 60);

        assertTrue(ball.isRemoved(), "the ball itself went with the blast");
        assertEquals(0, level.plantCount(), "and nothing of it is left on the lawn");
    }
}
