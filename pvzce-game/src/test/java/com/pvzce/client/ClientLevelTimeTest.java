package com.pvzce.client;

import com.pvzce.common.network.packet.SlotInfo;
import com.pvzce.common.network.packet.TimeOfDayS2C;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientLevelTimeTest {
    @Test
    void smoothDayTicksAdvancesBetweenServerSyncs() throws Exception {
        ClientLevel level = new ClientLevel();
        level.init("test", 9, 5,
                List.of(new SlotInfo(0, "pvzce:pea_shooter", "plant", 100, 0, true)), List.of(),
                List.of(), 6, List.of(), List.of(), "", "", List.of());
        level.setTimeOfDay(new TimeOfDayS2C(600, 600, 600));

        long start = System.nanoTime();
        float before = level.smoothDayTicks();
        Thread.sleep(80);
        float after = level.smoothDayTicks();
        // Derived from the time that really passed, not from the sleep that was asked for: a
        // loaded machine that oversleeps must not turn "the client clock is not frozen and does
        // not run away" into a failure. The two properties are what this test is for - the
        // extrapolation runs at the server's rate between syncs, and it is bounded.
        double elapsedTicks = (System.nanoTime() - start) * 60D / 1_000_000_000D;

        assertTrue(after > before, "client clock should advance between server syncs");
        assertTrue(after - before <= elapsedTicks + 2D,
                "extrapolation must stay near the server rate, advanced " + (after - before)
                        + " ticks in " + elapsedTicks + " ticks of wall clock");
    }

    @Test
    void interpolatedNightSwitchIsExact() {
        ClientLevel level = new ClientLevel();
        level.init("test", 9, 5, List.of(), List.of(), List.of(), 6, List.of(), List.of(), "", "",
                List.of());
        level.setTimeOfDay(new TimeOfDayS2C(0, 600, 600));

        assertFalse(level.isNightAt(599.9F));
        assertTrue(level.isNightAt(600F));
        assertFalse(level.isNightAt(0F));
    }

}
