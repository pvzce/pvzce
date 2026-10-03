package com.pvzce.client.renderer;

import com.pvzce.api.content.FogData;
import com.pvzce.common.level.mechanic.FogMechanic;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cloud's own arithmetic: the grid the tiles are laid out on, and the sheet they are cut from.
 *
 * <p>Both have already gone wrong once, and both fail silently on screen:
 *
 * <ul>
 *   <li>the grid is the original's own numbers - an 80x85 cell, a 210x190 tile at
 *       {@code x*80-15, y*85-30} - and it is the only reason the band is continuous instead of
 *       eight blobs per row. It is asserted <em>in cells</em> and with no board scale in it: the
 *       scale was multiplied in twice once, which drew every tile 1.8x too large at 1920x1080 and
 *       smeared a lamp's clearing across a whole oversized quad;</li>
 *   <li>the sheet is the layout: the frames a cell draws and how tall they are are read back from
 *       the PNG, so a sheet that was resliced or rescaled would draw the fog in the wrong place
 *       rather than throw. Read from the classpath, because that is where the renderer reads
 *       it.</li>
 * </ul>
 */
class FogCloudTest {
    /** The pool board's own cell, which is what the tile grid was measured in. */
    private static final float REFERENCE_CELL_X = 80F;
    private static final float REFERENCE_CELL_Y = 85F;

    /**
     * A tile hangs 15px left of its cell and 30px below it, and is 210x190.
     *
     * <p>In cells, because that is the space the grid lives in: one board cell, whatever the board
     * is drawn at. The tile is wider and taller than its own cell, which is what fills the band -
     * neighbouring tiles overlap by well over half.
     */
    @Test
    void theTilesAreLaidOutOnTheOriginalsGrid() {
        assertEquals(-15F / REFERENCE_CELL_X, FogCloud.OFFSET_X, 0.0001F,
                "a tile hangs 15px left of its cell");
        assertEquals(-30F / REFERENCE_CELL_Y, FogCloud.OFFSET_Y, 0.0001F, "and 30px below it");
        assertEquals(210F / REFERENCE_CELL_X, FogCloud.TILE_X, 0.0001F, "a tile is 210px wide");
        assertEquals(190F / REFERENCE_CELL_Y, FogCloud.TILE_Y, 0.0001F, "and 190px tall");
        assertTrue(FogCloud.TILE_X > 2F && FogCloud.TILE_Y > 2F,
                "so a tile covers more than its own cell, which is what fills the band");
        // The whole point of the numbers being in cells: nothing about them follows the window.
        assertEquals(2.625F, FogCloud.TILE_X, 0.0001F);
        assertEquals(0.1875F, -FogCloud.OFFSET_X, 0.0001F);
    }

    /**
     * Which frame of the sheet a cell draws.
     *
     * <p>The sheet is one continuous band cut into eight slices and a cell's slice is its x in the
     * backdrop's pixels over a frame's width, so three cells share a frame and the fourth moves on.
     * A level wider than 24 columns wraps rather than walking off the sheet.
     */
    @Test
    void aCellDrawsTheSliceOfTheBandItStandsOn() {
        assertEquals(0, FogCloud.frameFor(0));
        assertEquals(0, FogCloud.frameFor(2), "240px in is still the first frame");
        assertEquals(1, FogCloud.frameFor(3), "and 240px is the third frame's own slice");
        assertEquals(7, FogCloud.frameFor(20), "twenty cells in is the last slice");
        assertEquals(0, FogCloud.frameFor(21),
                "twenty-one cells is exactly one turn of the 1680px sheet");
        assertEquals(0, FogCloud.frameFor(-21), "and a cell off the left edge wraps too");
    }

    /** The drift stays inside the brightness the cloud is drawn at. */
    @Test
    void theDriftStaysInsideItsBand() {
        for (float seconds = 0F; seconds < 30F; seconds += 0.37F) {
            for (int cell = 0; cell < 12; cell++) {
                float brightness = FogCloud.brightnessAt(seconds, cell, cell % 7);
                assertTrue(brightness >= 0.55F && brightness <= 1F,
                        "cell " + cell + " at " + seconds + "s was " + brightness);
            }
        }
        assertTrue(FogCloud.brightnessAt(1.5F, 2, 3) != FogCloud.brightnessAt(1.5F, 6, 3),
                "neighbouring cells breathe out of step, which is what makes it weather");
    }

    /**
     * The sprite sheet really is the eight 210x190 frames the grid draws.
     *
     * <p>The failure this catches is silent: the cloud slices the sheet by frame count and reads
     * the frame's height off the texture, so a sheet that was resliced or rescaled would draw fog
     * at the wrong scale rather than throw.
     */
    @Test
    void theSheetHasTheFramesTheGridDraws() throws Exception {
        try (var stream = FogCloudTest.class.getResourceAsStream(
                "/assets/pvzce/textures/gui/screen/fog_cloud.png")) {
            assertNotNull(stream, "the fog cloud sheet has to be on the classpath;"
                    + " run tools/gen_fog_texture.py");
            java.awt.image.BufferedImage image = javax.imageio.ImageIO.read(stream);
            assertNotNull(image, "and it has to decode as a PNG");
            assertEquals(210, image.getWidth() / 8, "eight frames across");
            assertEquals(190, image.getHeight(), "each one 190px tall");
            assertEquals(image.getWidth() / 8F, FogCloud.TILE_X * REFERENCE_CELL_X, 0.001F,
                    "the frame's width is the tile's width in pixels");
            assertEquals(image.getHeight(), FogCloud.TILE_Y * REFERENCE_CELL_Y, 0.001F,
                    "and its height is the tile's");
        }
    }

    /**
     * A lamp takes a cell out of the cloud, which is what makes a lamp a hole rather than a shade.
     *
     * <p>The fold is asked of the same {@code FogMechanic.revealCoverage} the drawing uses, and the
     * point of the test is the <em>strength</em> of it: a plantern clears its own cell outright, so
     * that cell draws no tile at all, and a torchwood only dims its neighbours. Before this the
     * clearing was a quadratic falloff from the lamp's centre, which on a band drawn three tiles
     * deep composited to the same picture as no lamp at all - the player's report was "the
     * plantern's light circle is missing".
     */
    @Test
    void aLampClearsItsOwnCellsOutright() {
        FogMechanic.Reveal plantern = new FogMechanic.Reveal(1, 7.5F, 2.5F, 3F, 1F);
        assertEquals(0F, FogMechanic.revealCoverage(List.of(plantern), 7.5F, 2.5F), 0.0001F,
                "the lamp's own cell holds no fog");
        assertTrue(FogMechanic.revealCoverage(List.of(plantern), 8.5F, 2.5F) < 0.1F,
                "and the cell beside it is very nearly clear, as the original's own reach is");
        assertEquals(0.266F, FogMechanic.revealCoverage(List.of(plantern), 8.5F, 3.5F), 0.005F,
                "a diagonal neighbour keeps a quarter of it");
        assertTrue(FogMechanic.revealCoverage(List.of(plantern), 10.4F, 2.5F) > 0.9F,
                "while the rim is only just dimmed: a lamp has an edge");
        assertEquals(1F, FogMechanic.revealCoverage(List.of(plantern), 11F, 2.5F), 0.0001F,
                "and beyond the radius nothing is touched");

        FogMechanic.Reveal torchwood = new FogMechanic.Reveal(2, 2.5F, 2.5F, 1.4F, 0.8F);
        assertEquals(0.2F, FogMechanic.revealCoverage(List.of(torchwood), 2.5F, 2.5F), 0.0001F,
                "a torchwood is a weak lamp: its own cell keeps a fifth of the fog");
        assertTrue(FogMechanic.revealCoverage(List.of(torchwood), 3.4F, 2.5F) > 0.5F,
                "and its reach is one cell, not three");
        assertEquals(1F, FogMechanic.revealCoverage(List.of(torchwood), 4.5F, 2.5F), 0.0001F,
                "two cells away it lights nothing at all");
    }

    /** A board with no fog, or none a lamp reaches, draws the band untouched. */
    @Test
    void noLampsLeavesTheBandAlone() {
        assertEquals(1F, FogMechanic.revealCoverage(List.of(), 7.5F, 2.5F), 0.0001F);
        assertEquals(1F, FogMechanic.revealCoverage(null, 7.5F, 2.5F), 0.0001F);
        FogData fog = new FogData(6F, 9F, 0.94F);
        assertEquals(fog.alphaAt(8.5F),
                FogMechanic.alphaAt(fog, List.of(), 8.5F, 2.5F), 0.0001F,
                "and the folded alpha is the span's own where nothing lights it");
        assertFalse(FogMechanic.alphaAt(fog, List.of(), 5.5F, 2.5F) > 0F,
                "the clear side stays clear");
    }
}
