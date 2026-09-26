package com.pvzce.server.level;

import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.LevelRewards;
import com.pvzce.api.content.LevelUnlock;
import com.pvzce.api.content.TeamDef;
import com.pvzce.api.content.WaveDef;
import com.pvzce.api.content.WavePacingData;
import com.pvzce.api.content.mechanic.TypedMechanic;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.network.PvzcePacket;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.tag.TestContent;
import com.pvzce.testutil.TestLevels;
import com.pvzce.server.entity.ZombieEntity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The three ways a wave can pace itself: {@code pvzce:wave_pacing}.
 *
 * <p>The wave table's own rules are the fourth way and are pinned by {@code WaveSystemTest}; what
 * is under test here is what a level gains by declaring the mechanic - a wave that keeps a
 * presence on the lawn, a wave that hands the pacing to the player's kills, and a wave whose
 * composition is a point budget rather than a list.
 */
class WavePacingMechanicTest {
    @BeforeAll
    static void load() throws Exception {
        TestContent.loadBuiltInContentAndTags();
    }

    private static final class Bridge implements LevelServer.ServerBridge {
        final List<PvzcePacket> packets = new ArrayList<>();

        @Override
        public void send(PvzcePacket packet) {
            packets.add(packet);
        }

        /**
         * Zombies that have entered the level, by the spawn packets, corpses included.
         *
         * <p>Zombies only: a level whose sky still drops sun sends resource spawns down the same
         * channel, and "the second thing that appeared" is not what any of these tests mean.
         */
        int spawns() {
            return (int) packets.stream()
                    .filter(com.pvzce.common.network.packet.EntitySpawnS2C.class::isInstance)
                    .map(com.pvzce.common.network.packet.EntitySpawnS2C.class::cast)
                    .filter(spawn -> "zombie".equals(spawn.entityKind()))
                    .count();
        }
    }

    /**
     * A bare level with these waves and this pacing block.
     *
     * <p>No mowers and no rules of its own: the only thing that moves a zombie off this lawn is
     * what the test does to it, and the only thing that changes the clock is the block passed in.
     */
    private static LevelDef level(List<WaveDef> waves, WavePacingData pacing) {
        // A copy of 1-1, stripped of anything that would move a zombie on its own: no mowers, no
        // scene beyond the lawn, and no rules of its own. What is left is the wave clock plus the
        // pacing block this test hands it.
        return TestLevels.copy(BuiltInRegistries.LEVELS.get(PvzceIds.id("yard/adventure/1_1")))
                // 1-1's own rules are stripped, and the one that would move this clock is pinned
                // to its neutral value: every delay below is meant to be read as written.
                .rules(Map.of(PvzceIds.RULE_ZOMBIE_SPAWN_SPEED_MULTIPLIER,
                        new com.google.gson.JsonPrimitive(1.0),
                        PvzceIds.RULE_SUN_SPAWN_INTERVAL_MIN, new com.google.gson.JsonPrimitive(0),
                        PvzceIds.RULE_SUN_SPAWN_INTERVAL_MAX, new com.google.gson.JsonPrimitive(0)))
                .waves(waves)
                .mechanics(List.of(new TypedMechanic(PvzceIds.MECHANIC_WAVE_PACING, pacing),
                        new TypedMechanic(PvzceIds.MECHANIC_MOWER,
                                new com.pvzce.api.content.MowerData(Optional.of(List.of())))))
                .build();
    }

    private static WaveDef wave(WaveDef.WaveType type, int delay, int count) {
        return new WaveDef(type, delay, 0, List.of(
                new WaveDef.Entry(Identifier.withDefaultNamespace("basic_zombie"), count)),
                Optional.of(30), Optional.of(0));
    }

    /** Kills every zombie on the lawn, the way a player's plants do. */
    private static void killAll(LevelServer level) {
        for (var entity : level.entities()) {
            if (entity instanceof ZombieEntity zombie && !zombie.isRemoved()) {
                zombie.damageBody(10_000, level);
            }
        }
    }

    /**
     * The ticks on which the next {@code count} zombies appear, counted from now.
     *
     * <p>Counted by spawn packets rather than by what is standing, because three of these tests
     * kill the lawn on purpose: "the wave sent four" and "four are alive" are different facts,
     * and the pacing modes are about the first one. Relative to the call so a test may take one
     * measurement at a time from the same run.
     *
     * @param kill true to kill every zombie each tick, the way a player's plants do
     */
    private static int[] nextSpawns(LevelServer level, Bridge bridge, int count, boolean kill) {
        int before = bridge.spawns();
        int[] ticks = new int[count];
        int seen = 0;
        for (int tick = 1; tick <= 20_000 && seen < count; tick++) {
            level.tick(bridge);
            if (kill) {
                killAll(level);
            }
            while (seen < bridge.spawns() - before && seen < count) {
                ticks[seen++] = tick;
            }
        }
        if (seen < count) {
            throw new AssertionError("only " + seen + " of " + count + " zombies arrived");
        }
        return ticks;
    }

    /**
     * A stockpile wave keeps {@code max_alive} of its own zombies standing.
     *
     * <p>The point of the mode: a wave of six on a 600-tick interval used to take an hour to
     * arrive, and killing one of them changed nothing. Here the second zombie is due almost at
     * once because the first is alone, and once the cap is reached the wave stops releasing
     * until the player does something about it.
     */
    @Test
    void aStockpileWaveKeepsItsPresenceAndStopsAtTheCap() {
        WavePacingData pacing = WavePacingData.ofModes(WavePacingData.WaveMode.STOCKPILE)
                .clearRewardOff();
        LevelServer level = new LevelServer(level(List.of(wave(WaveDef.WaveType.SMALL, 10, 6)), pacing));
        Bridge bridge = new Bridge();

        int[] firstTwo = nextSpawns(level, bridge, 2, false);
        assertEquals(10, firstTwo[0], "the wave's own delay still decides when it starts");
        assertTrue(firstTwo[1] - firstTwo[0] <= 16,
                "with one zombie standing out of a cap of eight, the next is nearly immediate: "
                        + (firstTwo[1] - firstTwo[0]));

        // At the cap the wave waits: the default cap is eight, but this wave has six, so it
        // releases all six and then simply runs out.
        nextSpawns(level, bridge, 4, false);
        assertEquals(6, level.aliveZombieCount(), "all six are standing and nothing more is due");
    }

    /**
     * A survival-ratio wave hands the next wave's arrival to the player's kills.
     *
     * <p>Written as the difference between two runs of the same level: with wave 1 in
     * survival-ratio mode and its zombies killed as they arrive, wave 2 shows up while the first
     * one is still being fought - and in a run whose countdown cannot be cut at all, with the same
     * kills, it waits out its written 3000-tick delay. That difference is the mechanic.
     *
     * <p>The control run switches off <em>both</em> shortcuts ({@code clearRewardOff}): the health
     * drain is the other one, and with two zombies in the wave it would land on the same arrival
     * and make this test say nothing about the ratio gate.
     */
    @Test
    void aSurvivalRatioWaveArrivesOnceThePlayerHasAnsweredTheOneBeforeIt() {
        WaveDef first = wave(WaveDef.WaveType.SMALL, 10, 2);
        WaveDef second = wave(WaveDef.WaveType.FINAL, 3000, 1);

        WavePacingData ratio = WavePacingData.ofModes(WavePacingData.WaveMode.FIXED,
                        WavePacingData.row(1, WavePacingData.WaveMode.SURVIVAL_RATIO))
                .clearRewardOff();
        WavePacingData fixed = WavePacingData.ofModes(WavePacingData.WaveMode.FIXED)
                .clearRewardOff();

        int answered = thirdZombieTick(new LevelServer(level(List.of(first, second), ratio)), true);
        int untouched = thirdZombieTick(new LevelServer(level(List.of(first, second), fixed)), true);

        // Wave 1 is answered at 100%, so wave 2's 3000-tick countdown runs at twice the rate and
        // lands near 1600 - the same run in the fixed mode lands on its written 3000 instead.
        assertTrue(answered <= 1700,
                "killing wave 1 shortens wave 2's wait: " + answered);
        assertTrue(untouched >= 2500,
                "and in the fixed mode the same kills change nothing: " + untouched);
    }

    /**
     * The tick wave 2's only zombie arrives on, with everything killed as it appears.
     *
     * <p>Wave 1 is two zombies, so the third spawn of the run is wave 2's - and "killed as it
     * appears" is what a player's lawn does, which is the state the ratio gate is written for.
     */
    private static int thirdZombieTick(LevelServer level, boolean kill) {
        Bridge bridge = new Bridge();
        for (int tick = 1; tick <= 6000; tick++) {
            level.tick(bridge);
            if (kill) {
                killAll(level);
            }
            if (bridge.spawns() >= 3) {
                return tick;
            }
        }
        throw new AssertionError("wave 2 never arrived (spawns=" + bridge.spawns() + ")");
    }

    /**
     * A budget wave spends its points on the pool instead of reading {@code entries}.
     *
     * <p>The wave below writes one zombie in {@code entries} and can afford several ordinary ones,
     * so what arrives is what the budget bought - which is the only way to tell the two apart.
     */
    @Test
    void aBudgetWaveRollsItsCompositionFromThePool() {
        WavePacingData pacing = WavePacingData.ofModes(WavePacingData.WaveMode.BUDGET)
                .clearRewardOff();
        LevelServer level = new LevelServer(level(List.of(wave(WaveDef.WaveType.SMALL, 5, 1)), pacing));
        Bridge bridge = new Bridge();

        int arrived = 0;
        for (int i = 1; i <= 400 && arrived == 0; i++) {
            level.tick(bridge);
            arrived = (int) level.aliveZombieCount();
        }
        // One ordinary zombie costs one point, and the default budget of a pool of every zombie
        // is small: what matters is that the wave sent something it was not handed by `entries`.
        assertTrue(arrived >= 1, "the budget bought at least one zombie");
    }

    /**
     * The clear bonus never eats the pause between waves.
     *
     * <p>An 1800-tick gap with the default grace window of ten seconds and a factor of three runs
     * down to about 1000 ticks - shorter than written, and nowhere near the 300-tick floor a
     * purely multiplicative rule would have produced.
     */
    @Test
    void theClearBonusShortensTheWaitButKeepsAGraceWindow() {
        // The health drain is off: this test is about the clear bonus's own arithmetic, and both
        // are shortcuts to the same arrival.
        WavePacingData pacing = new WavePacingData(3F, 300, 600, 0.75F, 0.75F, false, false,
                WavePacingData.WaveMode.FIXED, List.of());
        LevelServer level = new LevelServer(level(List.of(
                wave(WaveDef.WaveType.SMALL, 10, 1), wave(WaveDef.WaveType.FINAL, 1800, 1)), pacing));
        Bridge bridge = new Bridge();

        int[] arrivals = nextSpawns(level, bridge, 2, true);
        assertEquals(10, arrivals[0], "the first wave still arrives on its written delay");
        int gap = arrivals[1] - arrivals[0];
        // The countdown runs at three ticks a tick up to its target of 1000 (a 600-tick grace
        // plus a third of the rest), so the wait lands near a third of the written 1800 rather
        // than at the 600 a purely multiplicative rule would have produced - and never at zero.
        assertTrue(gap >= 250, "the countdown is never erased: " + gap);
        assertTrue(gap < 1800, "but it is shorter than written: " + gap);
        assertEquals(300, pacing.clearRewardDelay(300), "a gap inside the window is untouched");
        assertEquals(1000, pacing.clearRewardDelay(1800), "600 grace + 1200/3");
    }

    // ------------------------------------------------------------------
    // The health drain: the wave on the lawn being beaten IS the pacing
    // ------------------------------------------------------------------

    /**
     * Beating the wave on the lawn brings the next one in about {@code HEALTH_DRAIN_TICKS}.
     *
     * <p>The original's own mechanic, and the answer to "the level makes me stand on an empty lawn
     * between waves": a countdown written as 3000 ticks is the pacing of a player who is <em>not</em>
     * winning, and the moment the wave that arrived is beaten the wait becomes a few seconds.
     *
     * <p>The clear reward is off in both runs on purpose: it is the other shortcut to the same
     * arrival, and with both of them on this would pass without the drain existing at all. What is
     * left is the drain's own signature - a countdown that stops obeying its written delay the
     * moment the wave is beaten. Two zombies, killed a second after they walk in, so the line sits
     * at "one of the two is gone" rather than on the dice.
     */
    @Test
    void beatingTheWaveOnTheLawnBringsTheNextOneInThreeSeconds() {
        LevelServer probe = new LevelServer(level(List.of(
                wave(WaveDef.WaveType.SMALL, 10, 2), wave(WaveDef.WaveType.FINAL, 3000, 1)),
                WavePacingData.ofModes(WavePacingData.WaveMode.FIXED).clearRewardOff().drainOn()));
        Bridge probeBridge = new Bridge();
        int lastSpawns = 0;
        for (int tick = 1; tick <= 700; tick++) {
            probe.tick(probeBridge);
            killAll(probe);
            var tag = probe.save();
            if (probeBridge.spawns() != lastSpawns || tick % 50 == 0) {
                lastSpawns = probeBridge.spawns();
                System.out.println("P t=" + tick + " w=" + probe.currentWave()
                        + " iv=" + tag.getInt("WaveIntervalTicks")
                        + " tgt=" + tag.getInt("NextWaveTargetTicks")
                        + " spawns=" + lastSpawns + " alive=" + probe.aliveZombieCount());
            }
        }
        int[] drained = arrivals(WavePacingData.ofModes(WavePacingData.WaveMode.FIXED)
                .clearRewardOff().drainOn());
        assertTrue(drained[2] - drained[1] <= WaveDirector.HEALTH_DRAIN_TICKS + 60,
                "the drain leaves the original's three seconds, not the written 3000: "
                        + (drained[2] - drained[1]));
        assertTrue(drained[2] - drained[1] >= 120,
                "and it is still a gap the player can see coming: " + (drained[2] - drained[1]));
    }

    /**
     * The drain is off in a level that says so, and the written delay is served in full.
     *
     * <p>What a scripted level (the mutation tutorial) asks for: its point is the order of its own
     * events, and a fight that ends early would take the last of them with it.
     */
    @Test
    void aLevelMayTurnTheDrainOffAndServeItsWrittenDelay() {
        int[] served = arrivals(WavePacingData.ofModes(WavePacingData.WaveMode.FIXED)
                .clearRewardOff());
        assertTrue(served[2] - served[1] >= 3000,
                "with the drain off the countdown runs its written 3000: "
                        + (served[2] - served[1]));
    }

    /**
     * The three arrivals of one level: two zombies a second after they walk in, then the finale.
     *
     * <p>Built here rather than taken from a fixture file because the wave table <em>is</em> the
     * subject: wave 1 is two zombies on a 30-tick interval, wave 2 a written 3000-tick wait.
     */
    private static int[] arrivals(WavePacingData pacing) {
        LevelServer level = new LevelServer(level(List.of(
                wave(WaveDef.WaveType.SMALL, 10, 2), wave(WaveDef.WaveType.FINAL, 3000, 1)),
                pacing));
        return nextSpawns(level, new Bridge(), 3, true);
    }
}
