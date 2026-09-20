package com.pvzce.server;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.WaveDef;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceConstants;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.network.PvzcePacket;
import com.pvzce.common.tag.TestContent;
import com.pvzce.server.entity.ZombieEntity;
import com.pvzce.server.level.LevelServer;
import com.pvzce.testutil.TestLevels;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Night graves open at the last wave.
 *
 * <p>The tombstones on a night lawn give up one zombie each when the final wave arrives - a
 * burst of dirt and an arm first, the zombie a second later - and the level's own
 * {@code graves_spawn_night} rule is what turns that off. What is pinned here is the counting:
 * one zombie per grave, none before the dirt settles, none at all when the rule says no or
 * when it is not night.
 */
class GraveRiseTest {
    private static final int ROWS = 5;
    private static final int COLUMNS = 9;
    /** The four cells 2-1 paints tombstones on. */
    private static final int EXPECTED_GRAVES = 4;

    private static LevelDef nightBoard;

    @BeforeAll
    static void load() throws Exception {
        TestContent.loadBuiltInContentAndTags();
        LevelDef source = BuiltInRegistries.LEVELS.get(PvzceIds.id("yard/adventure/2_1"));
        assertNotNull(source, "the shipped 2-1 must load");
        long graves = source.scene().keySet().stream()
                .filter(id -> id.path().startsWith("grave"))
                .mapToLong(id -> source.scene().get(id).size())
                .sum();
        assertEquals(EXPECTED_GRAVES, graves, "the level this test counts against");
        nightBoard = withFinalWaveOnly(source);
    }

    /**
     * 2-1 with a single final wave that arrives immediately.
     *
     * <p>The shipped level has six waves and its first one is a minute away; what matters here
     * is the transition into the last one, and the level's scene is the part being tested.
     */
    private static LevelDef withFinalWaveOnly(LevelDef source) {
        List<WaveDef> waves = List.of(new WaveDef(WaveDef.WaveType.FINAL, 1, 0,
                List.of(new WaveDef.Entry(PvzceIds.id("basic_zombie"), 1)), 15));
        return withRulesAndWaves(source, source.rules(), waves);
    }

    /** The same level with a rule overridden. */
    private static LevelDef withRulesAndWaves(LevelDef source, Map<Identifier, JsonElement> rules,
                                              List<WaveDef> waves) {
        return TestLevels.copy(source).rules(rules).waves(waves).build();
    }

    private static LevelDef withRule(LevelDef source, Identifier rule, boolean value) {
        Map<Identifier, JsonElement> rules = new LinkedHashMap<>(source.rules());
        rules.put(rule, new JsonPrimitive(value));
        return withRulesAndWaves(source, rules, source.waves());
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

    private static long graveZombies(LevelServer level) {
        return level.entities().stream()
                .filter(entity -> entity instanceof ZombieEntity zombie && zombie.isAlive())
                .filter(entity -> {
                    int x = (int) Math.floor(entity.cellX());
                    int y = entity.gridY();
                    var element = level.sceneAt(x, y);
                    return element != null && PvzceIds.SURFACE_GRAVE.equals(element.surfaceClass());
                })
                .count();
    }

    @Test
    void everyGraveGivesUpOneZombieAtTheLastWave() {
        LevelServer level = new LevelServer(nightBoard);
        CapturingBridge bridge = new CapturingBridge();
        assertTrue(level.isNight(), "2-1 is a night level");

        tick(level, bridge, 3);
        // The zombies exist from the tick the wave arrives - they are *climbing*, not queued:
        // that is what lets the client draw them under the lawn and raise them.
        assertEquals(EXPECTED_GRAVES, graveZombies(level), "one zombie per tombstone, and no more");
        assertEquals(EXPECTED_GRAVES + 1, level.aliveZombieCount(),
                "the wave's own entry still arrives: the graves add to it, they do not replace it");
    }

    /** A zombie coming out of a grave is under the lawn, and stays there while it climbs. */
    @Test
    void theRisenZombieClimbsOutOfTheGround() {
        LevelServer level = new LevelServer(nightBoard);
        CapturingBridge bridge = new CapturingBridge();
        tick(level, bridge, 2);

        ZombieEntity riser = graveZombie(level);
        assertNotNull(riser, "a grave zombie exists as soon as the wave arrives");
        assertTrue(riser.riseTicks() > ZombieEntity.RISE_TICKS / 2,
                "and it has most of its climb ahead of it: " + riser.riseTicks());
        assertTrue(riser.height() < 0F, "drawn below its cell: " + riser.height());
        float buriedX = riser.cellX();

        tick(level, bridge, ZombieEntity.RISE_TICKS / 2);
        assertTrue(riser.height() < 0F, "still climbing at the halfway mark");
        assertTrue(riser.height() > -PvzceConstants.ZOMBIE_RISE_DEPTH_CELLS,
                "and closer to the surface");
        assertEquals(buriedX, riser.cellX(), 0.0001F, "a climbing zombie does not walk");

        tick(level, bridge, ZombieEntity.RISE_TICKS);
        assertEquals(0F, riser.height(), 0.0001F, "it is standing on the lawn");
        assertTrue(riser.isAlive());
        tick(level, bridge, 2);
        assertTrue(riser.cellX() < buriedX, "and it walks off towards the house");
    }

    /** The first zombie found standing on a grave. */
    private static ZombieEntity graveZombie(LevelServer level) {
        for (var entity : level.entities()) {
            if (entity instanceof ZombieEntity zombie && zombie.isAlive()) {
                var element = level.sceneAt((int) Math.floor(zombie.cellX()), zombie.gridY());
                if (element != null && PvzceIds.SURFACE_GRAVE.equals(element.surfaceClass())) {
                    return zombie;
                }
            }
        }
        return null;
    }

    /**
     * The same farewell on 2-5, whose graves are raised by its own mechanic.
     *
     * <p>Whack-a-Zombie keeps a lawn full of tombstones and the last wave is meant to open
     * <em>every one of them</em> - it is the level's whole climax. The count is the point: a
     * farewell that only opens some of the stones is not the wave the level was designed
     * around, and this level's graves are not painted once at load but kept up by
     * {@code grave_spawner}, so "which cells hold a stone" is a different question here than
     * it is on 2-1.
     */
    @Test
    void everyStandingGraveOpensOnWhackAZombiesLastWave() {
        LevelDef source = BuiltInRegistries.LEVELS.get(PvzceIds.id("yard/adventure/2_5"));
        assertNotNull(source, "the shipped 2-5 must load");
        LevelServer level = new LevelServer(withFinalWaveOnly(source));
        CapturingBridge bridge = new CapturingBridge();
        assertTrue(level.isNight(), "2-5 is a night level");

        tick(level, bridge, 3);
        long graves = level.graveCells().size();
        long risen = graveZombies(level);
        assertTrue(graves > 0, "the level ships tombstones");
        assertEquals(graves, risen, "every stone gives up one zombie, and no stone gives two");
    }

    @Test
    void theRuleTurnsTheGravesOff() {
        LevelDef quiet = withRule(nightBoard, PvzceIds.RULE_GRAVES_SPAWN_NIGHT, false);
        LevelServer level = new LevelServer(quiet);
        CapturingBridge bridge = new CapturingBridge();
        tick(level, bridge, ZombieEntity.RISE_TICKS + 10);
        assertEquals(0, graveZombies(level), "a level whose graves are scenery says so with the rule");
    }

    @Test
    void gravesStayShutInDaylight() {
        LevelDef day = withRulesAndWaves(nightBoard,
                Map.of(PvzceIds.RULE_DAY_LENGTH, new JsonPrimitive(0),
                        PvzceIds.RULE_NIGHT_LENGTH, new JsonPrimitive(-1)),
                nightBoard.waves());
        LevelServer level = new LevelServer(day);
        CapturingBridge bridge = new CapturingBridge();
        tick(level, bridge, ZombieEntity.RISE_TICKS + 10);
        assertEquals(0, graveZombies(level), "a tombstone in daylight is a headstone, not a door");
    }

    @Test
    void theBoardIsStillTheShippedSize() {
        LevelServer level = new LevelServer(nightBoard);
        assertEquals(COLUMNS, level.width());
        assertEquals(ROWS, level.height());
    }
}
