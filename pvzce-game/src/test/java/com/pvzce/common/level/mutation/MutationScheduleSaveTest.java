package com.pvzce.common.level.mutation;

import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.MutationData;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.tag.TestContent;
import com.pvzce.server.entity.ZombieEntity;
import com.pvzce.server.level.LevelServer;
import com.pvzce.testutil.TestLevels;

import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A staged mutation survives a save: the ones that already arrived do not arrive again.
 *
 * <p>This is the half of the tutorial's script that a save can break, and it breaks quietly: a
 * resumed lesson that replays its first mutation looks like the tutorial starting over rather than
 * like a bug, and a lesson that replays all four is a level the player cannot finish reading. What
 * the save carries is how far through the script the run is - a count, because the entries arrive in
 * the order they are written.
 */
class MutationScheduleSaveTest {
    @BeforeAll
    static void load() throws Exception {
        TestContent.loadBuiltInContentAndTags();
    }

    @Test
    void aResumedRunDoesNotReplayTheScriptItHasAlreadySeen() {
        LevelServer level = tutorialLevel();
        MutationData data = level.mutationData();
        assertNotNull(data, "the fixture is the tutorial, which stages its mutations");
        int stopAt = data.schedule().get(1).atTick();
        for (int tick = 0; tick <= stopAt; tick++) {
            level.tick(packet -> { });
            killAll(level);
        }
        assertEquals(2, level.mutations().scheduledFired(),
                "two entries should have fired by tick " + stopAt + ": "
                        + level.mutations().activeIds());
        int firedBefore = level.mutations().scheduledFired();
        List<Identifier> activeBefore = level.mutations().activeIds();

        LevelServer resumed = new LevelServer(tutorialDef());
        resumed.restore(level.save());
        assertEquals(firedBefore, resumed.mutations().scheduledFired(),
                "the resumed run is as far through the script as the save was");
        assertEquals(activeBefore, resumed.mutations().activeIds(),
                "and holds the same mutations");

        // The rest of the script still arrives, in order, and nothing repeats.
        int stopAtEnd = data.schedule().get(data.schedule().size() - 1).atTick();
        for (int tick = stopAt; tick <= stopAtEnd + 30; tick++) {
            resumed.tick(packet -> { });
            killAll(resumed);
        }
        assertEquals(data.schedule().size(), resumed.mutations().scheduledFired(),
                "the whole script has fired");
        assertEquals(data.schedule().size(), resumed.mutations().activeIds().size(),
                "and each entry once: " + resumed.mutations().activeIds());
        assertTrue(resumed.mutations().activeIds().containsAll(activeBefore),
                "including the two that had already arrived before the save");
    }

    private static LevelDef tutorialDef() {
        LevelDef def = BuiltInRegistries.LEVELS.get(MutationLevels.tutorialId());
        assertNotNull(def);
        return TestLevels.copy(def).build();
    }

    private static LevelServer tutorialLevel() {
        return new LevelServer(tutorialDef(), List.of(PvzceIds.id("sun"),
                PvzceIds.id("pea_shooter")));
    }

    private static void killAll(LevelServer level) {
        for (var entity : level.entities()) {
            if (entity instanceof ZombieEntity zombie && !zombie.isRemoved()) {
                zombie.damageBody(10_000, level);
            }
        }
    }
}
