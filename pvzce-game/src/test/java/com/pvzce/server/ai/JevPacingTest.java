package com.pvzce.server.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two tiers' intervals, as the board's pressure changes them.
 *
 * <p>Tested on the arithmetic rather than through a running level, because what the user asked for is a
 * table of numbers - fifteen seconds for the commander once a zombie is in the plant zone, ten at the
 * door, and the tactical model speeding up to match - and a table is what a test can hold still.
 */
class JevPacingTest {
    @Test
    void theCommanderTightensAsTheZombiesArrive() {
        // The plant side's calm rate, then the two pressed ones.
        assertEquals(20 * 60, JevBrain.commanderInterval(true, JevBrain.Pressure.CALM));
        assertEquals(15 * 60, JevBrain.commanderInterval(true, JevBrain.Pressure.IN_PLANT_ZONE));
        assertEquals(10 * 60, JevBrain.commanderInterval(true, JevBrain.Pressure.AT_THE_DOOR));
    }

    @Test
    void theZombieSideKeepsItsOwnCalmRateAndSharesThePressedOnes() {
        assertEquals(30 * 60, JevBrain.commanderInterval(false, JevBrain.Pressure.CALM));
        assertEquals(15 * 60, JevBrain.commanderInterval(false, JevBrain.Pressure.IN_PLANT_ZONE));
        assertEquals(10 * 60, JevBrain.commanderInterval(false, JevBrain.Pressure.AT_THE_DOOR));
    }

    @Test
    void theTacticalLoopSpeedsUpWithIt() {
        assertEquals(180, JevBrain.decisionInterval(180, JevBrain.Pressure.CALM));
        assertEquals(120, JevBrain.decisionInterval(180, JevBrain.Pressure.IN_PLANT_ZONE));
        assertEquals(90, JevBrain.decisionInterval(180, JevBrain.Pressure.AT_THE_DOOR));
        // A level that asks for a very fast opponent still cannot be asked faster than the retry floor.
        assertTrue(JevBrain.decisionInterval(21, JevBrain.Pressure.AT_THE_DOOR) >= 20);
    }
}
