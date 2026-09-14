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
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Wave scheduling, warning, sound and interval-curve tests. */
class WaveSystemTest {
    @BeforeAll
    static void bootstrap() {
        BuiltInRegistries.bootstrap();
    }

    @Test
    void smallHugeAndFinalWavesUseCorrectTimingAndSounds() {
        LevelServer level = new LevelServer(testLevel(1F, 10, 20, 20));
        CapturingBridge bridge = new CapturingBridge();

        // Timeline of this level (delays 10/20/20, one-second release interval):
        //   tick 10  wave 1 triggers, its first zombie steps out
        //   tick 25  wave 2 (huge) starts warning; it triggers at 30
        //   tick 45  wave 3 (final) starts warning; it triggers at 50
        //   tick 70  wave 1's *second* zombie, a full interval after its first
        tick(level, bridge, 10);
        assertEquals(1, level.currentWave());
        assertEquals(1, bridge.zombieSpawns(), "wave 1's first zombie should spawn immediately");
        assertFalse(bridge.hasSound("pvzce:sfx/ambient/hugewave"), "small waves must not play the huge-wave sound");

        // One second apart, as the original does. At the old 15 ticks a small wave's
        // zombies were all out inside half a second, which is the "they all came at
        // once" complaint - and this test used to assert exactly that.
        tick(level, bridge, 15);
        assertEquals(1, bridge.zombieSpawns(), "wave 1's second zombie is not due yet");
        assertTrue(level.waveWarningActive(), "wave 2's warning opens 5 ticks before it");
        assertFalse(level.waveWarningFinal(), "and it is the huge wave, not the final one");

        tick(level, bridge, 5);
        assertEquals(2, level.currentWave());
        assertFalse(level.waveWarningActive(), "the warning ends when the wave it announced arrives");
        assertEquals(2, bridge.zombieSpawns(), "wave 2's first zombie");
        assertEquals(1, bridge.soundCount("pvzce:sfx/ambient/hugewave"), "huge wave should play hugewave once");
        assertEquals(0, bridge.soundCount("pvzce:sfx/effect/awooga"), "and it is not the final wave");

        tick(level, bridge, 15);
        assertTrue(level.waveWarningActive(), "final wave warning should start 5 ticks before it spawns");
        assertTrue(level.waveWarningFinal());

        tick(level, bridge, 5);
        assertEquals(3, level.currentWave());
        assertEquals(3, bridge.zombieSpawns(), "wave 3's first zombie");
        assertEquals(2, bridge.soundCount("pvzce:sfx/ambient/hugewave"), "final wave is also a huge wave");
        assertEquals(1, bridge.soundCount("pvzce:sfx/effect/awooga"), "final wave should play the awooga siren");

        // Wave 1 has a second zombie and it is on wave 1's clock, 300 ticks after its
        // first. Waves 2 and 3 released their only zombie on their own trigger tick.
        tick(level, bridge, WaveDef.DEFAULT_SPAWN_INTERVAL_TICKS);
        assertEquals(4, bridge.zombieSpawns(),
                "wave 1's second zombie, one default interval after its first");

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

    @Test
    void intervalCurveShortensLaterWaveDelays() {
        LevelServer level = new LevelServer(testLevel(0.5F, 10, 10, 10));
        CapturingBridge bridge = new CapturingBridge();

        tick(level, bridge, 10);
        assertEquals(1, level.currentWave());

        tick(level, bridge, 8);
        assertEquals(2, level.currentWave(), "second wave delay should be 8 ticks (10 * 0.75)");

        tick(level, bridge, 5);
        assertEquals(3, level.currentWave(), "final wave delay should be 5 ticks (10 * 0.5)");
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
                        new WaveDef.Entry(Identifier.withDefaultNamespace("basic_zombie"), 2)), 600),
                new WaveDef(WaveDef.WaveType.FINAL, 30, 5, List.of(
                        new WaveDef.Entry(Identifier.withDefaultNamespace("basic_zombie"), 2)), 300)));
        LevelServer level = new LevelServer(def);
        CapturingBridge bridge = new CapturingBridge();

        // Wave 1 triggers on tick 10 and resets the counter, so wave 2's 30-tick delay
        // elapses at tick 40. Both waves are then open with one zombie out each.
        tick(level, bridge, 40);
        assertEquals(2, bridge.zombieSpawns(), "each wave released its first zombie");

        // 300 ticks later wave 2's interval is up and wave 1's (600) is not: the two waves
        // are running on different clocks.
        // Wave 2's interval is 300 and its first zombie came out at tick 41; wave 1's is
        // 600 and its first came out at tick 10.
        tick(level, bridge, 290);
        assertEquals(2, bridge.zombieSpawns(), "wave 2 is not due yet");
        tick(level, bridge, 11);
        assertEquals(3, bridge.zombieSpawns(), "wave 2's second zombie, on wave 2's clock");
        tick(level, bridge, 300);
        assertEquals(4, bridge.zombieSpawns(), "and wave 1's, on wave 1's slower one");
        tick(level, bridge, 400);
        assertEquals(4, bridge.zombieSpawns(), "both waves are spent");
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

    private static void tick(LevelServer level, LevelServer.ServerBridge bridge, int ticks) {
        for (int i = 0; i < ticks; i++) {
            level.tick(bridge);
        }
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
