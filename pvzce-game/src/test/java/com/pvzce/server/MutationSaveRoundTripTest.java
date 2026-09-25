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
            "yard/endless/mutation_normal");

    @BeforeAll
    static void load() throws Exception {
        TestContent.loadBuiltInContentAndTags();
    }

    @Test
    void aSavedMutationRunResumesWithTheSameMutations(@TempDir Path gameDir) throws Exception {
        List<Identifier> before;
        int ticksUntilNext;
        float sunRate;
        Path saveFile;

        try (ServerHarness harness = ServerHarness.createWithWorld(gameDir, WORLD, true)) {
            harness.server().createLevelForTest(LEVEL.toString(), WORLD, true);
            LevelServer level = harness.server().level();
            assertNotNull(level, "the level has to be running");
            assertNotNull(level.mutations(), "and it has to be a mutation level");
            // The clock is shortened through the level's own rules, which is the state a resumed
            // run reads back: the manager arms its first countdown from them.
            level.setRule(PvzceIds.RULE_MUTATION_INITIAL_TICKS, 1);
            level.setRule(PvzceIds.RULE_MUTATION_INTERVAL_MULTIPLIER, 10F);
            awaitMutations(harness, 3);

            before = level.mutations().activeIds();
            ticksUntilNext = level.mutations().ticksUntilNext();
            sunRate = level.rules().getFloat(PvzceIds.RULE_SUN_RATE_MULTIPLIER);
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

        try (ServerHarness harness = ServerHarness.createWithWorld(gameDir, WORLD, true)) {
            harness.server().createLevelForTest(LEVEL.toString(), WORLD, false);
            LevelServer resumed = harness.server().level();
            assertNotNull(resumed);
            assertNotNull(resumed.mutations(), "the resumed level still mutates");
            assertEquals(before, resumed.mutations().activeIds(),
                    "and it is running the same mutations, in the same order");
            assertEquals(ticksUntilNext, resumed.mutations().ticksUntilNext(),
                    "with the arrival clock where the save left it");
            assertEquals(sunRate, resumed.rules().getFloat(PvzceIds.RULE_SUN_RATE_MULTIPLIER),
                    0.0001F,
                    "and the rules those mutations own restored rather than doubled");
        }
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
