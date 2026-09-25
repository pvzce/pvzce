package com.pvzce.common.level.mutation;

import com.google.gson.JsonPrimitive;
import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.MutationData;
import com.pvzce.api.content.mechanic.TypedMechanic;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.tag.TestContent;
import com.pvzce.server.entity.ZombieEntity;
import com.pvzce.server.level.LevelServer;
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
 * The mutation tutorial: a lesson with a script, not a run with dice.
 *
 * <p>What has to hold for it to be a lesson at all: the dice are off, the four mutations arrive in
 * the order somebody chose, each one is followed by the line that explains it, and the level can be
 * <em>finished</em> - a tutorial that never ends is a tutorial nobody leaves.
 */
class MutationTutorialTest {
    @BeforeAll
    static void load() throws Exception {
        TestContent.loadBuiltInContentAndTags();
    }

    /** The level is a short day lawn on the endless page, and it is not endless itself. */
    @Test
    void theTutorialIsAShortLevelThatCanBeWon() {
        LevelDef def = tutorial();
        assertEquals(9, def.width());
        assertEquals(5, def.height(), "the front lawn: five rows");
        assertFalse(def.scene().containsKey(PvzceIds.WATER), "a day lawn has no water");
        assertEquals(6, def.waves().size(), "six waves and the level is over");
        assertEquals("FINAL", def.waves().get(5).type().name(),
                "and the last one is a final wave, not another round");
        assertFalse(def.mechanics().stream()
                        .anyMatch(mechanic -> mechanic.type().equals(PvzceIds.MECHANIC_ENDLESS)),
                "no endless mechanic: this level ends");
        assertEquals(6, def.slots().size(), "the fixed deck: sun, three plants, a bomb and a shovel");
    }

    /** The script: dice off, four entries, in the order the lesson tells them. */
    @Test
    void theScriptIsFixedAndCoversTheFourKinds() {
        MutationData data = BuiltInRegistries.LEVELS.get(MutationLevels.tutorialId()).mechanics()
                .stream()
                .filter(mechanic -> mechanic.type().equals(PvzceIds.MECHANIC_MUTATION))
                .map(TypedMechanic::value)
                .filter(MutationData.class::isInstance)
                .map(MutationData.class::cast)
                .findFirst().orElse(null);
        assertNotNull(data, "the tutorial declares a mutation block");
        assertFalse(data.random(), "a lesson whose examples are rolled is not a lesson");
        assertEquals(4, data.schedule().size());
        assertEquals(List.of(PvzceIds.MUTATION_SLOT_REPLACE, PvzceIds.MUTATION_SUN_RATE,
                        PvzceIds.MUTATION_BOWLING_NUT, PvzceIds.MUTATION_CONVEYOR),
                data.schedule().stream().map(MutationData.Planned::id).toList(),
                "a card rewrite, a number, a mini-game, a dealer: one of each kind");
        for (MutationData.Planned planned : data.schedule()) {
            assertNotNull(MutationRegistry.get(planned.id()),
                    planned.id() + " has to exist, or the script stalls on a no-op");
        }
        assertTrue(data.validate().isEmpty(), "and the block is well formed: " + data.validate());
    }

    /** Playing it: each staged mutation arrives on its own tick, and in order. */
    @Test
    void theStagedMutationsArriveOnTheirTicksInOrder() {
        LevelServer level = tutorialLevel();
        MutationData data = level.mutationData();
        assertNotNull(data);
        List<Identifier> arrived = new ArrayList<>();
        int tick = 0;
        for (MutationData.Planned planned : data.schedule()) {
            while (tick < planned.atTick()) {
                level.tick(packet -> { });
                killAll(level);
                tick++;
            }
            for (Identifier id : level.mutations().activeIds()) {
                if (!arrived.contains(id)) {
                    arrived.add(id);
                }
            }
            assertTrue(arrived.contains(planned.id()),
                    planned.id() + " should have arrived by tick " + tick + ", field holds "
                            + level.mutations().activeIds());
        }
        assertEquals(data.schedule().size(), level.mutations().scheduledFired());
        // And no dice: over the whole script nothing else may appear.
        assertEquals(data.schedule().size(), level.mutations().activeIds().size(),
                "a level that stages its mutations must not roll any: "
                        + level.mutations().activeIds());
    }

    /** The guide speaks: two lines before it starts and one for each staged mutation. */
    @Test
    void peaChanExplainsEveryStagedMutation() {
        LevelDef def = tutorial();
        assertEquals(2, def.dialogue().lines().size(), "the opening conversation is two lines");
        assertEquals(4, def.dialogue().timed().size(), "and there is one line per staged mutation");
        MutationData data = def.mechanics().stream()
                .filter(mechanic -> mechanic.type().equals(PvzceIds.MECHANIC_MUTATION))
                .map(TypedMechanic::value)
                .filter(MutationData.class::isInstance)
                .map(MutationData.class::cast)
                .findFirst().orElseThrow();
        for (int i = 0; i < def.dialogue().timed().size(); i++) {
            var line = def.dialogue().timed().get(i);
            assertTrue(line.atTick() >= data.schedule().get(i).atTick(),
                    "the line about " + data.schedule().get(i).id() + " has to come after it:"
                            + " a lesson that explains a change before it happens is a lecture");
            assertEquals(Identifier.withDefaultNamespace("pea_chan"), line.line().character(),
                    "the guide is the user's own 豌豆酱");
            assertFalse(line.line().text().isBlank(), "a timed line with no words is a pause");
        }
    }

    /** Six waves later the level is won, and the run is not a round of endless. */
    @Test
    void theLevelIsWonAfterSixWaves() {
        LevelServer level = tutorialLevel();
        for (int i = 0; i < 120_000 && level.gameState().equals(
                com.pvzce.common.network.packet.GameStateS2C.RUNNING); i++) {
            level.tick(packet -> { });
            killAll(level);
        }
        assertFalse(level.gameState().equals(
                        com.pvzce.common.network.packet.GameStateS2C.RUNNING),
                "the level has to end");
        assertEquals(com.pvzce.common.network.packet.GameStateS2C.WON, level.gameState(),
                "and the player wins it by surviving six waves: " + level.gameState());
    }

    private static LevelDef tutorial() {
        LevelDef def = BuiltInRegistries.LEVELS.get(MutationLevels.tutorialId());
        assertNotNull(def, "the tutorial has to be registered");
        return def;
    }

    /** The tutorial with the sky quiet, so the test drives the clock itself. */
    private static LevelServer tutorialLevel() {
        // The sky off, so the test drives the clock - and the wave pacing left at its own speed,
        // because the script is written against the real pacing: the level runs about seven and a
        // half thousand ticks, and the last staged mutation is due at six thousand. Speeding the
        // waves up would end the level before its own lesson did.
        LevelDef def = TestLevels.copy(tutorial())
                .rules(java.util.Map.of(
                        PvzceIds.RULE_SUN_SPAWN_INTERVAL_MIN, new JsonPrimitive(0),
                        PvzceIds.RULE_SUN_SPAWN_INTERVAL_MAX, new JsonPrimitive(0)))
                .build();
        return new LevelServer(def, List.of(PvzceIds.id("sun"), PvzceIds.id("pea_shooter"),
                PvzceIds.id("sunflower"), PvzceIds.id("wall_nut")));
    }

    private static void killAll(LevelServer level) {
        for (var entity : level.entities()) {
            if (entity instanceof ZombieEntity zombie && !zombie.isRemoved()) {
                zombie.damageBody(10_000, level);
            }
        }
    }
}
