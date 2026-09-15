package com.pvzce.client.renderer;

import com.pvzce.client.animation.Timeline;
import com.pvzce.api.content.ParticleDef;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.tag.TestContent;
import com.pvzce.client.particle.ParticleEngine;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the rendering-adjacent defects that can be checked without a
 * GL context: the camera's mouse mapping, timeline boundaries, the shared visual
 * table and frame-rate-independent particle stepping.
 */
class RenderMathRegressionTest {
    private static final int WIDTH = 1920;
    private static final int HEIGHT = 1080;

    /**
     * {@code inBoard} takes a raw top-down GLFW cursor Y, while the board rectangle
     * is in bottom-up framebuffer pixels. Comparing them directly accepted the
     * board's mirror image, so the bottom of the lawn was unclickable.
     */
    @Test
    void cameraBoardHitTestFlipsTheCursorY() {
        PvzceCamera camera = new PvzceCamera(WIDTH, HEIGHT, 9, 5);

        // A world point maps to a window pixel; feeding that pixel back must land in
        // the board. This is the round trip the old code failed.
        float boardBottomWorldY = 0.25F;
        float boardTopWorldY = 4.75F;
        for (float worldY : new float[]{boardBottomWorldY, 1F, 2.5F, 4F, boardTopWorldY}) {
            float worldX = 4.5F;
            double windowX = camera.screenX(worldX);
            double windowY = HEIGHT - camera.screenY(worldY);
            assertTrue(camera.inBoard(windowX, windowY),
                    "a window pixel over board cell (" + worldX + "," + worldY + ") must be inside the board");
        }

        // And the mirror image must not be accepted: a point the same distance above
        // the board's top edge used to pass because it mirrored onto the board.
        float boardTopWindowY = HEIGHT - camera.screenY(5F);
        assertFalse(camera.inBoard(camera.screenX(4.5F), boardTopWindowY - 4),
                "the mirrored strip above the board must not be treated as inside it");
    }

    /** The camera's world mapping and its hit test agree. */
    @Test
    void cameraCellsMatchTheHitTest() {
        PvzceCamera camera = new PvzceCamera(WIDTH, HEIGHT, 9, 5);
        for (int cellY = 0; cellY < 5; cellY++) {
            for (int cellX = 0; cellX < 9; cellX++) {
                double windowX = camera.screenX(cellX + 0.5F);
                double windowY = HEIGHT - camera.screenY(cellY + 0.5F);
                assertTrue(camera.inBoard(windowX, windowY), "cell centre must be inside the board");
                assertEquals(cellX, camera.cellX(windowX, windowY), "cell column must round-trip");
                assertEquals(cellY, camera.cellY(windowX, windowY), "cell row must round-trip");
            }
        }
    }

    /** Looping wraps, non-looping clamps, and the sampling boundary never overshoots. */
    @Test
    void timelineWrapsAndClampsConsistently() {
        assertEquals(0.5D, Timeline.wrap(0.5D, 1F, true), 1e-9);
        assertEquals(0.5D, Timeline.wrap(2.5D, 1F, true), 1e-9);
        assertEquals(0.5D, Timeline.wrap(-0.5D, 1F, true), 1e-9, "negative time wraps forward");
        assertEquals(1D, Timeline.wrap(2.5D, 1F, false), 1e-9, "a one-shot clamps at its end");
        assertEquals(0D, Timeline.wrap(0.5D, 0F, true), 1e-9, "a zero-length clip samples at its start");

        assertTrue(Timeline.wrapForSampling(1D, 1F, false) < 1F,
                "sampling at the exact end of a one-shot must stay inside the clip");
        assertEquals(0.5D, Timeline.wrapForSampling(0.5D, 1F, false), 1e-9);
    }

    /** Frame lookup at a boundary returns the frame that is actually visible. */
    @Test
    void timelineFrameLookupIsStableAtBoundaries() {
        // Delays are authored as floats (0.1 is really 0.100000001 as a double), so a
        // sample at exactly 0.1 still falls inside the first frame. Frames are tested
        // from clearly inside each slot.
        float[] delays = {0.1F, 0.2F, 0.3F};
        assertEquals(0, Timeline.frameAt(delays, 3, 0.0D, false));
        assertEquals(0, Timeline.frameAt(delays, 3, 0.05D, false));
        assertEquals(1, Timeline.frameAt(delays, 3, 0.2D, false), "inside the second frame");
        assertEquals(2, Timeline.frameAt(delays, 3, 0.5D, false), "inside the third frame");
        assertEquals(2, Timeline.frameAt(delays, 3, 100D, false), "past the end holds the last frame");
        // Looping wraps back to the first frame once past the total (0.65s is 0.05s
        // into the second cycle).
        assertEquals(0, Timeline.frameAt(delays, 3, 0.65D, true));
        assertEquals(1, Timeline.frameAt(delays, 3, 0.85D, true));
        assertEquals(0.6F, Timeline.totalDuration(delays, 3), 1e-6F);
        assertEquals(0.3F, Timeline.frameStart(delays, 2), 1e-6F);
        assertEquals(-1, Timeline.frameAt(delays, 0, 0.5D, false), "an empty flipbook has no frame");
    }

    /** Missing or single-entry delay arrays use one rule, not three. */
    @Test
    void timelineDelayDefaultsAreShared() {
        assertEquals(Timeline.DEFAULT_FRAME_DELAY, Timeline.delayAt(new float[0], 3), 1e-6F);
        assertEquals(Timeline.DEFAULT_FRAME_DELAY, Timeline.delayAt(null, 0), 1e-6F);
        assertEquals(0.2F, Timeline.delayAt(new float[]{0.2F}, 7), 1e-6F, "one delay applies to every frame");
        assertEquals(0.3F, Timeline.delayAt(new float[]{0.1F, 0.3F}, 9), 1e-6F, "the last delay repeats");
        assertEquals(Timeline.MIN_FRAME_DELAY, Timeline.delayAt(new float[]{0F}, 0), 1e-9F,
                "a zero delay is clamped so the wrap maths cannot divide by zero");
    }

    /**
     * Particle motion is per second. The engine used to advance a fixed 1/60 per
     * frame, so particles ran twice as fast at the default 120 FPS cap.
     *
     * <p>The effect id has to be a registered particle: an unknown one draws nothing
     * (the engine deliberately stopped inventing a red square for it), so these tests
     * look one up from the shipped set rather than naming a legacy effect.
     */
    @Test
    void particleMotionDoesNotDependOnFrameRate() throws Exception {
        String effect = movingEffect();
        ParticleEngine at60 = new ParticleEngine();
        ParticleEngine at120 = new ParticleEngine();
        at60.spawn(effect, 1F, 1F);
        at120.spawn(effect, 1F, 1F);

        // 0.2s of simulated time, split into different numbers of frames (short
        // enough that both particles are still alive to be compared).
        for (int i = 0; i < 12; i++) {
            at60.tick(0.2F / 12F);
        }
        for (int i = 0; i < 24; i++) {
            at120.tick(0.2F / 24F);
        }

        float[] slow = at60.position(0);
        float[] fast = at120.position(0);
        assertNotNull(slow);
        assertNotNull(fast);
        // Float accumulation differs slightly with the number of steps; the point is
        // that the result is frame-rate independent, not bit-identical. The old
        // per-frame step produced a 2x difference here.
        assertEquals(slow[0], fast[0], 0.02F, "motion must be the same at any frame rate");
        assertEquals(slow[1], fast[1], 0.02F);
    }

    /**
     * Some shipped effect that actually moves, and does so the same way every time.
     *
     * <p>The spread has to be zero: each engine rolls its own random spread at spawn, so a
     * definition that jitters would have the two engines flying in different directions and
     * the comparison below would be measuring the dice rather than the integrator.
     */
    private static ParticleDef movingDefinition() throws Exception {
        TestContent.loadBuiltInContentAndTags();
        for (Identifier id : BuiltInRegistries.PARTICLES.keySet()) {
            ParticleDef def = BuiltInRegistries.PARTICLES.get(id);
            if (def != null && def.motion().speed() > 0F && def.look().lifetime() > 0.3F
                    && def.motion().speedSpread() == 0F && def.motion().angleSpread() == 0F
                    && def.look().scaleSpread() == 0F && def.count() == 1) {
                return def;
            }
        }
        throw new IllegalStateException("no moving particle definition was loaded");
    }

    private static String movingEffect() throws Exception {
        return movingDefinition().id().toString();
    }

    /** Every entity kind has a visual entry, and the table is the single source. */
    @Test
    void entityVisualTableCoversEveryKind() {
        for (String kind : new String[]{"plant", "zombie", "projectile", "sun"}) {
            EntityVisuals.Visuals visuals = EntityVisuals.of(kind);
            assertTrue(visuals.spriteWidth() > 0F, kind + " needs a sprite width");
            assertTrue(visuals.spriteHeight() > 0F, kind + " needs a sprite height");
            assertTrue(visuals.baseZ() >= 0F, kind + " needs a render layer");
        }
        // Unknown kinds still render, with the default bucket rather than none.
        assertEquals(5, EntityVisuals.sortBucket("something_new", 0));
        assertEquals(EntityVisuals.UNDERGROUND_SORT_BUCKET, EntityVisuals.sortBucket("zombie", -1),
                "a burrowing zombie draws under the lawn regardless of kind");
        assertNull(new ParticleEngine().position(0), "an empty engine has no particles to report");
    }
}
