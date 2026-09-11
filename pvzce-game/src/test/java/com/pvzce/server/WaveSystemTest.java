package com.pvzce.server;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.TeamDef;
import com.pvzce.api.content.WaveDef;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.network.PvzcePacket;
import com.pvzce.common.network.packet.EffectEventS2C;
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

        tick(level, bridge, 10);
        assertEquals(1, level.currentWave());
        assertEquals(1, level.aliveZombieCount(), "first zombie of wave 1 should spawn immediately");
        assertFalse(bridge.hasSound("pvzce:sfx/ambient/hugewave"), "small waves must not play the huge-wave sound");

        tick(level, bridge, 16);
        assertEquals(2, level.aliveZombieCount(), "wave 1 should release its second zombie after 15 ticks");
        assertTrue(level.waveWarningActive(), "huge wave warning should start 5 ticks before it spawns");
        assertFalse(level.waveWarningFinal());

        tick(level, bridge, 4);
        assertEquals(2, level.currentWave());
        assertEquals(1, bridge.soundCount("pvzce:sfx/ambient/hugewave"), "huge wave should play hugewave once");
        assertEquals(0, bridge.soundCount("pvzce:sfx/effect/awooga"));

        tick(level, bridge, 15);
        assertTrue(level.waveWarningActive(), "final wave warning should start 5 ticks before it spawns");
        assertTrue(level.waveWarningFinal());

        tick(level, bridge, 5);
        assertEquals(3, level.currentWave());
        assertEquals(2, bridge.soundCount("pvzce:sfx/ambient/hugewave"), "final wave is also a huge wave");
        assertEquals(1, bridge.soundCount("pvzce:sfx/effect/awooga"), "final wave should play the awooga siren");

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
                List.of()
        );
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
