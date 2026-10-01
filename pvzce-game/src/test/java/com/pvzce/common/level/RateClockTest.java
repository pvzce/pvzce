package com.pvzce.common.level;

import com.pvzce.common.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RateClockTest {
    @Test
    void changingHasteNeverRevaluesPastWorkAndSavingPreservesFractionalProgress() {
        RateClock clock = new RateClock();
        for (int i = 0; i < 1000; i++) {
            assertEquals(1, clock.step(1F));
        }
        assertEquals(3, clock.step(3F), "entering a network earns only this tick's haste");
        assertEquals(1, clock.step(1F), "disconnecting earns an ordinary tick, without a stall");
        assertEquals(1, clock.step(1.75F));
        CompoundTag saved = new CompoundTag();
        clock.save(saved);
        RateClock restored = new RateClock();
        restored.load(saved);
        assertEquals(1, restored.step(0.5F), "the saved three quarters carry into the next half step");
        assertEquals(clock.step(0.5F), 1);
    }
}
