package com.pvzce.server.level;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.pvzce.api.content.EnvValue;
import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.LevelRewards;
import com.pvzce.api.content.LevelUnlock;
import com.pvzce.api.content.TeamDef;
import com.pvzce.api.content.WaveDef;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.network.PvzcePacket;
import com.pvzce.common.tag.TestContent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The zombie spawn speed rule: {@code pvzce:zombie_spawn_speed_multiplier}.
 *
 * <p>It scales the level's whole spawn timeline - the gap between waves and the gap between the
 * zombies inside one - so a level can say "the same wave table, twice as fast" without rewriting
 * every delay in it. The conveyor levels are what it exists for: their cards arrive at their own
 * fixed rate, so how fast the horde shows up is a separate knob.
 */
class ZombieSpawnSpeedTest {
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
    }

    /**
     * Two waves of four zombies: the first at tick {@code 120}, one every {@code 240} ticks, and
     * a second wave {@code 480} ticks after the first finished releasing.
     */
    private static LevelDef level(float spawnSpeed) {
        WaveDef first = new WaveDef(WaveDef.WaveType.SMALL, 120, 0, List.of(
                new WaveDef.Entry(PvzceIds.id("basic_zombie"), 4)),
                Optional.of(240), Optional.of(0));
        WaveDef second = new WaveDef(WaveDef.WaveType.FINAL, 480, 0, List.of(
                new WaveDef.Entry(PvzceIds.id("basic_zombie"), 1)),
                Optional.of(240), Optional.of(0));
        Map<Identifier, JsonElement> rules = spawnSpeed == 1F
                ? Map.of()
                : Map.of(PvzceIds.RULE_ZOMBIE_SPAWN_SPEED_MULTIPLIER,
                        JsonParser.parseString(String.valueOf(spawnSpeed)));
        return new LevelDef(Identifier.withDefaultNamespace("spawn_speed_test"), "出怪", "",
                3, 1,
                Map.of(Identifier.withDefaultNamespace("grass"), List.of("0,0", "1,0", "2,0")),
                List.of(new TeamDef(PvzceIds.PLANT_TEAM, "植物方", "survive_waves"),
                        new TeamDef(PvzceIds.ZOMBIE_TEAM, "僵尸方", "plant_side_lost")),
                PvzceIds.PLANT_TEAM,
                rules,
                Map.<Identifier, EnvValue>of(),
                List.of(first, second),
                1F,
                List.of(PvzceIds.id("pea_shooter")),
                Map.of(PvzceIds.SUN, true),
                500,
                new LevelDef.LevelMusicDef(List.of()),
                List.of(),
                6,
                LevelRewards.NONE,
                LevelUnlock.NONE);
    }

    /** The tick the n-th zombie appears, counting from the level's first tick. */
    private static int tickOfZombie(LevelDef def, int ordinal) {
        LevelServer level = new LevelServer(def);
        Bridge bridge = new Bridge();
        int seen = 0;
        for (int tick = 1; tick <= 20_000; tick++) {
            level.tick(bridge);
            int alive = level.entities().stream()
                    .filter(entity -> entity instanceof com.pvzce.server.entity.ZombieEntity)
                    .toList().size();
            while (seen < alive) {
                seen++;
                if (seen == ordinal) {
                    return tick;
                }
            }
        }
        throw new AssertionError("zombie " + ordinal + " never arrived");
    }

    /**
     * Twice the spawn speed is half the timeline: the wave arrives earlier and its zombies
     * trickle out sooner, so the fourth one is out at half the tick.
     */
    @Test
    void doubleSpeedHalvesTheWholeSpawnTimeline() {
        LevelDef normal = level(1F);
        LevelDef fast = level(2F);

        int normalFirst = tickOfZombie(normal, 1);
        int normalFourth = tickOfZombie(normal, 4);
        int fastFirst = tickOfZombie(fast, 1);
        int fastFourth = tickOfZombie(fast, 4);

        // The written pacing, exactly: the wave arrives on the tick its delay runs out, and
        // then one zombie per interval - plus the one tick the counter takes to reach zero
        // before the spawn, so a 240-tick interval releases every 241 ticks.
        assertEquals(120, normalFirst, "the wave's own delay, as written");
        assertEquals(120 + 3 * 241, normalFourth, "three intervals after it");

        // Twice the speed is half the timeline, both halves of it.
        assertEquals(normalFirst / 2, fastFirst, "the wave arrives at half the delay");
        assertEquals(normalFourth / 2F, fastFourth, 2F,
                "and the interval is halved too (" + normalFourth + " -> " + fastFourth + ")");
    }

    /**
     * The rule is one number on a level, and no shipped adventure level declares it any more.
     *
     * <p>1-10 and 2-10 used to say 2: this build's stand-in for "a mini-boss level is denser than
     * its wave table". The original does not double a clock - it triples the points every wave is
     * allowed to spend (`IsMiniBossLevel`) - and the rebuilt tables carry that density themselves,
     * so the two finales stopped using this. The rule itself stays, because the endless modes and
     * the mutations do declare it (see the fixtures above).
     */
    @Test
    void noShippedAdventureLevelRunsItsTableAtDoubleSpeed() {
        for (Identifier id : com.pvzce.common.core.BuiltInRegistries.LEVELS.keySet()) {
            if (!id.path().startsWith("yard/adventure/")) {
                continue;
            }
            LevelDef def = com.pvzce.common.core.BuiltInRegistries.LEVELS.get(id);
            assertTrue(!def.rules().containsKey(PvzceIds.RULE_ZOMBIE_SPAWN_SPEED_MULTIPLIER),
                    def.id() + " declares a spawn-speed multiplier, which the original's own"
                            + " pacing has no equivalent of");
        }
    }
}
