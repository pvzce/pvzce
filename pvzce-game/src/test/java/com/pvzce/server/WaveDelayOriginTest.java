package com.pvzce.server;

import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.TeamDef;
import com.pvzce.api.content.WaveDef;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.network.PvzcePacket;
import com.pvzce.common.network.packet.EntitySpawnS2C;
import com.pvzce.server.level.LevelServer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Where a wave's {@code delay} starts counting.
 *
 * <p>The gap is measured from the tick the previous wave <em>triggered</em>, not from the tick it
 * finished putting its zombies out (see {@code WaveDirector.tick}). Every shipped table is a
 * replay of the original's own generator, and the original resets its countdown the tick a wave
 * spawns - so a delay armed after the release charged each level its own release window a second
 * time, which is where "the lawn sits empty between waves" came from.
 *
 * <p>What must not come back with the fix is the wave overlap "count from the trigger" used to
 * mean, two release queues running at once: that half is pinned by
 * {@code WaveSystemTest.theNextWaveWaitsForThePreviousOneToFinishReleasing}, on a level whose
 * delay is shorter than its release window.
 */
class WaveDelayOriginTest {
    @BeforeAll
    static void bootstrap() {
        BuiltInRegistries.bootstrap();
    }

    /**
     * A long release window and a short delay, so the two readings are half a minute apart.
     *
     * <p>Wave 1 is five zombies 300 ticks apart, so its queue does not empty until well past tick
     * 1200; wave 2 is due 100 ticks after the trigger, which is long before that.
     */
    @Test
    void theDelayRunsFromTheTriggerNotFromTheEndOfTheRelease() {
        LevelServer level = new LevelServer(level());
        CapturingBridge bridge = new CapturingBridge();

        assertEquals(10, tickUntil(level, bridge, () -> bridge.zombieSpawns() >= 1));
        assertEquals(311, tickUntil(level, bridge, () -> bridge.zombieSpawns() >= 2),
                "wave 1's own zombies are one 300-tick interval apart");
        assertEquals(1, level.currentWave(), "wave 2 has not opened while they are coming");

        // Wave 2's own 100-tick delay was spent while wave 1 was still coming out, so what the
        // arrival waits for is the release (five zombies at one per 301 ticks) and nothing else.
        // Arming the countdown after the release instead lands this on 2213.
        assertEquals(1314, tickUntil(level, bridge, () -> level.currentWave() >= 2),
                "wave 2's delay ran from wave 1's trigger, not from the end of its release");
        assertEquals(6, bridge.zombieSpawns(), "and it starts with its own first zombie");
    }

    /**
     * A level of this test's own: five zombies one every five seconds, and the next wave a second
     * and a half after the first one starts. No mowers, so a zombie walking in cannot end the run
     * mid-measurement.
     */
    private static LevelDef level() {
        List<WaveDef> waves = List.of(
                WaveDef.declaringSpawnInterval(WaveDef.WaveType.SMALL, 10, 0,
                        List.of(new WaveDef.Entry(Identifier.withDefaultNamespace("basic_zombie"),
                                5)), 300),
                new WaveDef(WaveDef.WaveType.FINAL, 100, 0,
                        List.of(new WaveDef.Entry(Identifier.withDefaultNamespace("basic_zombie"),
                                1))));
        return new LevelDef(
                Identifier.withDefaultNamespace("wave_delay_test"), "", "", 9, 1,
                Map.of(),
                List.of(new TeamDef(PvzceIds.PLANT_TEAM, "植物方", "survive_waves"),
                        new TeamDef(PvzceIds.ZOMBIE_TEAM, "僵尸方", "plant_side_lost")),
                PvzceIds.PLANT_TEAM,
                Map.of(),
                Map.of(),
                waves,
                1F,
                List.of(Identifier.withDefaultNamespace("pea_shooter")),
                Map.of(),
                150,
                LevelDef.LevelMusicDef.DEFAULT,
                List.of());
    }

    private static int tickUntil(LevelServer level, LevelServer.ServerBridge bridge,
                                 BooleanSupplier condition) {
        for (int i = 0; i < 6_000; i++) {
            level.tick(bridge);
            if (condition.getAsBoolean()) {
                return level.tickCount();
            }
        }
        throw new AssertionError("the condition never held within 6000 ticks");
    }

    /** Counts the zombies that walk in, by the spawn packets the level sends. */
    private static final class CapturingBridge implements LevelServer.ServerBridge {
        private int spawns;

        @Override
        public void send(PvzcePacket packet) {
            if (packet instanceof EntitySpawnS2C spawn && "zombie".equals(spawn.entityKind())) {
                spawns++;
            }
        }

        int zombieSpawns() {
            return spawns;
        }
    }
}
