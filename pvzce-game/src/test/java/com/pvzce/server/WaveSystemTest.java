package com.pvzce.server;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.TeamDef;
import com.pvzce.api.content.WaveDef;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.tag.TestContent;
import com.pvzce.common.network.PvzcePacket;
import com.pvzce.common.network.packet.EffectEventS2C;
import com.pvzce.common.network.packet.GameStateS2C;
import com.pvzce.common.network.packet.EntitySpawnS2C;
import com.pvzce.server.level.LevelServer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Wave scheduling, warning, sound and interval-curve tests. */
class WaveSystemTest {
    @BeforeAll
    static void bootstrap() {
        BuiltInRegistries.bootstrap();
    }

    /**
     * The wave timeline, and the sounds that go with it.
     *
     * <p>Driven by "tick until X happens" rather than by fixed counts: every step here is
     * a one-tick boundary (a queue publishes on the tick *after* its interval elapses, the
     * next wave's delay starts on the tick the previous queue empties), and a test that
     * hard-codes those boundaries is testing its own arithmetic.
     */
    @Test
    void smallHugeAndFinalWavesUseCorrectTimingAndSounds() {
        LevelServer level = new LevelServer(testLevel(1F, 10, 20, 20));
        CapturingBridge bridge = new CapturingBridge();

        assertEquals(10, tickUntil(level, bridge, () -> bridge.zombieSpawns() >= 1),
                "wave 1 triggers on its 10-tick delay and releases at once");
        assertEquals(1, level.currentWave());
        assertFalse(bridge.hasSound("pvzce:sfx/ambient/hugewave"), "small waves must not play the huge-wave sound");

        // Wave 1's second zombie is on wave 1's own clock, and wave 2 does not start until
        // it is out: a wave's delay is armed once the previous wave stops releasing.
        assertEquals(311, tickUntil(level, bridge, () -> bridge.zombieSpawns() >= 2),
                "wave 1's second zombie, one 300-tick interval after its first");
        assertEquals(1, level.currentWave(), "wave 2 waits for wave 1 to finish releasing");

        tickUntil(level, bridge, () -> level.currentWave() >= 2);
        assertEquals(3, bridge.zombieSpawns(), "wave 2's first zombie");
        assertEquals(1, bridge.soundCount("pvzce:sfx/ambient/hugewave"), "huge wave should play hugewave once");
        assertEquals(0, bridge.soundCount("pvzce:sfx/effect/awooga"), "and it is not the final wave");

        tickUntil(level, bridge, () -> level.currentWave() >= 3);
        assertEquals(4, bridge.zombieSpawns(), "wave 3's first zombie");
        assertEquals(2, bridge.soundCount("pvzce:sfx/ambient/hugewave"), "final wave is also a huge wave");
        assertEquals(1, bridge.soundCount("pvzce:sfx/effect/awooga"), "final wave should play the awooga siren");

        tick(level, bridge, 400);
        assertEquals(4, bridge.zombieSpawns(), "and nothing else is due");

        for (var entity : level.entities()) {
            if (entity instanceof com.pvzce.server.entity.ZombieEntity zombie && !zombie.isRemoved()) {
                zombie.remove();
            }
        }
        level.tick(bridge);
        assertEquals("won", level.gameState());
    }

    /** The huge-wave warning shows for `warning_ticks` before the wave it announces. */
    @Test
    void theWarningOpensRightBeforeItsWave() {
        LevelServer level = new LevelServer(testLevel(1F, 10, 20, 20));
        CapturingBridge bridge = new CapturingBridge();

        tick(level, bridge, 10);
        assertEquals(1, level.currentWave());
        assertFalse(level.waveWarningActive(), "the warning is not up yet");

        // Wave 1 is spent as soon as it triggers (one zombie), so wave 2's 20-tick delay
        // runs from tick 11 and its 5-tick warning covers ticks 26..30.
        assertEquals(326, tickUntil(level, bridge, () -> level.waveWarningActive()),
                "the warning opens 5 ticks before the wave it announces");
        assertFalse(level.waveWarningFinal(), "and it is the huge wave, not the final one");
        assertEquals(1, level.currentWave(), "the wave itself has not arrived yet");

        tickUntil(level, bridge, () -> level.currentWave() >= 2);
        assertFalse(level.waveWarningActive(), "the warning ends when the wave it announced arrives");
    }

    @Test
    void intervalCurveShortensLaterWaveDelays() {
        // One zombie per wave, so every wave is spent the tick it arrives and each delay
        // is exactly the configured one times the curve's value for that wave.
        List<WaveDef> waves = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            waves.add(new WaveDef(i == 2 ? WaveDef.WaveType.FINAL : WaveDef.WaveType.SMALL,
                    10, 5, List.of(new WaveDef.Entry(
                            Identifier.withDefaultNamespace("basic_zombie"), 1))));
        }
        LevelServer level = new LevelServer(testLevel(0.5F, waves));
        CapturingBridge bridge = new CapturingBridge();

        assertEquals(10, tickUntil(level, bridge, () -> level.currentWave() >= 1));
        assertEquals(18, tickUntil(level, bridge, () -> level.currentWave() >= 2),
                "second wave delay should be 8 ticks (10 * 0.75)");
        assertEquals(23, tickUntil(level, bridge, () -> level.currentWave() >= 3),
                "final wave delay should be 5 ticks (10 * 0.5)");
    }

    @Test
    void waveCodecReadsEntriesAndDefaults() {
        WaveDef wave = WaveDef.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("""
                {
                  "type": "huge",
                  "delay": 1200,
                  "warning_ticks": 300,
                  "entries": [
                    { "id": "pvzce:basic_zombie", "count": 2 },
                    { "id": "pvzce:buckethead_zombie" }
                  ]
                }
                """)).getOrThrow();

        assertEquals(WaveDef.WaveType.HUGE, wave.type());
        assertEquals(1200, wave.delay());
        assertEquals(300, wave.warningTicks());
        assertEquals(2, wave.entries().get(0).count());
        assertEquals(1, wave.entries().get(1).count());
        assertEquals(3, wave.totalZombies());
    }

    private static LevelDef testLevel(float intervalEndMultiplier, int... delays) {
        List<WaveDef> waves = new ArrayList<>();
        for (int i = 0; i < delays.length; i++) {
            WaveDef.WaveType type = i == delays.length - 1
                    ? WaveDef.WaveType.FINAL
                    : (i == 1 ? WaveDef.WaveType.HUGE : WaveDef.WaveType.SMALL);
            waves.add(new WaveDef(type, delays[i], 5,
                    List.of(new WaveDef.Entry(Identifier.withDefaultNamespace("basic_zombie"),
                            i == 0 ? 2 : 1))));
        }
        return testLevel(intervalEndMultiplier, waves);
    }

    /** The same level with intervals chosen per wave, which is the point of the field. */
    private static LevelDef testLevel(float intervalEndMultiplier, List<WaveDef> waves) {
        return new LevelDef(
                Identifier.withDefaultNamespace("wave_test"),
                "", "", 9, 5,
                Map.of(),
                List.of(
                        new TeamDef(Identifier.withDefaultNamespace("plant_team"), "植物方", "survive_waves"),
                        new TeamDef(Identifier.withDefaultNamespace("zombie_team"), "僵尸方", "plant_side_lost")
                ),
                Identifier.withDefaultNamespace("plant_team"),
                Map.of(),
                Map.of(),
                waves,
                intervalEndMultiplier,
                List.of(Identifier.withDefaultNamespace("pea_shooter"), Identifier.withDefaultNamespace("sun")),
                Map.of(),
                150,
                LevelDef.LevelMusicDef.DEFAULT,
                List.of(),
                6,
                com.pvzce.api.content.LevelRewards.NONE,
                com.pvzce.api.content.LevelUnlock.NONE,
                // No mowers, so "the level ends" in these tests means what it says: a mower
                // would eat the zombie that walks in to end it (see MowerTest).
                List.of(com.pvzce.api.content.mechanic.TypedMechanic.of(
                        com.pvzce.common.PvzceIds.MECHANIC_MOWER,
                        new com.pvzce.api.content.MowerData(java.util.Optional.of(List.of())))),
                com.pvzce.api.content.LevelDialogue.EMPTY
        );
    }

    /**
     * Each wave keeps its own pace.
     *
     * <p>This is the pacing knob an easy level needs: its first wave should take ten
     * seconds between zombies while its last takes three, and that is a property of the
     * wave rather than of the file. Before the field existed every wave used one constant,
     * so slowing down the opening also slowed down the finale.
     */
    @Test
    void eachWaveReleasesAtItsOwnInterval() {
        LevelDef def = testLevel(1F, List.of(
                new WaveDef(WaveDef.WaveType.SMALL, 10, 5, List.of(
                        new WaveDef.Entry(Identifier.withDefaultNamespace("basic_zombie"), 2)), 5),
                new WaveDef(WaveDef.WaveType.FINAL, 30, 5, List.of(
                        new WaveDef.Entry(Identifier.withDefaultNamespace("basic_zombie"), 2)), 300)));
        LevelServer level = new LevelServer(def);
        CapturingBridge bridge = new CapturingBridge();

        // Wave 1's interval is floored at 15 ticks, so it releases at ticks 10 and 26;
        // wave 2's 30-tick delay then runs from 26, opening it at 56, and its own
        // 300-tick interval puts its second zombie at 357.
        assertEquals(357, tickUntil(level, bridge, () -> bridge.zombieSpawns() >= 4),
                "wave 2's second zombie, on wave 2's own clock");
        assertEquals(2, level.currentWave());

        tick(level, bridge, 400);
        assertEquals(4, bridge.zombieSpawns(), "and both waves are spent");
    }

    /**
     * The warning is off at the end of the level, whatever ended it.
     *
     * <p>A loss that lands while the final warning is showing used to leave the banner up
     * forever: {@code markEnd} stops the level ticking, so the tick that would have cleared
     * the flag never ran and the player saw "a huge wave is coming" over the defeat screen
     * for as long as they looked at it.
     */
    @Test
    void theWarningIsClearedWhenTheLevelEnds() {
        LevelDef def = testLevel(1F, List.of(
                new WaveDef(WaveDef.WaveType.FINAL, 900, 300, List.of(
                        new WaveDef.Entry(Identifier.withDefaultNamespace("basic_zombie"), 1)))));
        LevelServer level = new LevelServer(def);
        CapturingBridge bridge = new CapturingBridge();

        // Far enough in for the warning window, not far enough for the wave.
        tick(level, bridge, 650);
        assertTrue(level.waveWarningActive(), "the final wave should be warned about");
        assertTrue(level.waveWarningFinal());
        assertTrue(level.gameState().equals(GameStateS2C.RUNNING), "and the level is still on");

        // A zombie at the house ends it under the banner.
        level.spawnZombie(Identifier.withDefaultNamespace("basic_zombie"),
                level.team(PvzceIds.ZOMBIE_TEAM), -1F, 0);
        tick(level, bridge, 120);
        assertFalse(level.gameState().equals(GameStateS2C.RUNNING),
                "the level is over: " + level.gameState());
        assertFalse(level.waveWarningActive(),
                "a finished level must not still be announcing a wave");
        assertFalse(level.waveWarningFinal());
    }

    /**
     * A wave's delay is armed when the previous wave stops releasing, not when it starts.
     *
     * <p>Counting from the trigger let two waves' release queues run at the same time, and
     * zombies from different waves then arrived a few seconds apart instead of on the
     * pacing their own wave asked for - which is what "they all come out together" was in
     * levels whose first waves are authored ten seconds apart.
     */
    @Test
    void theNextWaveWaitsForThePreviousOneToFinishReleasing() {
        // Wave 1: three zombies a default interval apart. Wave 2: 20 ticks after that.
        LevelDef def = testLevel(1F, List.of(
                new WaveDef(WaveDef.WaveType.SMALL, 10, 5, List.of(
                        new WaveDef.Entry(Identifier.withDefaultNamespace("basic_zombie"), 3))),
                new WaveDef(WaveDef.WaveType.FINAL, 20, 5, List.of(
                        new WaveDef.Entry(Identifier.withDefaultNamespace("basic_zombie"), 1)))));
        LevelServer level = new LevelServer(def);
        CapturingBridge bridge = new CapturingBridge();

        assertEquals(10, tickUntil(level, bridge, () -> bridge.zombieSpawns() >= 1));
        assertEquals(311, tickUntil(level, bridge, () -> bridge.zombieSpawns() >= 2),
                "wave 1's own zombies are on wave 1's clock");
        assertEquals(1, level.currentWave(), "wave 2 must not open while wave 1 is releasing");

        assertEquals(612, tickUntil(level, bridge, () -> bridge.zombieSpawns() >= 3),
                "wave 1's third and last zombie");
        assertEquals(1, level.currentWave(), "still wave 1");

        int waveTwo = tickUntil(level, bridge, () -> level.currentWave() >= 2);
        assertEquals(632, waveTwo, "wave 2's 20-tick delay runs from the tick wave 1 was spent");
        assertEquals(4, bridge.zombieSpawns(), "and it starts with its own first zombie");
    }

    /**
     * 1-2 and 1-3 open with their zombies ten seconds apart.
     *
     * <p>Both shipped with 4-6 second gaps in their first three waves, which reads as a
     * crowd rather than an opening: three zombies arriving within twelve seconds of each
     * other is not something a two-card deck can answer. The pacing is level data, so this
     * is where the shipped files are checked; that it survives the wave boundaries is
     * {@link #theNextWaveWaitsForThePreviousOneToFinishReleasing}'s job.
     */
    @Test
    void theOpeningWavesOfTheFirstLevelsAreTenSecondsApart() throws Exception {
        TestContent.loadBuiltInContentAndTags();
        for (String path : List.of("1_2", "1_3")) {
            LevelDef def = BuiltInRegistries.LEVELS.get(
                    Identifier.withDefaultNamespace("yard/adventure/" + path));
            assertNotNull(def, path + " must be a shipped level");
            List<WaveDef> waves = def.waves();
            assertTrue(waves.size() >= 3, path + " should have at least three waves");
            for (int i = 0; i < 3; i++) {
                assertEquals(600, waves.get(i).spawnInterval(),
                        path + " wave " + i + " should release one zombie every ten seconds");
            }
        }
    }

    private static void tick(LevelServer level, LevelServer.ServerBridge bridge, int ticks) {
        for (int i = 0; i < ticks; i++) {
            level.tick(bridge);
        }
    }

    /**
     * Ticks until {@code condition} holds, returning the tick it first did.
     *
     * <p>Fails rather than looping forever, and prints the tick it stopped on - a timing
     * test is much easier to read when the failure says *when* rather than "expected 2,
     * got 1".
     */
    private static int tickUntil(LevelServer level, LevelServer.ServerBridge bridge,
                                 java.util.function.BooleanSupplier condition) {
        for (int i = 0; i < 6_000; i++) {
            level.tick(bridge);
            if (condition.getAsBoolean()) {
                return level.tickCount();
            }
        }
        throw new AssertionError("the condition never held within 6000 ticks");
    }

    private static final class CapturingBridge implements LevelServer.ServerBridge {
        private final List<PvzcePacket> packets = new ArrayList<>();

        @Override
        public void send(PvzcePacket packet) {
            packets.add(packet);
        }

        /** Zombies currently on the field, by the spawn packets that were sent. */
        int zombieSpawns() {
            return (int) packets.stream()
                    .filter(EntitySpawnS2C.class::isInstance)
                    .map(EntitySpawnS2C.class::cast)
                    .filter(spawn -> "zombie".equals(spawn.entityKind()))
                    .count();
        }

        long soundCount(String sound) {
            return packets.stream()
                    .filter(EffectEventS2C.class::isInstance)
                    .map(EffectEventS2C.class::cast)
                    .filter(effect -> sound.equals(effect.sound()))
                    .count();
        }

        boolean hasSound(String sound) {
            return soundCount(sound) > 0;
        }
    }
}
