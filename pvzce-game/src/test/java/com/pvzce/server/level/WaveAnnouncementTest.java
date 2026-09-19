package com.pvzce.server.level;

import com.google.gson.JsonElement;
import com.pvzce.api.content.EnvValue;
import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.LevelRewards;
import com.pvzce.api.content.ResourceDef;
import com.pvzce.api.content.TeamDef;
import com.pvzce.api.content.WaveDef;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.network.PvzcePacket;
import com.pvzce.common.network.packet.EffectEventS2C;
import com.pvzce.common.network.packet.GameStateS2C;
import com.pvzce.common.tag.TestContent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The presentation a wave produces: which stingers fire, and how often.
 *
 * <p>These are one-shot announcements. A wave that announces itself every tick - or a
 * banner that stays on for the whole release window - reads as a stuck loop, and both
 * were real: the warning window accepted a negative countdown, so it lit up while the
 * wave it had already announced was still walking in.
 */
class WaveAnnouncementTest {
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

        List<String> sounds() {
            List<String> sounds = new ArrayList<>();
            for (PvzcePacket packet : packets) {
                if (packet instanceof EffectEventS2C effect && !effect.sound().isEmpty()) {
                    sounds.add(effect.sound());
                }
            }
            return sounds;
        }

        int countOf(String sound) {
            return (int) sounds().stream().filter(sound::equals).count();
        }

        /** Frames where the client would draw the "a huge wave is coming" banner. */
        List<Boolean> warnings() {
            List<Boolean> warnings = new ArrayList<>();
            for (PvzcePacket packet : packets) {
                if (packet instanceof com.pvzce.common.network.packet.WaveProgressS2C wave) {
                    warnings.add(wave.warningActive());
                }
            }
            return warnings;
        }
    }

    /**
     * A level whose second wave is huge and final, so every announcement fires once.
     *
     * <p>Delays are short: the point is the count, not the pacing, and a real 1500-tick
     * gap would make this the slowest test in the suite.
     */
    private static LevelDef level(int wave1Delay) {
        // hold_until_dead 0: this level is about how often a stinger fires, not about the
        // opening's pacing, and nothing here ever kills the zombie the gate would wait for.
        WaveDef small = new WaveDef(WaveDef.WaveType.SMALL, 60, 0, List.of(
                new WaveDef.Entry(Identifier.withDefaultNamespace("basic_zombie"), 1)),
                WaveDef.DEFAULT_SPAWN_INTERVAL_TICKS, java.util.Optional.of(0));
        WaveDef last = new WaveDef(WaveDef.WaveType.FINAL, wave1Delay, 40, List.of(
                new WaveDef.Entry(Identifier.withDefaultNamespace("basic_zombie"), 1)),
                WaveDef.DEFAULT_SPAWN_INTERVAL_TICKS, java.util.Optional.of(0));
        return new LevelDef(Identifier.withDefaultNamespace("wave_announce_test"), "波次", "",
                3, 1,
                Map.of(Identifier.withDefaultNamespace("grass"), List.of("0,0", "1,0", "2,0")),
                List.of(new TeamDef(Identifier.withDefaultNamespace("plant_team"), "植物方", "survive_waves"),
                        new TeamDef(Identifier.withDefaultNamespace("zombie_team"), "僵尸方", "plant_side_lost")),
                Identifier.withDefaultNamespace("plant_team"),
                Map.<Identifier, JsonElement>of(),
                Map.<Identifier, EnvValue>of(),
                List.of(small, last),
                1F,
                List.of(Identifier.withDefaultNamespace("pea_shooter")),
                Map.of(Identifier.withDefaultNamespace("sun"), true),
                500,
                new LevelDef.LevelMusicDef(List.of()),
                List.of(),
                6,
                LevelRewards.NONE,
                com.pvzce.api.content.LevelUnlock.NONE);
    }

    /** Each wave announces itself exactly once. */
    @Test
    void everyWaveStingerFiresOnce() {
        LevelServer serverLevel = new LevelServer(level(120));
        Bridge bridge = new Bridge();
        for (int i = 0; i < 400 && serverLevel.gameState().equals(GameStateS2C.RUNNING); i++) {
            serverLevel.tick(bridge);
        }

        assertEquals(1, bridge.countOf("pvzce:sfx/effect/awooga"),
                "the final wave's stinger must fire exactly once: " + bridge.sounds());
        assertEquals(1, bridge.countOf("pvzce:sfx/ambient/hugewave"),
                "the huge-wave call must fire exactly once: " + bridge.sounds());
    }

    /**
     * The warning is on while a huge wave counts down and off once it arrives.
     *
     * <p>The bug this pins: {@code waveIntervalTicks} keeps running while the previous
     * wave's zombies are still being released, so the countdown went negative and the
     * banner stayed lit through the whole release window - blinking on and off long
     * after the wave it announced had walked in.
     */
    @Test
    void theHugeWaveWarningTurnsOffWhenTheWaveArrives() {
        LevelServer serverLevel = new LevelServer(level(300));
        Bridge bridge = new Bridge();
        for (int i = 0; i < 500 && serverLevel.gameState().equals(GameStateS2C.RUNNING); i++) {
            serverLevel.tick(bridge);
        }

        List<Boolean> warnings = bridge.warnings();
        assertTrue(warnings.contains(true), "the huge wave must warn before it arrives");
        assertTrue(warnings.get(warnings.size() - 1).equals(Boolean.FALSE),
                "the warning must be off once the wave has arrived");
        assertFalse(serverLevel.waveWarningActive(),
                "and the level's own flag must agree with what was sent");
    }

    /**
     * A whole level announces each wave once and stops.
     *
     * <p>Counted across every packet a complete run sends, not just the two the synthetic
     * fixtures above cover: the siren is the one sound a player would notice looping,
     * because it lands at the moment the level is at its loudest.
     */
    @Test
    void aWholeLevelAnnouncesEachWaveOnce() throws Exception {
        LevelServer level = new LevelServer(com.pvzce.common.core.BuiltInRegistries.LEVELS
                .get(Identifier.withDefaultNamespace("yard/adventure/1_2")));
        Bridge bridge = new Bridge();
        for (int i = 0; i < 40_000 && level.gameState().equals(GameStateS2C.RUNNING); i++) {
            level.tick(bridge);
            // Clear the board so the level can actually finish.
            for (var entity : level.entities()) {
                if (entity instanceof com.pvzce.server.entity.ZombieEntity zombie
                        && !zombie.isRemoved()) {
                    zombie.damageBody(10_000, level);
                }
            }
        }
        assertEquals(1, bridge.countOf("pvzce:sfx/effect/awooga"),
                "the final wave's siren, once: " + bridge.sounds());
        assertEquals(2, bridge.countOf("pvzce:sfx/ambient/hugewave"),
                "1-2 has two huge waves: " + bridge.sounds());
    }

    /** The resource drops currently on the field, in the order they were added. */
    private static List<com.pvzce.server.entity.ResourceDropEntity> drops(LevelServer level) {
        return level.entities().stream()
                .filter(com.pvzce.server.entity.ResourceDropEntity.class::isInstance)
                .map(com.pvzce.server.entity.ResourceDropEntity.class::cast)
                .toList();
    }

    /** A sky sun falls; a harvested one rises. The two motions are the resource's own. */
    @Test
    void sunDropsFallAndHarvestedSunRises() {
        ResourceDef sun = com.pvzce.common.core.BuiltInRegistries.RESOURCES
                .get(com.pvzce.common.PvzceIds.SUN);
        assertEquals(ResourceDef.DropMotion.FALL, sun.dropMotion());

        LevelServer serverLevel = new LevelServer(level(600));
        Bridge bridge = new Bridge();
        serverLevel.tick(bridge);

        com.pvzce.server.Team team = serverLevel.team(Identifier.withDefaultNamespace("plant_team"));
        // The level above has already ticked, and its random is unseeded: a sky sun may have
        // dropped in on that tick (0.1%). The drop under test is therefore the one that was
        // not on the field a moment ago, not "the first drop entity".
        List<com.pvzce.server.entity.ResourceDropEntity> before = drops(serverLevel);
        serverLevel.spawnProducedResource(com.pvzce.common.PvzceIds.SUN, 25, 1F, 0F, team);
        // Entities are queued and only enter the world on a flush, which the level's own
        // tick does; the test drives one so the drop is real before it is inspected.
        serverLevel.flushPending(bridge);
        com.pvzce.server.entity.ResourceDropEntity produced = drops(serverLevel).stream()
                .filter(drop -> !before.contains(drop))
                .findFirst().orElseThrow();

        assertEquals(ResourceDef.DropMotion.RISE, produced.motion());
        assertFalse(produced.landed(), "it starts moving, not already down");

        // It goes up first, then comes back to where it started.
        float peak = 0F;
        for (int i = 0; i < ResourceDef.RISE_TICKS * 2 + 4; i++) {
            serverLevel.tick(bridge);
            peak = Math.max(peak, produced.height());
        }
        assertTrue(peak > 0.3F, "the sun must rise clear of the flower, peaked at " + peak);
        assertTrue(produced.landed());
        assertEquals(0F, produced.height(), 0.0001F);

        // A coin bursts out of whatever dropped it instead: a short pop that is thrown a
        // little to one side, so a fistful of them does not stack up as one arc copied.
        serverLevel.spawnResource(com.pvzce.common.PvzceIds.COIN_SILVER, 10, 2F, 0F, team);
        serverLevel.flushPending(bridge);
        com.pvzce.server.entity.ResourceDropEntity coin = serverLevel.entities().stream()
                .filter(com.pvzce.server.entity.ResourceDropEntity.class::isInstance)
                .map(com.pvzce.server.entity.ResourceDropEntity.class::cast)
                .filter(drop -> drop.def().id().equals(com.pvzce.common.PvzceIds.COIN_SILVER))
                .findFirst().orElseThrow();
        assertFalse(coin.landed(), "a coin pops before it settles");
        float spawnX = coin.cellX();
        assertTrue(coin.def().riseHeight() < ResourceDef.RISE_HEIGHT,
                "a coin pops lower than the sun a sunflower makes");

        float coinPeak = 0F;
        for (int i = 0; i < ResourceDef.RISE_TICKS * 2 + 4; i++) {
            serverLevel.tick(bridge);
            coinPeak = Math.max(coinPeak, coin.height());
        }
        assertTrue(coin.landed(), "the pop ends on the ground");
        assertEquals(0F, coin.height(), 0.0001F);
        assertTrue(coinPeak > 0.1F, "it has to leave the ground, peaked at " + coinPeak);
        assertTrue(Math.abs(coin.cellX() - spawnX) <= coin.def().riseScatter() + 0.0001F,
                "the sideways throw stays inside rise_scatter");
    }
}
