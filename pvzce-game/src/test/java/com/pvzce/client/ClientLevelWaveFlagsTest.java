package com.pvzce.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The wave meter's flag list stays bounded, whatever the level's table holds.
 *
 * <p>An endless level's table is thousands of entries; the meter draws every frame, and the first
 * version of it walked that whole table and asked about each wave - 46ms of a 55ms frame on a pool
 * board. This pins the property that made the level unplayable rather than the shape of the fix:
 * the number of waves the meter looks at never exceeds the meter's own capacity.
 */
class ClientLevelWaveFlagsTest {
    @Test
    void aShortTableIsWalkedWhole() {
        int[] all = ClientLevel.waveFlagCandidates(6, 2, 128);
        assertEquals(6, all.length, "six waves, six candidates");
        assertEquals(0, all[0]);
        assertEquals(5, all[all.length - 1]);
    }

    @Test
    void aLongTableIsSampledDownToTheMetersCapacity() {
        int[] picked = ClientLevel.waveFlagCandidates(3_990, 7, 128);
        assertTrue(picked.length <= 128,
                "the meter may look at most at its capacity, was " + picked.length);
        assertTrue(picked.length > 100, "and it should still fill up, was " + picked.length);
        // Ascending, so the right-to-left drawing reads in wave order.
        for (int i = 1; i < picked.length; i++) {
            assertTrue(picked[i] > picked[i - 1], "candidates are ascending");
        }
    }

    @Test
    void theWindowFollowsThePlayer() {
        int[] early = ClientLevel.waveFlagCandidates(3_990, 0, 128);
        int[] late = ClientLevel.waveFlagCandidates(3_990, 3_900, 128);
        assertTrue(early[0] < late[0], "a run that has moved on looks further down the table");
        assertTrue(late[late.length - 1] <= 3_990, "and never past the end");
        assertTrue(late[0] >= 0, "nor before the start");
    }

    @Test
    void anEndlessTableCostsTheSameAsASmallOne() {
        // The property that matters: the work is bounded by the meter, not by the level.
        assertEquals(ClientLevel.waveFlagCandidates(200, 3, 64).length,
                ClientLevel.waveFlagCandidates(20_000, 3, 64).length,
                "twenty thousand waves must not cost more than two hundred");
    }
}
