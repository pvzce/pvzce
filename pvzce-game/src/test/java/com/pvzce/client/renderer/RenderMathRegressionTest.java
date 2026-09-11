package com.pvzce.client.renderer;

import com.pvzce.client.animation.Timeline;
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
     */
    @Test
    void particleMotionDoesNotDependOnFrameRate() {
        ParticleEngine at60 = new ParticleEngine();
        ParticleEngine at120 = new ParticleEngine();
        at60.spawn("pvzce:bite", 1F, 1F);
        at120.spawn("pvzce:bite", 1F, 1F);

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

    /** A long frame is clamped, so a stall cannot teleport particles off-screen. */
    @Test
    void particleStepIsClampedForLongFrames() {
        ParticleEngine engine = new ParticleEngine();
        engine.spawn("pvzce:bite", 0F, 0F);
        engine.tick(5F);
        float[] position = engine.position(0);
        assertNotNull(position);
        assertTrue(Math.abs(position[0]) < 1F,
                "a 5 second frame must not move a particle 5 seconds' worth: " + position[0]);
    }

    /** Particles expire on the authored tick lifetime, converted to seconds. */
    @Test
    void particlesExpireAfterTheirAuthoredLifetime() {
        ParticleEngine engine = new ParticleEngine();
        engine.spawn("pvzce:bite", 0F, 0F);
        assertEquals(1, engine.count());
        // The bite particle lives 14 ticks at 60tps (~0.23s). Steps are themselves
        // clamped to 0.1s, so advance in frames rather than one huge step.
        for (int i = 0; i < 6; i++) {
            engine.tick(0.1F);
        }
        assertEquals(0, engine.count(), "the particle must expire once its lifetime elapses");
    }

    /**
     * A scene tile must cover exactly the board cell the highlight and the hit test
     * use. The renderer drew grass 1.25 cells wide (square 209px atlas crops centred
     * in an 80x100 board cell), so the drawn pattern's seams sat 0.125 cells to the
     * left of every cell boundary and the placement highlight looked offset.
     */
    @Test
    void sceneTilesCoverExactlyOneBoardCell() {
        for (int x = -2; x < 12; x++) {
            for (int y = -2; y < 8; y++) {
                SceneTileRenderer.CellQuad quad = SceneTileRenderer.cellQuad(x, y);
                assertEquals(x, quad.x(), 1e-6F, "a tile must start at its own cell's left edge");
                assertEquals(y, quad.y(), 1e-6F, "a tile must start at its own cell's bottom edge");
                assertEquals(1F, quad.width(), 1e-6F, "a tile must be exactly one cell wide");
                assertEquals(1F, quad.height(), 1e-6F, "a tile must be exactly one cell tall");
            }
        }
    }

    /**
     * The 6x6 fast path must land on the same six cells as six individual tiles: the
     * two paths used to be 0.625 cells apart, so a plain lawn and a mixed lawn rendered
     * the same cell at different places.
     */
    @Test
    void fullBlockTilesOnTheSameGridAsIndividualCells() {
        int cells = SceneTileRenderer.TILE_CELLS;
        for (int blockX = 0; blockX < 3; blockX++) {
            for (int blockY = 0; blockY < 2; blockY++) {
                SceneTileRenderer.CellQuad block = SceneTileRenderer.blockQuad(blockX, blockY);
                assertEquals(blockX * (float) cells, block.x(), 1e-6F);
                assertEquals(blockY * (float) cells, block.y(), 1e-6F);
                assertEquals(cells, block.width(), 1e-6F, "a block must span exactly its six cells");
                assertEquals(cells, block.height(), 1e-6F);
                // The k-th texture cell of the block covers board cell blockX*6 + k.
                for (int k = 0; k < cells; k++) {
                    float step = 1F / cells;
                    float textureCellLeft = block.x() + block.width() * (k * step);
                    float textureCellRight = block.x() + block.width() * ((k + 1) * step);
                    assertEquals(blockX * (float) cells + k, textureCellLeft, 1e-4F,
                            "texture cell " + k + " must start on board cell " + (blockX * cells + k));
                    assertEquals(blockX * (float) cells + k + 1F, textureCellRight, 1e-4F);
                }
            }
        }
    }

    /**
     * The board's cell size is fixed by the background art: nine 80px columns and five
     * 100px rows. Anything that draws per cell must use these units.
     */
    @Test
    void boardCellsMatchTheBackgroundLawnGrid() {
        assertEquals(80F, LevelStage.LAWN_WIDTH / LevelStage.BOARD_COLUMNS, 1e-4F);
        assertEquals(100F, LevelStage.LAWN_HEIGHT / LevelStage.BOARD_ROWS, 1e-4F);
        // A 9x5 board fills the lawn exactly; other sizes keep the native cell aspect.
        LevelStage.Board standard = LevelStage.board(1920, 1080, 9, 5);
        assertEquals(1.25F, standard.cellHeight() / standard.cellWidth(), 1e-3F,
                "board cells keep the 80x100 aspect ratio, so world-space x needs the "
                        + "1.25 sprite correction while scene tiles must not use it");
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
        // The animation anchor and the sprite fallback read the same number.
        assertEquals(EntityVisuals.of("plant").anchorLift(), EntityVisuals.anchorLift("plant"), 1e-6F);
        assertEquals(EntityVisuals.of("plant").baseZ(), EntityVisuals.baseZ("plant"), 1e-6F);
        assertNull(new ParticleEngine().position(0), "an empty engine has no particles to report");
    }
}
