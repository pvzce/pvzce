package com.pvzce.server;

import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.level.mutation.MutationManager;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.common.nbt.NbtIo;
import com.pvzce.common.tag.TestContent;
import com.pvzce.common.util.LevelKey;
import com.pvzce.server.level.LevelServer;
import com.pvzce.testutil.ServerHarness;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A mutation run, saved to disk and resumed from it.
 *
 * <p>{@code MutationManagerTest} pins the manager's own save and restore; what this adds is the
 * path through the file - the level's {@code save()} has to call into the manager, {@code restore()}
 * has to run it before the card bar comes back, and the write has to survive {@link NbtIo}. The
 * player-visible symptom of any of those breaking is a resumed run whose mutation panel is empty
 * and whose rules have quietly gone back to normal.
 */
class MutationSaveRoundTripTest {
    private static final String WORLD = "world";
    private static final Identifier LEVEL = Identifier.withDefaultNamespace(
            "yard/survival/mutation_normal");

    @BeforeAll
    static void load() throws Exception {
        TestContent.loadBuiltInContentAndTags();
    }

    @Test
    void aSavedMutationRunResumesWithTheSameMutations(@TempDir Path gameDir) throws Exception {
        List<Identifier> before;
        java.util.Map<Identifier, Object> rulesBefore;
        Path saveFile;

        try (ServerHarness harness = ServerHarness.createWithWorld(gameDir, WORLD, true)) {
            harness.server().createLevelForTest(LEVEL.toString(), WORLD, true);
            LevelServer level = harness.server().level();
            assertNotNull(level, "the level has to be running");
            assertNotNull(level.mutations(), "and it has to be a mutation level");
            // A fixed seed: the mutations a run rolls are dice, and this test is about whether
            // they survive the save, not about which ones a particular run happens to get.
            level.seedRandom(20260925L);
            // The clock is shortened through the level's own rules, which is the state a resumed
            // run reads back: the manager arms its first countdown from them.
            level.setRule(PvzceIds.RULE_MUTATION_INITIAL_TICKS, 1);
            level.setRule(PvzceIds.RULE_MUTATION_INTERVAL_MULTIPLIER, 10F);
            awaitMutations(harness, 3);

            before = level.mutations().activeIds();
            // The live rules at the moment of the save. Compared as a whole rather than through
            // one named rule: which mutations a run rolls is dice, and a test that named
            // `sun_rate_multiplier` was silently asserting that this particular run had rolled
            // the sun-rate mutation - which stopped being true the moment anything upstream
            // consumed a different number of random draws.
            rulesBefore = new java.util.LinkedHashMap<>(level.rules().values());
            harness.server().saveGame();
            saveFile = LevelKey.levelDir(new WorldStore(gameDir).worldDir(WORLD), LEVEL)
                    .resolve("level.dat");
            assertTrue(java.nio.file.Files.isRegularFile(saveFile),
                    "the save has to be on disk at " + saveFile);
        }

        // The file itself: the list is not derivable from anything else in it.
        CompoundTag file = NbtIo.readCompressed(saveFile);
        assertNotNull(file);
        CompoundTag block = file.getCompound(MutationManager.KEY_MUTATIONS);
        assertNotNull(block, "the save file carries the mutation block");
        assertEquals(before.size(), block.getList("List").size(),
                "one entry per mutation that was running");
        // The clock is compared against the file rather than against a reading taken a moment
        // earlier: the server keeps ticking between the two, and a test that demanded the exact
        // number it saw would be pinning the scheduler's timing instead of the save.
        int savedCountdown = block.getInt("TicksUntilNext");

        try (ServerHarness harness = ServerHarness.createWithWorld(gameDir, WORLD, true)) {
            harness.server().createLevelForTest(LEVEL.toString(), WORLD, false);
            LevelServer resumed = harness.server().level();
            assertNotNull(resumed);
            assertNotNull(resumed.mutations(), "the resumed level still mutates");
            assertEquals(before, resumed.mutations().activeIds(),
                    "and it is running the same mutations, in the same order");
            assertEquals(savedCountdown, resumed.mutations().ticksUntilNext(),
                    "with the arrival clock where the save left it, not back at the grace period");
            // The rule arithmetic (each mutation re-applies exactly its own factor, once) is
            // pinned by MutationManagerTest, which does not race the server's own ticking; what
            // this test adds is that the rules reached the file and came back at all.
            for (java.util.Map.Entry<Identifier, Object> rule : rulesBefore.entrySet()) {
                assertEquals(rule.getValue(), resumed.rules().values().get(rule.getKey()),
                        "the run resumed with a different value for " + rule.getKey());
            }
            // And the file carries them, rather than the resume recomputing them by luck.
            for (java.util.Map.Entry<Identifier, Object> rule : rulesBefore.entrySet()) {
                String written = file.getCompound("Rules").getString(rule.getKey().toString());
                assertNotNull(written, "the save file is missing rule " + rule.getKey());
            }
        }
    }

    @Test
    void aNightfallInTheSaveIsStillNightWhenItIsLoaded(@TempDir Path gameDir) throws Exception {
        boolean hadNightfall = false;
        try (ServerHarness harness = ServerHarness.createWithWorld(gameDir, WORLD, true)) {
            harness.server().createLevelForTest(LEVEL.toString(), WORLD, true);
            LevelServer level = harness.server().level();
            assertNotNull(level);
            assertFalse(level.isNight(), "the day pool starts in daylight");
            // Put the night mutation on the field by name: rolling for it would make this test
            // depend on eighteen weights.
            com.pvzce.common.level.mutation.Mutation nightfall =
                    com.pvzce.common.level.mutation.MutationRegistry.get(
                            PvzceIds.MUTATION_NIGHTFALL);
            assertNotNull(nightfall);
            level.mutations().add(nightfall, com.pvzce.common.level.mutation.Mutation.Roll.NONE);
            hadNightfall = level.mutations().isApplied(PvzceIds.MUTATION_NIGHTFALL);
            assertTrue(hadNightfall, "the night mutation has to be running to be worth saving");
            assertTrue(level.isNight(), "and it is night now");
            harness.server().saveGame();
        }
        assertTrue(hadNightfall);

        try (ServerHarness harness = ServerHarness.createWithWorld(gameDir, WORLD, true)) {
            harness.server().createLevelForTest(LEVEL.toString(), WORLD, false);
            LevelServer resumed = harness.server().level();
            assertNotNull(resumed);
            // The rule, not the mutation: whatever the save says about mutations, the board has to
            // be playing under the rules it was saved under. This is also what a mushroom reads
            // when it decides whether to sleep.
            assertTrue(resumed.isNight(),
                    "a save taken at night resumes at night, not in daylight");
            assertFalse(resumed.rules().getBoolean(PvzceIds.RULE_GRAVES_SPAWN_NIGHT) == false
                            && !resumed.isNight(),
                    "and the two ways of asking agree");
            assertEquals(0, resumed.rules().getInt(PvzceIds.RULE_DAY_LENGTH));
            assertEquals(360_000, resumed.rules().getInt(PvzceIds.RULE_NIGHT_LENGTH));

            // And what a player actually sees: a mushroom on this board is awake. `isNight` is
            // what every nocturnal plant asks, so this is the same question the plant asks.
            var sunShroom = com.pvzce.common.core.BuiltInRegistries.PLANTS.get(
                    PvzceIds.id("sun_shroom"));
            assertNotNull(sunShroom, "the sun-shroom is the mushroom this checks");
            var plant = resumed.spawnPlant(sunShroom, resumed.team(PvzceIds.PLANT_TEAM), 2, 0);
            assertNotNull(plant);
            assertFalse(plant.isAsleep(resumed),
                    "a mushroom on a board that is at night is awake, not asleep");
        }
    }

    @Test
    void restartingDiscardsTheSavedRulesAsWellAsTheList(@TempDir Path gameDir) throws Exception {
        try (ServerHarness harness = ServerHarness.createWithWorld(gameDir, WORLD, true)) {
            harness.server().createLevelForTest(LEVEL.toString(), WORLD, true);
            LevelServer level = harness.server().level();
            assertNotNull(level);
            com.pvzce.common.level.mutation.Mutation nightfall =
                    com.pvzce.common.level.mutation.MutationRegistry.get(PvzceIds.MUTATION_NIGHTFALL);
            assertNotNull(nightfall);
            level.mutations().add(nightfall, com.pvzce.common.level.mutation.Mutation.Roll.NONE);
            assertTrue(level.isNight(), "the run to be discarded is a night run");
            harness.server().saveGame();
            assertTrue(WorldStore.hasRunningSave(
                    LevelKey.levelDir(new WorldStore(gameDir).worldDir(WORLD), LEVEL)),
                    "and it is on disk");
        }

        // 重新开始: the run on disk is a leftover, not something to resume. Everything it owns
        // goes - the mutation list, and the rules those mutations rewrote. A restart that kept the
        // night would be a fresh run of a day level played in the dark.
        try (ServerHarness harness = ServerHarness.createWithWorld(gameDir, WORLD, true)) {
            harness.server().createLevelForTest(LEVEL.toString(), WORLD, true);
            LevelServer restarted = harness.server().level();
            assertNotNull(restarted);
            assertFalse(restarted.isNight(), "a restarted day level starts in daylight");
            assertEquals(-1, restarted.rules().getInt(PvzceIds.RULE_NIGHT_LENGTH));
            assertTrue(restarted.mutations().activeIds().isEmpty(),
                    "and the mutation list starts empty; saw " + restarted.mutations().activeIds());
        }
    }

    @Test
    void theRestartPacketDiscardsANightRun(@TempDir Path gameDir) throws Exception {
        // The same thing again, through the packet the pause menu's 重新开始 sends rather than
        // through the test hook: the intent has to survive the wire, not just the method call.
        try (ServerHarness harness = ServerHarness.createWithWorld(gameDir, WORLD, true)) {
            harness.server().createLevelForTest(LEVEL.toString(), WORLD, true);
            LevelServer level = harness.server().level();
            assertNotNull(level);
            com.pvzce.common.level.mutation.Mutation nightfall =
                    com.pvzce.common.level.mutation.MutationRegistry.get(PvzceIds.MUTATION_NIGHTFALL);
            assertNotNull(nightfall);
            level.mutations().add(nightfall, com.pvzce.common.level.mutation.Mutation.Roll.NONE);
            assertTrue(level.isNight());
            harness.server().saveGame();

            harness.send(new com.pvzce.common.network.packet.RestartLevelC2S(
                    LEVEL.toString(), WORLD, List.of()));
            awaitLevelNight(harness, false);
            LevelServer restarted = harness.server().level();
            assertNotNull(restarted);
            assertFalse(restarted.isNight(),
                    "重新开始 puts the day back: the night belonged to the run being discarded");
            assertTrue(restarted.mutations().activeIds().isEmpty(),
                    "and the mutation list starts empty");
        }
    }

    @Test
    void backingOutOfTheChooserStillDiscardsTheRun(@TempDir Path gameDir) throws Exception {
        // The save prompt's 重新开始 now discards the run on the spot, so a player who opens the
        // chooser and backs out does not find the same night waiting for them again.
        Path saveFile;
        try (ServerHarness harness = ServerHarness.createWithWorld(gameDir, WORLD, true)) {
            harness.server().createLevelForTest(LEVEL.toString(), WORLD, true);
            LevelServer level = harness.server().level();
            assertNotNull(level);
            com.pvzce.common.level.mutation.Mutation nightfall =
                    com.pvzce.common.level.mutation.MutationRegistry.get(PvzceIds.MUTATION_NIGHTFALL);
            assertNotNull(nightfall);
            level.mutations().add(nightfall, com.pvzce.common.level.mutation.Mutation.Roll.NONE);
            assertTrue(level.isNight());
            harness.server().saveGame();
            saveFile = LevelKey.levelDir(new WorldStore(gameDir).worldDir(WORLD), LEVEL)
                    .resolve("level.dat");
            assertTrue(java.nio.file.Files.isRegularFile(saveFile));

            // The level is left (the prompt is about a run on disk) and the answer is 重新开始.
            harness.send(new com.pvzce.common.network.packet.LeaveLevelC2S());
            harness.server().createLevelForTest(LEVEL.toString(), WORLD, false);
            harness.send(new com.pvzce.common.network.packet.DiscardLevelSaveC2S(
                    LEVEL.toString(), WORLD));
            awaitSaveGone(harness, saveFile);
        }
        assertFalse(java.nio.file.Files.isRegularFile(saveFile),
                "the refused run is gone from disk, not waiting to be offered again");
    }

    /** Ticks the connection until the file is gone, or fails. */
    private static void awaitSaveGone(ServerHarness harness, Path saveFile) throws Exception {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < deadline) {
            harness.pair().client().tick();
            if (!java.nio.file.Files.isRegularFile(saveFile)) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("the discarded save is still at " + saveFile);
    }

    /** Waits until the running level answers the question, ticking the connection while it waits. */
    private static void awaitLevelNight(ServerHarness harness, boolean night) throws Exception {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (System.nanoTime() < deadline) {
            harness.pair().client().tick();
            LevelServer level = harness.server().level();
            if (level != null && level.isNight() == night) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("the level never reported night=" + night);
    }

    /** Spins the server's connection until at least {@code count} mutations are on the field. */
    private static void awaitMutations(ServerHarness harness, int count) throws Exception {
        long deadline = System.nanoTime() + 20_000_000_000L;
        while (System.nanoTime() < deadline) {
            harness.pair().client().tick();
            LevelServer level = harness.server().level();
            if (level != null && level.mutations() != null
                    && level.mutations().activeIds().size() >= count) {
                return;
            }
            Thread.sleep(10);
        }
        LevelServer level = harness.server().level();
        throw new AssertionError("no " + count + " mutations in twenty seconds; on the field: "
                + (level == null || level.mutations() == null
                        ? "-" : level.mutations().activeIds()));
    }
}
