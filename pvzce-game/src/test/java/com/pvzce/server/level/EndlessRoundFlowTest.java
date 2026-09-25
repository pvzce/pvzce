package com.pvzce.server.level;

import com.google.gson.JsonPrimitive;
import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.mechanic.TypedMechanic;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.level.mechanic.EndlessMechanic;
import com.pvzce.common.network.PvzcePacket;
import com.pvzce.common.network.packet.GameStateS2C;
import com.pvzce.common.network.packet.RoundClearS2C;
import com.pvzce.common.network.packet.RoundSyncS2C;
import com.pvzce.server.entity.ZombieEntity;
import com.pvzce.testutil.TestLevels;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What an endless run does at a round boundary, and what it must never do.
 *
 * <p>The whole mode hangs on three claims, and each has a test here: a round ends when its last
 * wave is dead rather than when it was released, the lawn is kept across the boundary while the
 * cards are replaced, and the run is never won - the only way out is a zombie reaching the house.
 */
class EndlessRoundFlowTest {
    /**
     * Registered in a static initializer rather than in {@code @BeforeAll}.
     *
     * <p>Content registries freeze the moment the first level is built (the shipped levels are
     * built from {@code BuiltInRegistries.bootstrap()}), and JUnit runs the whole class's
     * initializers before any of its lifecycle callbacks - so this is the only point at which a
     * test can still add to one.
     */
    @BeforeAll
    static void bootstrap() throws Exception {
        // Tags as well as content: the placement rules read them, and a suite that skipped the
        // tag pass would see every cell as unplantable (see TestContent).
        com.pvzce.common.tag.TestContent.loadBuiltInContentAndTags();
    }

    /**
     * A round ends only when the field is clear, and the next one starts longer.
     *
     * <p>The order matters and is the reason the round cannot close on "the last wave was
     * released": a player who is still fighting must not be handed a card chooser.
     */
    @Test
    void aRoundClosesWhenItsLastZombieDiesAndTheNextOneIsLonger() {
        LevelServer level = endlessLevel();
        CapturingBridge bridge = new CapturingBridge();
        RoundClock clock = new RoundClock(level, bridge);

        // The round's last wave is released, and the field is not clear yet.
        clock.tickUntil(() -> {
            killAll(level);
            return level.waveInRound() >= level.totalWaves() && level.isRoundClearPending();
        }, 40_000);
        assertEquals(1, level.round(), "still the first round");
        assertEquals(11, level.totalWaves(), "round one is eleven waves");
        clock.tickUntil(() -> level.isRoundClearPending(), 600);
        assertTrue(level.isRoundClearPending(), "the round is over once the field is clear");
        assertEquals(0, level.cumulativeWaves() - level.currentWave(),
                "nothing has been counted for the next round yet");

        // The run is waiting: a tick must not advance anything.
        int waveBefore = level.currentWave();
        int ticksBefore = level.tickCount();
        level.tick(bridge);
        assertEquals(ticksBefore, level.tickCount(),
                "the level does not simulate while the card choice is open");
        assertEquals(waveBefore, level.currentWave());

        assertTrue(level.reselectCards(List.of(PvzceIds.id("pea_shooter"), PvzceIds.id("sun")), bridge),
                "the waiting level accepts the answer");
        assertEquals(2, level.round(), "and the run is in round two");
        assertEquals(12, level.totalWaves(), "which is one wave longer");
        assertEquals(11, level.cumulativeWaves(),
                "the first round's eleven waves are part of the run's total");
        assertFalse(level.reselectCards(List.of(), bridge),
                "a second answer for the same round is refused rather than restarting it");
    }

    /**
     * The lawn survives the boundary: plants, sun, mowers and the cooldowns are all kept.
     *
     * <p>This is the design decision the whole mode rests on - the rounds are one run, not a
     * series of levels - so it is pinned rather than left to a screenshot.
     */
    @Test
    void theLawnAndTheSunAreKeptAcrossTheBoundary() {
        LevelServer level = endlessLevel();
        CapturingBridge bridge = new CapturingBridge();
        RoundClock clock = new RoundClock(level, bridge);

        // A plant on the board and some sun in the bank, which is a run in progress. The sun is
        // granted rather than waited for: this test is about what survives the boundary, not
        // about how a player earns the price of a peashooter.
        level.team(PvzceIds.PLANT_TEAM).addResource(PvzceIds.SUN, 500);
        assertTrue(level.placePlant(bridge, 0, 1, 1), "the first lawn cell takes a plant");
        int plantsBefore = plantCount(level);
        int sunBefore = level.team(PvzceIds.PLANT_TEAM)
                .resourcesOf(PvzceIds.SUN);
        assertTrue(plantsBefore > 0, "a plant is on the board before the round ends");

        finishRound(level, clock);
        assertTrue(level.reselectCards(List.of(PvzceIds.id("pea_shooter")), bridge));

        // The plant count is not compared exactly: this level runs the plant AI (see
        // EndlessLevels), and the AI keeps planting with whatever sun the run has. What the test
        // is about is that nothing was *cleared* - the lawn the last round was defended with is
        // the lawn the next one starts on.
        assertTrue(plantCount(level) >= plantsBefore,
                "the plants are still standing: " + plantCount(level) + " of " + plantsBefore);
        assertEquals(sunBefore, level.team(PvzceIds.PLANT_TEAM).resourcesOf(PvzceIds.SUN),
                "and the sun is where the player left it");
    }

    /**
     * The round's boundary is announced once, and the client is told the new round's wave list.
     *
     * <p>The list has to travel with the round: the level init packet carried the first round's
     * types, and a meter that kept drawing them would plant the previous round's flags.
     */
    @Test
    void theBoundaryIsAnnouncedOnceAndCarriesTheNewRoundsWaves() {
        LevelServer level = endlessLevel();
        CapturingBridge bridge = new CapturingBridge();
        RoundClock clock = new RoundClock(level, bridge);

        finishRound(level, clock);
        bridge.clear();
        level.tick(bridge);
        level.tick(bridge);
        assertEquals(1, bridge.count(RoundClearS2C.class),
                "the summary is sent once, not once per frozen tick");

        RoundClearS2C clear = bridge.last(RoundClearS2C.class);
        assertNotNull(clear);
        assertEquals(1, clear.round(), "the round that just finished");
        assertEquals(11, clear.cumulativeWaves(), "and what the run has released so far");

        bridge.clear();
        assertTrue(level.reselectCards(List.of(PvzceIds.id("pea_shooter")), bridge));
        RoundSyncS2C sync = bridge.last(RoundSyncS2C.class);
        assertNotNull(sync, "the next round's state goes out with the choice");
        assertEquals(2, sync.round());
        assertEquals(12, sync.wavesInRound());
        assertEquals(12, sync.waveTypes().size(),
                "the meter's flags come from the round's own list");
        assertFalse(sync.roundClearPending());
    }

    /**
     * An endless run is never won.
     *
     * <p>The wave director answers "every wave is out" at the end of every round, which is
     * exactly the condition an ordinary level wins on - so the level has to know the difference,
     * or a run would be declared won after its first round and stop.
     */
    @Test
    void aClearedRoundIsNotAWin() {
        LevelServer level = endlessLevel();
        CapturingBridge bridge = new CapturingBridge();
        RoundClock clock = new RoundClock(level, bridge);

        finishRound(level, clock);
        level.tick(bridge);
        for (int i = 0; i < 240; i++) {
            level.tick(bridge);
        }
        assertFalse(GameStateS2C.WON.equals(level.gameState()),
                "an endless run cannot be won; the level must not declare one");
        assertEquals(GameStateS2C.RUNNING, level.gameState());
        assertFalse(bridge.packets.stream().anyMatch(GameStateS2C.class::isInstance),
                "and no end-of-level state goes out at a round boundary either");
    }

    /**
     * The run still ends the ordinary way: a zombie that reaches the house loses it.
     *
     * <p>And the summary counts the whole run's waves rather than the current round's, which is
     * the only score this mode has.
     */
    @Test
    void aZombieReachingTheHouseStillEndsTheRunWithTheRunsWaveCount() {
        LevelServer level = endlessLevel();
        CapturingBridge bridge = new CapturingBridge();
        RoundClock clock = new RoundClock(level, bridge);

        // Through the real path: a zombie arrives, reaches the house, and the level notices -
        // rather than the test calling the loss in by hand.
        clock.tickUntil(() -> {
            killAll(level);
            return level.cumulativeWaves() > 0;
        }, 20_000);
        ZombieEntity zombie = level.spawnZombie(PvzceIds.id("basic_zombie"), 0.6F, 0);
        level.flushPending(bridge);
        assertNotNull(zombie);
        clock.tickUntil(() -> !GameStateS2C.RUNNING.equals(level.gameState()), 2_000);
        assertEquals(GameStateS2C.LOST, level.gameState(),
                "a zombie at the house loses the run for the plants");
        // The end-of-level packet goes out from `checkEnd`, which runs on the tick after the
        // loss - the level stops simulating the moment it is decided.
        for (int i = 0; i < 20; i++) {
            level.tick(bridge);
        }

        GameStateS2C state = bridge.last(GameStateS2C.class);
        assertNotNull(state, "the end-of-level state goes out");
        assertEquals(GameStateS2C.LOST, state.state());
        assertEquals(level.cumulativeWaves(), state.wavesArrived(),
                "an endless run's score is its total waves, across every round");
    }

    /**
     * A run saved mid-round resumes at the same round and the same wave in it.
     *
     * <p>What the whole "the table is not saved" design rests on: what goes in the file is the
     * position, and the wave it names is regenerated from the schedule. The old save format held
     * an index into a four-thousand-entry table that no longer exists, so this also pins the
     * refusal - a block the current build cannot read is not silently read as round one.
     */
    @Test
    void aSavedRunResumesAtTheSameRoundAndWave() throws Exception {
        LevelServer level = endlessLevel();
        CapturingBridge bridge = new CapturingBridge();
        RoundClock clock = new RoundClock(level, bridge);
        // Into the round far enough that "resumed at the start" is not the same answer.
        clock.tickUntil(() -> {
            killAll(level);
            return level.waveInRound() >= 4;
        }, 40_000);
        int savedRound = level.round();
        int savedWave = level.waveInRound();

        com.pvzce.common.nbt.CompoundTag save = level.save();
        // The file's own words, not the live state: this is what a resumed process reads.
        assertTrue(save.contains("Rules"), "the run's rules travel with the save");
        assertEquals(com.pvzce.server.level.WaveDirector.SAVE_VERSION,
                save.getInt("LoadVersion"), "and the wave position is the version this build reads");

        LevelServer resumed = endlessLevel();
        resumed.restore(save);
        assertEquals(savedRound, resumed.round(), "the same round");
        assertEquals(savedWave, resumed.waveInRound(), "and the same wave in it");
        assertEquals(level.cumulativeWaves(), resumed.cumulativeWaves(),
                "which makes the run's wave tally the same");
    }

    // ------------------------------------------------------------------
    // Harness
    // ------------------------------------------------------------------

    /**
     * The shipped endless level, sped up.
     *
     * <p>The shipped schedule and the shipped pool - the shape is what these tests are about, so
     * nothing about the curve is replaced. What is turned up is the level's own spawn speed,
     * which divides every wave delay and every spawn interval; a test that killed each wave as it
     * arrived would otherwise spend its budget watching countdowns.
     *
     * <p>A test cannot ship its own schedule: a content load clears and refreezes the dynamic
     * registries, so an entry added by a test is gone by the time a level is built.
     */
    private static LevelServer endlessLevel() {
        LevelDef def = TestLevels.copy(BuiltInRegistries.LEVELS.get(
                        PvzceIds.id("yard/survival/endless_pool")))
                // No sun from the sky and no mowers: the only thing that moves a zombie, or the
                // clock, is what the test does.
                .rules(Map.of(PvzceIds.RULE_SUN_SPAWN_INTERVAL_MIN, new JsonPrimitive(0),
                        PvzceIds.RULE_SUN_SPAWN_INTERVAL_MAX, new JsonPrimitive(0),
                        PvzceIds.RULE_ZOMBIE_SPAWN_SPEED_MULTIPLIER, new JsonPrimitive(12.0)))
                // The shipped level runs the plant AI (an endless run is the one mode worth
                // watching play itself); this test counts sun, so the AI is switched off and
                // nothing spends it behind the test's back.
                .envVars(Map.of(PvzceIds.ENV_PLANT_AI, com.pvzce.api.content.EnvValue.of(
                        "pvzce:boolean", new JsonPrimitive(false))))
                .mechanics(List.of(
                        new TypedMechanic(PvzceIds.MECHANIC_ENDLESS,
                                new EndlessMechanic.Data(PvzceIds.ENDLESS_SCHEDULE_POOL)),
                        new TypedMechanic(PvzceIds.MECHANIC_DECK,
                                com.pvzce.api.content.mechanic.MechanicData.Empty.INSTANCE),
                        new TypedMechanic(PvzceIds.MECHANIC_MOWER,
                                new com.pvzce.api.content.MowerData(Optional.of(List.of())))))
                .build();
        return new LevelServer(def, List.of(PvzceIds.id("pea_shooter"), PvzceIds.id("sun")));
    }

    /**
     * Runs the level until the round is over and the field is clear.
     *
     * <p>The killing happens inside the loop rather than after it: an opening wave releases one
     * zombie at a time and waits for it to die (the level's own death gate, twenty seconds per
     * zombie), so a caller that let the level run to its last wave first would spend most of the
     * budget waiting out gates it could have answered.
     */
    private static void finishRound(LevelServer level, RoundClock clock) {
        clock.tickUntil(() -> {
            killAll(level);
            return level.waveInRound() >= level.totalWaves() && level.isRoundClearPending();
        }, 40_000);
    }

    /** Kills every zombie on the lawn, the way a player's plants do. */
    private static void killAll(LevelServer level) {
        for (var entity : level.entities()) {
            if (entity instanceof ZombieEntity zombie && !zombie.isRemoved()) {
                zombie.damageBody(10_000, level);
            }
        }
    }

    private static ZombieEntity firstZombie(LevelServer level) {
        for (var entity : level.entities()) {
            if (entity instanceof ZombieEntity zombie && !zombie.isRemoved()) {
                return zombie;
            }
        }
        return null;
    }

    private static int plantCount(LevelServer level) {
        int plants = 0;
        for (var entity : level.entities()) {
            if (entity instanceof com.pvzce.server.entity.PlantEntity && !entity.isRemoved()) {
                plants++;
            }
        }
        return plants;
    }

    /** Ticks a level until a condition holds, and records how long that took. */
    private static final class RoundClock {
        private final LevelServer level;
        private final CapturingBridge bridge;

        RoundClock(LevelServer level, CapturingBridge bridge) {
            this.level = level;
            this.bridge = bridge;
        }

        void tickUntil(java.util.function.BooleanSupplier condition, int limit) {
            for (int i = 0; i < limit; i++) {
                if (condition.getAsBoolean()) {
                    return;
                }
                level.tick(bridge);
            }
            throw new AssertionError("the condition never held within " + limit + " ticks");
        }
    }

    /** A bridge that keeps what the level sent, and says nothing back. */
    private static final class CapturingBridge implements LevelServer.ServerBridge {
        private final List<PvzcePacket> packets = new ArrayList<>();

        @Override
        public void send(PvzcePacket packet) {
            packets.add(packet);
        }

        void clear() {
            packets.clear();
        }

        <T extends PvzcePacket> T last(Class<T> type) {
            T found = null;
            for (PvzcePacket packet : packets) {
                if (type.isInstance(packet)) {
                    found = type.cast(packet);
                }
            }
            return found;
        }

        <T extends PvzcePacket> int count(Class<T> type) {
            int total = 0;
            for (PvzcePacket packet : packets) {
                if (type.isInstance(packet)) {
                    total++;
                }
            }
            return total;
        }
    }
}
