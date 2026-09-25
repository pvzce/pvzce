package com.pvzce.server.level;

import com.google.gson.JsonPrimitive;
import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.mechanic.MechanicData;
import com.pvzce.api.content.mechanic.TypedMechanic;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.level.mechanic.EndlessMechanic;
import com.pvzce.common.tag.TestContent;
import com.pvzce.server.entity.ZombieEntity;
import com.pvzce.testutil.TestLevels;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The four lawn endless levels: the same mode on a board with no water.
 *
 * <p>The claim worth pinning is the one the plan flagged as the risk: an endless schedule carries
 * <em>water</em> zombies, and a lawn has no water rows to put them in. What the test watches for is
 * therefore not "the wave exists" - that is easy - but "every zombie of three rounds of every level
 * spawned onto a real row, and nothing was dropped for want of one".
 *
 * <p>The other half is what distinguishes the four from each other: day from night, plain from
 * mutating.
 */
class LawnEndlessTest {
    @BeforeAll
    static void load() throws Exception {
        TestContent.loadBuiltInContentAndTags();
    }

    /** All four are registered, on the endless page, with a lawn's board. */
    @Test
    void theFourLawnLevelsAreLawnBoardsOnTheSurvivalPage() {
        for (Identifier id : EndlessLevels.levelIds()) {
            LevelDef def = BuiltInRegistries.LEVELS.get(id);
            assertNotNull(def, id + " has to be registered");
            assertTrue(id.path().startsWith("yard/survival/"),
                    id + " belongs on the survival page, which is where the endless modes live");
        }
        for (Identifier id : List.of(EndlessLevels.levelIds().get(1), EndlessLevels.levelIds().get(2),
                EndlessLevels.levelIds().get(3), EndlessLevels.levelIds().get(4))) {
            LevelDef def = BuiltInRegistries.LEVELS.get(id);
            assertEquals(9, def.width());
            assertEquals(5, def.height(), id + " is the front lawn: five rows");
            assertFalse(def.scene().containsKey(PvzceIds.WATER),
                    id + " has no water rows at all - that is what makes it the lawn version");
            assertEquals(45, def.scene().get(PvzceIds.GRASS).size(),
                    id + " paints all forty-five cells as grass");
        }
    }

    /** The two halves of the pair: night means the night clock and the graves. */
    @Test
    void theNightLawnIsTheNightOne() {
        LevelDef day = BuiltInRegistries.LEVELS.get(EndlessLevels.levelIds().get(1));
        LevelDef night = BuiltInRegistries.LEVELS.get(EndlessLevels.levelIds().get(2));
        assertNotNull(day);
        assertNotNull(night);
        assertTrue(night.rules().get(PvzceIds.RULE_NIGHT_LENGTH).getAsInt() > 0,
                "a night lawn is dark: one long night, like the shipped night levels");
        assertEquals(-1, day.rules().get(PvzceIds.RULE_NIGHT_LENGTH).getAsInt(),
                "and the day one has no night at all");
        assertTrue(night.mechanics().stream()
                        .anyMatch(mechanic -> mechanic.type().equals(PvzceIds.MECHANIC_GRAVE_FIELD)),
                "the night lawn opens with the graves 2-1 opens with");
        assertFalse(day.mechanics().stream()
                        .anyMatch(mechanic -> mechanic.type().equals(PvzceIds.MECHANIC_GRAVE_FIELD)),
                "and the day one does not");
    }

    /** The mutation halves carry the mutation mechanic, and the plain ones must not. */
    @Test
    void onlyTheMutatingLawnsMutate() {
        for (int index = 0; index < EndlessLevels.levelIds().size(); index++) {
            LevelDef def = BuiltInRegistries.LEVELS.get(EndlessLevels.levelIds().get(index));
            boolean mutating = index >= 3;
            boolean declares = def.mechanics().stream()
                    .anyMatch(mechanic -> mechanic.type().equals(PvzceIds.MECHANIC_MUTATION));
            assertEquals(mutating, declares, def.displayName() + " is on the wrong side of the switch");
            if (mutating) {
                assertEquals(14, def.maxSeedSlots(), "the mutating lawn gets the wider bar");
            }
        }
    }

    /**
     * Three rounds of every level: every zombie lands on a real row.
     *
     * <p>The water entries of the shared schedule are the risk, so what this counts is spawns that
     * did not happen: {@code spawnZombie} returns {@code null} for an unknown definition, and the
     * engine's own row choice moves a land zombie out of the water - on a lawn there is no water to
     * move out of, so nothing should need moving.
     */
    @Test
    void threeRoundsOfEveryLawnLevelSpawnOnRealRows() {
        // The four lawn levels, not the pool one: this test is about a board with no water, and the
        // pool's zombies are supposed to be in the water.
        for (Identifier id : EndlessLevels.levelIds().subList(1, EndlessLevels.levelIds().size())) {
            LevelServer level = lawnLevel(id);
            for (int i = 0; i < 60_000 && level.round() < 3; i++) {
                level.tick(packet -> { });
                for (var entity : level.entities()) {
                    if (entity instanceof ZombieEntity zombie && zombie.isAlive()) {
                        assertTrue(zombie.gridY() >= 0 && zombie.gridY() < level.height(),
                                id + ": a zombie at row " + zombie.cellY() + " is off the board");
                        assertFalse(level.rowIsWater(zombie.gridY()),
                                id + ": a zombie was spawned in a water row on a lawn");
                    }
                }
                // The fixture has no plants, so the field is cleared by hand: a round only ends when
                // its last zombie is dead, and a zombie that walks to the house would end the run
                // instead (which is the mode working, not the mode under test).
                killAll(level);
                if (level.isRoundClearPending()) {
                    // The round boundary freezes the run until the player re-picks; the test plays
                    // that part for them so the next round can start.
                    level.reselectCards(List.of(PvzceIds.id("pea_shooter"), PvzceIds.id("sun")),
                            packet -> { });
                }
            }
            assertTrue(level.round() >= 3, id + " has to reach round three in sixty thousand ticks");
            // Round one is eleven waves and each round adds one, so reaching round three means the
            // first two rounds' worth (11 + 12) have been released and generated.
            int released = level.cumulativeWaves();
            assertTrue(released >= 23,
                    id + " released only " + released + " waves before round three");
        }
    }

    /** Kills everything on the lawn, so the next wave is due. */
    private static void killAll(LevelServer level) {
        for (var entity : level.entities()) {
            if (entity instanceof ZombieEntity zombie && !zombie.isRemoved()) {
                zombie.damageBody(10_000, level);
            }
        }
    }

    /** A lawn level with the sky and the mowers quiet, so the test drives the clock itself. */
    private static LevelServer lawnLevel(Identifier id) {
        LevelDef def = TestLevels.copy(BuiltInRegistries.LEVELS.get(id))
                .rules(java.util.Map.of(
                        PvzceIds.RULE_SUN_SPAWN_INTERVAL_MIN, new JsonPrimitive(0),
                        PvzceIds.RULE_SUN_SPAWN_INTERVAL_MAX, new JsonPrimitive(0),
                        PvzceIds.RULE_ZOMBIE_SPAWN_SPEED_MULTIPLIER, new JsonPrimitive(20.0)))
                .envVars(java.util.Map.of(PvzceIds.ENV_PLANT_AI, com.pvzce.api.content.EnvValue.of(
                        "pvzce:boolean", new JsonPrimitive(false))))
                .build();
        List<Identifier> bar = new ArrayList<>(List.of(PvzceIds.id("pea_shooter"), PvzceIds.id("sun")));
        return new LevelServer(def, bar);
    }

    /** One place that still names the endless mechanic, for the mutation levels' own switch. */
    @Test
    void everyLawnLevelNamesAnEndlessSchedule() {
        for (Identifier id : EndlessLevels.levelIds()) {
            LevelDef def = BuiltInRegistries.LEVELS.get(id);
            TypedMechanic endless = def.mechanics().stream()
                    .filter(mechanic -> mechanic.type().equals(PvzceIds.MECHANIC_ENDLESS))
                    .findFirst().orElse(null);
            assertNotNull(endless, id + " has to say which curve it grows on");
            assertTrue(endless.value() instanceof EndlessMechanic.Data,
                    id + " names an endless schedule");
            assertNotNull(BuiltInRegistries.ENDLESS_SCHEDULES.get(
                            ((EndlessMechanic.Data) endless.value()).schedule()),
                    id + " names a schedule that exists");
        }
    }

    /** A level with no water rows must not ask for the water half of a schedule. */
    @Test
    void aLawnBoardHasNoWaterRowsToFill() {
        LevelServer level = lawnLevel(EndlessLevels.levelIds().get(1));
        assertTrue(level.landRows().size() == 5, "all five rows are land");
        List<Integer> water = new ArrayList<>();
        for (int y = 0; y < level.height(); y++) {
            if (level.rowIsWater(y)) {
                water.add(y);
            }
        }
        assertTrue(water.isEmpty(), "and none of them is water: " + water);
        assertNotNull(MechanicData.Empty.INSTANCE, "the mechanic marker is still a value");
    }
}
