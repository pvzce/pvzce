package com.pvzce.server.level;

import com.pvzce.api.content.LevelDef;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.network.PvzcePacket;
import com.pvzce.common.network.packet.EntitySpawnS2C;
import com.pvzce.common.network.packet.GameStateS2C;
import com.pvzce.common.tag.TestContent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * What a level's pacing actually costs the player, measured rather than guessed.
 *
 * <p>The wave tables are 23 files of hand-written {@code delay}s, and "this level drags" is not
 * something a reader can check: a delay is only slow in relation to the wave before it and to how
 * long the player needs to clear it. This class runs each shipped level headless under a few
 * fixed "how fast does the player kill" profiles and reports the numbers that complaint is about:
 * how long the level takes, how long the longest stretch with nothing to do is, how much of the
 * run is spent on an empty lawn, and how many zombies are ever on the field at once.
 *
 * <p>A profile is deliberately crude - one zombie dies every N ticks - because the alternative is
 * simulating plants, and a plant simulation would measure the plant AI rather than the tables.
 * What a level author needs from this is the shape of the curve and the outliers, not a verdict.
 *
 * <p>Set {@code -Ppvzce.smoke=pvzce.pacingReport=true} to print the table; the default run only
 * checks that every level can be finished under the slowest profile, which is what makes a data
 * edit visibly wrong rather than quietly worse.
 */
class WavePacingMetricsTest {
    /** How fast the player kills, as "one zombie every N ticks". */
    private static final int[] KILL_INTERVALS = {30, 90, 240};
    private static final String[] PROFILE_NAMES = {"fast", "normal", "slow"};
    /** A level is given this long before the run is called unfinished. */
    private static final int TICK_BUDGET = 120_000;

    @BeforeAll
    static void load() throws Exception {
        TestContent.loadBuiltInContentAndTags();
    }

    private static final class Bridge implements LevelServer.ServerBridge {
        int spawns;

        @Override
        public void send(PvzcePacket packet) {
            if (packet instanceof EntitySpawnS2C spawn && "zombie".equals(spawn.entityKind())) {
                spawns++;
            }
        }
    }

    /** One level's run, as the numbers the report prints. */
    private record Metrics(String level, String profile, int ticks, int waves, int zombies,
                           int longestEmpty, int emptyShare, int peakAlive, boolean won) {
    }

    private static Metrics measure(LevelDef def, String profile, int killInterval) {
        LevelServer level = new LevelServer(def);
        Bridge bridge = new Bridge();
        int longestEmpty = 0;
        int emptyTicks = 0;
        int peakAlive = 0;
        int currentEmpty = 0;
        int started = -1;
        int lastSpawnTick = 0;
        int tick = 0;
        for (; tick < TICK_BUDGET && level.gameState().equals(GameStateS2C.RUNNING); tick++) {
            level.tick(bridge);
            int alive = (int) level.aliveZombieCount();
            if (alive > 0 && started < 0) {
                started = tick;
            }
            // The player's own clock: one zombie dies every `killInterval` ticks, starting from
            // the first one that walks in.
            if (killInterval > 0 && alive > 0 && tick % killInterval == 0) {
                killOne(level);
            }
            alive = (int) level.aliveZombieCount();
            peakAlive = Math.max(peakAlive, alive);
            if (bridge.spawns != lastSpawnTick) {
                lastSpawnTick = bridge.spawns;
            }
            if (alive == 0 && started >= 0) {
                currentEmpty++;
                emptyTicks++;
                longestEmpty = Math.max(longestEmpty, currentEmpty);
            } else {
                currentEmpty = 0;
            }
        }
        int span = Math.max(1, tick - Math.max(0, started));
        return new Metrics(def.id().toString(), profile, tick, level.currentWave(),
                bridge.spawns, longestEmpty, Math.round(100F * emptyTicks / span),
                peakAlive, level.gameState().equals(GameStateS2C.WON));
    }

    /** Kills the zombie nearest the house, which is the one the player would shoot first. */
    private static void killOne(LevelServer level) {
        com.pvzce.server.entity.ZombieEntity target = null;
        for (var entity : level.entities()) {
            if (entity instanceof com.pvzce.server.entity.ZombieEntity zombie && !zombie.isRemoved()) {
                if (target == null || zombie.cellX() < target.cellX()) {
                    target = zombie;
                }
            }
        }
        if (target != null) {
            target.damageBody(10_000, level);
        }
    }

    @Test
    void everyShippedLevelCanBeFinishedAndReportsItsPacing() {
        boolean report = Boolean.getBoolean("pvzce.pacingReport");
        List<String> rows = new ArrayList<>();
        if (report) {
            rows.add(String.format("%-34s %-7s %7s %6s %8s %9s %7s %6s",
                    "level", "profile", "ticks", "waves", "zombies", "longestEmpty", "empty%", "peak"));
        }
        int unfinished = 0;
        for (Identifier id : BuiltInRegistries.LEVELS.keySet()) {
            LevelDef def = BuiltInRegistries.LEVELS.get(id);
            if (def == null || def.waves().isEmpty()) {
                continue;
            }
            for (int i = 0; i < KILL_INTERVALS.length; i++) {
                Metrics metrics = measure(def, PROFILE_NAMES[i], KILL_INTERVALS[i]);
                if (report) {
                    rows.add(String.format("%-34s %-7s %7d %6d %8d %9d %6d%% %6d",
                            shorten(metrics.level()), metrics.profile(), metrics.ticks(),
                            metrics.waves(), metrics.zombies(), metrics.longestEmpty(),
                            metrics.emptyShare(), metrics.peakAlive()));
                }
                // Only the slowest profile has to finish: a level the player cannot keep up with
                // is a difficulty statement, not a broken table - but one that never ends under
                // any profile is a table that cannot be won.
                if (i == KILL_INTERVALS.length - 1 && !metrics.won()) {
                    unfinished++;
                    rows.add("UNFINISHED under the slow profile: " + metrics.level()
                            + " after " + metrics.ticks() + " ticks, wave " + metrics.waves()
                            + " of " + def.waves().size());
                }
            }
        }
        if (report) {
            System.out.println("=== level pacing ===");
            rows.forEach(System.out::println);
        }
        org.junit.jupiter.api.Assertions.assertEquals(0, unfinished,
                "levels that cannot be finished under the slowest profile:\n"
                        + String.join("\n", rows));
    }

    private static String shorten(String levelId) {
        return levelId.replace("pvzce:", "").replace("yard/", "");
    }
}
