package com.pvzce.server;

import com.pvzce.common.PvzceConstants;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** /tick backend: rate, freeze, step, sprint. */
class PvzceTickRateManagerTest {
    @Test
    void defaultRateIsSixtyTicksPerSecond() {
        PvzceTickRateManager manager = new PvzceTickRateManager();
        assertEquals(60F, manager.tickRate(), 0.001F);
        assertTrue(manager.advance(PvzceConstants.NANOS_PER_TICK));
        assertFalse(manager.advance(0));
    }

    @Test
    void freezeStopsTickingAndStepRunsThenRefreezes() {
        PvzceTickRateManager manager = new PvzceTickRateManager();
        manager.setFrozen(true);
        assertFalse(manager.advance(PvzceConstants.NANOS_PER_TICK));

        assertTrue(manager.stepGameIfPaused(2));
        assertTrue(manager.advance(0));
        assertTrue(manager.advance(0));
        assertFalse(manager.advance(0));
        assertTrue(manager.isFrozen(), "step should re-freeze after its budget is spent");
    }

    @Test
    void rateChangeAndSprintBehave() {
        PvzceTickRateManager manager = new PvzceTickRateManager();
        manager.setTickRate(120F);
        assertEquals(120F, manager.tickRate(), 0.001F);
        assertEquals(Math.round(1_000_000_000L / 120F), manager.nanosPerTick());

        manager.setFrozen(true);
        manager.requestSprint(2);
        assertFalse(manager.isFrozen(), "sprint should unfreeze the server");
        assertTrue(manager.advance(0));
        assertTrue(manager.advance(0));
        assertFalse(manager.isSprinting());
    }

    @Test
    void resetRestoresDefaultRateAndClearsSprintFreezeAndStep() {
        PvzceTickRateManager manager = new PvzceTickRateManager();
        manager.setTickRate(30F);
        manager.setFrozen(true);
        manager.requestSprint(5);
        manager.resetTickRate();

        assertEquals(PvzceTickRateManager.DEFAULT_TICK_RATE, manager.tickRate(), 0.001F);
        assertFalse(manager.isFrozen());
        assertFalse(manager.isSprinting());
        assertFalse(manager.isStepping());
        assertEquals(PvzceConstants.NANOS_PER_TICK, manager.nanosPerTick());
    }

    @Test
    void tickTimeSamplingComputesAverageAndPercentiles() {
        PvzceTickRateManager manager = new PvzceTickRateManager();
        for (int i = 1; i <= 10; i++) {
            manager.recordTickTime(i * 1_000_000L);
        }
        assertEquals(5_500_000L, manager.averageTickTimeNanos());
        assertEquals(6_000_000L, manager.percentileTickTimeNanos(0.5));
        assertEquals(10, manager.tickTimeSampleCount());
    }
}
