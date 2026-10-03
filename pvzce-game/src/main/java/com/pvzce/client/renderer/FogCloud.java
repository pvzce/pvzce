package com.pvzce.client.renderer;

import com.pvzce.api.content.FogData;
import com.pvzce.api.util.Identifier;
import com.pvzce.client.PvzceClient;
import com.pvzce.common.level.mechanic.FogMechanic;

import java.util.List;

/**
 * The fog's cloud picture: one frame of the sprite band per board cell, on the original's grid.
 *
 * <p>The original's fog is not a gradient and not a flat wash - it is a <em>grid of cloud
 * sprites</em> ({@code Board::DrawFog}): for every cell of a 9x6 board it draws one 210x190 frame
 * of {@code IMAGE_FOG} at {@code x*80 - 15, y*85 + 20} in the backdrop's own pixels, so the frames
 * overlap by well over half and the band reads as one continuous cloud. This class is that grid,
 * and it is the only place the grid's arithmetic lives: the in-game overlay
 * ({@code FogClientMechanic}) and the seed-chooser preview both draw through it, one in world
 * cells and one in the GUI pixels of the chooser's board rectangle.
 *
 * <h2>Why the sizes are in cells and not in pixels</h2>
 *
 * <p>A tile is 210x190 of the backdrop's pixels on a board whose cell is 80x85 of them, so its
 * size <em>in cells</em> is 2.625 x 2.235 and its offset -0.1875 x -0.3529 - the same numbers on
 * every window, because the board is one cell grid whatever it is drawn at. The board's own scale
 * is the camera's business ({@link PvzceCamera#unitX()}), and this class never multiplies by it:
 * doing so drew every tile {@code boardScale} times too large (1.8x at 1920x1080), which stretched
 * the cloud and smeared a lamp's clearing across a whole oversized quad.
 *
 * <h2>What decides how dark a cell is</h2>
 *
 * <p>The level's {@code max_alpha}, flat, exactly as the original's {@code mGridCelFog} holds a
 * flat 200/255 for every cell right of the fog's edge - and what a lamp takes away from that
 * ({@link FogMechanic#revealCoverage}). The span's ramp is deliberately not folded in: it is the
 * rule's curve ("how dark is it here", {@link FogData#alphaAt}), and drawing it would turn the
 * band into a three-column gradient where the original has a wall. A cell the fog does not reach
 * at all - or one a lamp has cleared outright - draws nothing, which is what makes a lamp a hole
 * in the cloud rather than a lighter patch of it.
 */
public final class FogCloud {
    /** Where a tile hangs relative to its cell, in cells: 15px left of it and 30px below. */
    public static final float OFFSET_X = -15F / 80F;
    public static final float OFFSET_Y = -30F / 85F;
    /** One frame of the sheet, in cells: 210x190 of the backdrop's pixels. */
    public static final float TILE_X = 210F / 80F;
    public static final float TILE_Y = 190F / 85F;
    /** How many frames the sheet holds, and so the divisor of its width. */
    private static final int FRAMES = 8;
    /** One frame's width in the backdrop's own pixels: 1680 / 8. */
    private static final int FRAME_PIXELS = 210;

    /**
     * How far past the board's right edge the cloud extends, in cells.
     *
     * <p>A zombie spawns at {@code width + 0.6} and walks in; without a margin the cloud would
     * stop exactly at the last column and a zombie would be visible one tick before it steps onto
     * the board. The original draws one extra column past the last one for the same reason.
     */
    public static final float RIGHT_MARGIN_CELLS = 1.5F;

    /** The cloud band, cut into eight slices by {@code tools/gen_fog_texture.py}. */
    public static final Identifier TEXTURE =
            Identifier.withDefaultNamespace("textures/gui/screen/fog_cloud");

    /**
     * Which of the sheet's frames a cell draws.
     *
     * <p>The sheet is one continuous band cut into eight slices, so frame {@code n} holds the
     * band's pixels {@code 210n .. 210n+210} and which frame a cell wants follows from where the
     * cell is: its x in the backdrop's pixels, divided by a frame's width. The original picks a
     * frame at random instead ({@code mGridCelLook = Rand(20)}), which works for it because its
     * eight frames are interchangeable shapes laid out to tile; these are not, and a random pick
     * would show each cell the wrong slice and seam the band.
     */
    public static int frameFor(int cellX) {
        return Math.floorMod(cellX * 80 / FRAME_PIXELS, FRAMES);
    }

    /**
     * The per-cell drift: two slow sines whose phases are set by the cell's own column and row.
     *
     * <p>This is the original's {@code aMotion} - {@code 13 + 4*sin(t/900 + phaseY) +
     * 8*sin(t/500 + phaseX)} with the counters it uses - rescaled so that the value is a brightness
     * multiplier rather than a byte offset. The periods are its own (about 5.2s and 9.4s), and the
     * phase step per cell is its own 6*PI over the grid's 9 columns and 7 rows: neighbouring cells
     * breathe out of step, which is the whole point - a band that changed as one would read as the
     * screen dimming rather than as fog.
     */
    private static final double MOTION_RATE_X = 2.0 * Math.PI / (900.0 / 60.0);
    private static final double MOTION_RATE_Y = 2.0 * Math.PI / (500.0 / 60.0);
    private static final double MOTION_PHASE_X = 6.0 * Math.PI / 9.0;
    private static final double MOTION_PHASE_Y = 6.0 * Math.PI / 7.0;
    /** Where the drift sits and how far it swings, after the original's asymmetric two terms. */
    private static final double MOTION_CENTRE = 0.72;
    private static final double MOTION_SWING = 0.18;
    /**
     * The dimmest a cell's cloud is ever drawn.
     *
     * <p>The drift's own floor; the original's darkest cell works out near the same number. Below
     * it the cloud starts to read as a hole in the fog rather than as thin fog.
     */
    private static final float BRIGHTNESS_FLOOR = 0.55F;

    private FogCloud() {
    }

    /**
     * Draws the band over one board rectangle.
     *
     * <p>The rectangle is in whatever space the caller is drawing in - world cells for the board,
     * logical GUI pixels for the seed chooser - and is given as its origin and its cell size, so
     * the same grid arithmetic lands on either. {@code offsetCells} is how far the whole cloud has
     * been pushed to the right (a blover's gust, or the fog-retreat buff): the picture travels as
     * one piece, like the original's {@code mFogOffset}, which is what makes blowing the fog away
     * a movement rather than a disappearance.
     *
     * @param columns      the board's width in cells
     * @param rows         the board's height in cells; one extra row is drawn past it, as the
     *                     original draws one past its own
     * @param seconds      the client's own clock, for the per-cell drift
     * @param offsetCells  how far right the cloud has slid; 0 in the ordinary case
     */
    public static void render(PvzceClient client, float originX, float originY,
                              float cellW, float cellH, int columns, int rows,
                              FogData fog, List<FogMechanic.Reveal> lamps,
                              float seconds, float offsetCells, float z) {
        if (fog == null || fog.maxAlpha() <= 0F || fog.endColumn() <= fog.startColumn()) {
            return;
        }
        // The sheet is the layout: how many frames it holds and how tall each one is are read
        // back from the PNG, so reslicing the art needs no change here.
        int frameWidth = Math.max(1, client.textureWidth(TEXTURE) / FRAMES);
        int frameHeight = client.textureHeight(TEXTURE);

        float tileW = TILE_X * cellW;
        float tileH = TILE_Y * cellH;
        // The cells whose *content* is drawn, in the cloud's own un-shifted numbering: the
        // picture is then drawn at that cell plus the slide.
        int firstColumn = Math.max(0,
                (int) Math.floor(fog.startColumn() - TILE_X - offsetCells));
        int lastColumn = (int) Math.ceil(columns + RIGHT_MARGIN_CELLS - offsetCells);
        for (int cellX = firstColumn; cellX <= lastColumn; cellX++) {
            // The cell's own middle decides whether it is fogged at all: a cell the fog does not
            // reach draws nothing, and neither does one a lamp has cleared outright.
            if (fog.alphaAt(cellX + 0.5F) <= 0F) {
                continue;
            }
            float u0 = frameFor(cellX) * (float) frameWidth;
            float x = originX + (cellX + OFFSET_X + offsetCells) * cellW;
            for (int cellY = 0; cellY <= rows; cellY++) {
                float density = fog.maxAlpha()
                        * FogMechanic.revealCoverage(lamps, cellX + 0.5F, cellY + 0.5F);
                if (density <= 0F) {
                    continue;
                }
                float brightness = brightnessAt(seconds, cellX, cellY);
                client.drawTextureShaded(TEXTURE, u0, 0F, u0 + frameWidth, frameHeight,
                        x, originY + (cellY + OFFSET_Y) * cellH, tileW, tileH, z,
                        brightness, brightness, brightness,
                        density, density, density, density);
            }
        }
    }

    /** One cell's own brightness this frame; see the drift constants above. */
    public static float brightnessAt(float seconds, int cellX, int cellY) {
        double drift = MOTION_CENTRE
                + MOTION_SWING * Math.sin(seconds * MOTION_RATE_Y + MOTION_PHASE_Y * cellY)
                + MOTION_SWING * Math.sin(seconds * MOTION_RATE_X + MOTION_PHASE_X * cellX);
        return Math.max(BRIGHTNESS_FLOOR, Math.min(1F, (float) drift));
    }
}
