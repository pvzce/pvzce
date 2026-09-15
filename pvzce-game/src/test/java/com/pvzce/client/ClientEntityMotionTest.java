package com.pvzce.client;

import com.pvzce.api.entity.EntityAnimations;
import com.pvzce.api.entity.EntityLayers;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The client draws entities between the server's samples rather than on them.
 *
 * <p>{@code LevelServer} publishes positions every third tick - 20 times a second - while
 * the client draws 60 to 260 times. Drawing the raw packet position is a staircase: the
 * zombie holds still for several frames and then jumps, which is most visible on the one
 * zombie the player is watching (the one being shot at). These pin the two halves of the
 * fix: a fresh entity is drawn exactly where it spawned, and an update starts a slide
 * instead of a jump.
 *
 * <p>The slide itself is asserted as "between the samples, and never past the new one",
 * not as an exact position: it is driven by the wall clock, and a test that demanded a
 * particular fraction of a 50ms window would be a test of how busy the machine is.
 */
class ClientEntityMotionTest {
    @Test
    void aFreshEntityIsDrawnExactlyWhereItSpawned() {
        ClientEntity entity = zombie(1.5F, 2.5F);

        // Nothing has been interpolated yet, or the first frame of every entity in the level
        // would slide in from the corner of the board.
        assertEquals(1.5F, entity.visualCellX(), 1e-6F);
        assertEquals(2.5F, entity.visualCellY(), 1e-6F);
    }

    @Test
    void anUpdateStartsASlideFromTheOldPosition() {
        ClientEntity entity = zombie(1.5F, 2.5F);
        entity.update(1.0F, 2.5F, 200, EntityAnimations.WALK, 0F, 0);

        // The authoritative position moved in one step, the drawn one did not: it is on its
        // way there over the sync period.
        assertEquals(1.0F, entity.cellX(), 1e-6F);
        float drawn = entity.visualCellX();
        assertTrue(drawn <= 1.5F && drawn >= 1.0F,
                "the drawn x must stay between the two samples, was " + drawn);
    }

    @Test
    void aStillEntityDoesNotDrift() {
        // Most of the board does not move at all: plants, walls, parked mowers. Their drawn
        // position has to be their position, exactly, or a lawn of plants would shimmer.
        ClientEntity entity = zombie(4.5F, 1.5F);
        entity.update(4.5F, 1.5F, 200, EntityAnimations.IDLE, 0F, 0);
        assertEquals(4.5F, entity.visualCellX(), 1e-6F);
        assertEquals(1.5F, entity.visualCellY(), 1e-6F);
    }

    @Test
    void aFallingDropSlidesDownRatherThanStepping() {
        // A drop's descent is carried by its height, not by its cell: interpolating only x
        // and y would leave the sun's fall at 20 Hz while everything around it was smooth.
        ClientEntity drop = new ClientEntity(2, "resource", "pvzce:sun", 4.5F, 2.5F, 1,
                EntityLayers.GROUND, EntityAnimations.IDLE, 2.2F, "");
        assertEquals(2.2F, drop.visualHeight(), 1e-6F);

        drop.update(4.5F, 2.5F, 1, EntityAnimations.IDLE, 1.6F, -1);
        float drawn = drop.visualHeight();
        assertTrue(drawn <= 2.2F && drawn >= 1.6F,
                "the drawn height must stay between the two samples, was " + drawn);
        assertEquals(1.6F, drop.height(), 1e-6F, "the packet's height is untouched");
    }

    @Test
    void theAuthoredPositionIsStillTheServersNumber() {
        // The interpolation is a rendering position and nothing else: hit tests, camera
        // maths and every test in this suite read cellX(), and it must stay the packet's.
        ClientEntity entity = zombie(1.5F, 2.5F);
        entity.update(0.75F, 3.25F, 200, EntityAnimations.WALK, 0F, 0);
        assertEquals(0.75F, entity.cellX(), 1e-6F);
        assertEquals(3.25F, entity.cellY(), 1e-6F);
    }

    private static ClientEntity zombie(float x, float y) {
        return new ClientEntity(1, "zombie", "pvzce:basic_zombie", x, y, 200,
                EntityLayers.GROUND, EntityAnimations.WALK, 0F, "");
    }
}
