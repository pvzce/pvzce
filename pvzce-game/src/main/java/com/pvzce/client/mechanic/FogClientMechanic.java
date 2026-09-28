package com.pvzce.client.mechanic;

import com.pvzce.api.content.FogData;
import com.pvzce.api.util.Identifier;
import com.pvzce.client.ClientLevel;
import com.pvzce.client.PvzceClient;
import com.pvzce.client.renderer.PvzceCamera;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.level.mechanic.FogMechanic;
import com.pvzce.common.network.PacketByteBuf;

/**
 * World 4's fog, on the client: the cloud sitting over the right-hand side of the board.
 *
 * <p>Two jobs, and they are the two halves of "you cannot see what is over there":
 *
 * <ul>
 *   <li><b>Draw the cloud.</b> The original's own fog is a <em>grid of cloud sprites</em>, not a
 *       gradient: for every board cell it draws one 210x190 frame of {@code IMAGE_FOG}, so the
 *       frames overlap by well over half and the band reads as one continuous cloud. This does the
 *       same, with the frames of {@code textures/gui/screen/fog_cloud.png}
 *       ({@code tools/gen_fog_texture.py}); {@link #tileOrigin} is the grid's own arithmetic.</li>
 *   <li><b>Hide what is inside it.</b> An entity past {@link FogData#hidingColumn()} is not drawn
 *       at all. This is the half the player actually asked for - "a zombie is only drawn once it
 *       walks into view" - and it is why the fog is not merely a translucent sheet over
 *       everything: a cloud drawn over a fully drawn lawn still shows you the silhouette you are
 *       not supposed to have.</li>
 * </ul>
 *
 * <p>The span comes from the server (the level's block, a mutation, or the fog-retreat buff) and
 * the lamps come from the plants the client is already drawing, so nothing here re-derives either.
 *
 * <h2>What is deliberately not hidden</h2>
 *
 * <p>The lawn itself, the mowers, the card bar and the sun. Hiding the terrain would mean the fog
 * was a change to the board rather than a change to the view, and hiding the HUD would be hiding
 * the player's own hand. Only the things that <em>move</em> - zombies, projectiles, plants, drops -
 * are subject to it, which is also what makes the boundary legible: a plant that vanishes into the
 * cloud is the message.
 */
public final class FogClientMechanic implements ClientMechanic {
    /**
     * Where the cloud grid sits, in the pool board's own pixels, converted to cells.
     *
     * <p>The original draws frame {@code n} of {@code IMAGE_FOG} at
     * {@code x*80 + fogOffset - 15, y*85 + 20} on a 900x600 playfield whose bottom row's top edge
     * is at y=590, and {@code LeftFogColumn()} puts {@code fogOffset} at 0 for the levels that
     * start at column 4..6. Both axes are therefore the same two numbers every time: a tile hangs
     * 15px left of its cell and 30px below it, and is 210x190px.
     *
     * <p>Those are the <b>backdrop's own pixels</b> ({@link LevelStage#POOL} measures the pool's
     * board in exactly this unit), and they are converted to this build's board with
     * {@code camera.boardScale()} rather than assumed to be cells. They are not the same thing on
     * any window that is not the backdrop's native size: at 1920x1080 the board's cell is 144px
     * where the reference is 80, so a tile that took 15px for 15 world pixels would be drawn at
     * less than half its size and in the wrong place.
     */
    private static final float TILE_OFFSET_PX_X = -15F;
    private static final float TILE_OFFSET_PX_Y = -30F;
    /** The tile's size, in the same backdrop pixels. */
    private static final float TILE_PX_X = 210F;
    private static final float TILE_PX_Y = 190F;
    /** The pool board's cell in those pixels; {@link LevelStage#POOL}'s own numbers. */
    private static final float REFERENCE_CELL_PX_X = 80F;
    private static final float REFERENCE_CELL_PX_Y = 85F;
    /** The tile's offsets in reference cells, which is how the hiding rule reads them. */
    private static final float TILE_OFFSET_CELLS_X = TILE_OFFSET_PX_X / REFERENCE_CELL_PX_X;
    private static final float TILE_OFFSET_CELLS_Y = TILE_OFFSET_PX_Y / REFERENCE_CELL_PX_Y;

    /** How many frames the sheet holds, and so the divisor of its width. */
    private static final int FOG_FRAMES = 8;
    /** One frame's width in the backdrop's own pixels: 1680 / 8. */
    private static final int FOG_FRAME_PIXELS = 210;
    /** The fog grid's own size, in cells: room for the board plus the columns drawn past its edge. */
    private static final int CELL_LOOK_SIZE = 64;

    /** Above the board and below the HUD; the storm's own layer, since no level has both. */
    private static final float Z = 0.30F;

    private static final Identifier CLOUD =
            Identifier.withDefaultNamespace("textures/gui/screen/fog_cloud");

    /**
     * How far past the board's right edge the cloud extends, in cells.
     *
     * <p>A zombie spawns at {@code width + 0.6} and walks in; without a margin the cloud would
     * stop exactly at the last column and a zombie would be visible one tick before it steps onto
     * the board. The original draws one extra column past the last one for the same reason.
     */
    private static final float RIGHT_MARGIN_CELLS = 1.5F;

    /**
     * How many pieces a lamp's cell is cut into, per axis, before its alphas are sampled.
     *
     * <p>Without it the hole would be a polygon with one corner per cell - a lamp of radius 2.5
     * would be a decagon - and with it the boundary is smooth. The subdivision is per cell and only
     * where a lamp actually reaches, so an ordinary foggy board still draws one quad per cell.
     */
    private static final int LAMP_SUBDIVISION = 4;

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

    @Override
    public Identifier id() {
        return PvzceIds.MECHANIC_FOG;
    }

    @Override
    public void applySync(ClientLevel level, PacketByteBuf payload) {
        // Straight into the level's own state slot, which is also where the accessors below read
        // it from: the overlay is created once per level and holds what it was made with, so this
        // is what the next frame's overlay sees.
        level.setMechanicState(PvzceIds.MECHANIC_FOG, FogMechanic.Wire.decode(payload));
    }

    @Override
    public WorldOverlay createWorldOverlay(ClientLevel level) {
        FogData fog = fogOf(level);
        if (fog == null || fog.maxAlpha() <= 0F || fog.endColumn() <= fog.startColumn()) {
            // No fog, or a span that draws nothing. Registering an overlay anyway would make every
            // ordinary level pay for a feature it does not use.
            return null;
        }
        return new Fog(fog);
    }

    /**
     * The fog this level has right now.
     *
     * <p>What the server last synced, falling back to the level's own block before the first sync
     * arrives - a level that declares fog must not be drawn clear for the frame or two between the
     * level starting and its first mechanic sync.
     */
    public static FogData fogOf(ClientLevel level) {
        FogMechanic.Wire synced = wire(level);
        if (synced != null) {
            return synced.data();
        }
        return level.mechanicData(PvzceIds.MECHANIC_FOG, FogData.class);
    }

    /** The lamps the server last reported, or an empty list. */
    public static java.util.List<FogMechanic.Reveal> revealsOf(ClientLevel level) {
        FogMechanic.Wire synced = wire(level);
        return synced == null ? java.util.List.of() : synced.reveals();
    }

    private static FogMechanic.Wire wire(ClientLevel level) {
        return level.mechanicStateOrNull(PvzceIds.MECHANIC_FOG, FogMechanic.Wire.class);
    }

    /**
     * True when something standing at this column is inside the part of the fog that hides it.
     *
     * <p>Asked per entity by the board's render loop. An entity vanishes the moment it crosses the
     * line - no fade, because a zombie the player can half see is a zombie the player will argue
     * about. What walks in from the right does the opposite: it appears at the line, fully drawn.
     *
     * <p>The line is {@link #hidingColumn}, and what it crosses is the cloud: a fifth of a column
     * into the first fogged cell, which is where the original hides what is inside it.
     *
     * <p>The lamp is folded in after it, because a lamp only ever takes fog away: a plantern
     * standing in the dark is lit around itself, and what is standing in that light has to be
     * drawn.
     */
    public static boolean hides(ClientLevel level, float x, float y) {
        FogData fog = fogOf(level);
        if (fog == null || fog.maxAlpha() <= 0F) {
            return false;
        }
        if (x < hidingColumn(fog)) {
            return false;
        }
        java.util.List<FogMechanic.Reveal> lamps = revealsOf(level);
        if (lamps.isEmpty()) {
            return true;
        }
        return FogMechanic.alphaAt(fog, lamps, x, y) >= fog.maxAlpha() * HIDE_FRACTION;
    }

    /**
     * The column past which what is standing there cannot be made out.
     *
     * <p>A fifth of a column into the first fogged cell: the boundary the original has, where a
     * zombie is drawn while it steps into the cloud and gone once it is inside. The number is a fact
     * about the cloud rather than a preference - a tile hangs 0.1875 of a cell to its left, so that
     * is where the fog over a cell begins - and it is expressed as a fraction of the level's span so
     * that a level which moves its fog moves this with it, clamped so a span narrower than one cell
     * still hides something.
     *
     * <p>Not through {@link FogData#HIDE_FRACTION}: that is a threshold on how opaque the
     * <em>picture</em> is, and this picture is a grid of overlapping tiles - every point is covered
     * about three times over and the composite saturates half a column in - so measuring the cloud
     * answers "unreadable almost immediately" for every level, which is true of the cloud and
     * useless as a rule for what a level declares. (Tried that; the line came out past the second
     * fogged column on 4-1.) This reads the level's declaration instead.
     */
    public static float hidingColumn(FogData fog) {
        if (fog == null || fog.maxAlpha() <= 0F) {
            return Float.MAX_VALUE;
        }
        float fraction = Math.min(1F, -TILE_OFFSET_CELLS_X / (fog.endColumn() - fog.startColumn()));
        return fog.startColumn() + (fog.endColumn() - fog.startColumn()) * fraction;
    }

    /** How dark a cell has to be before what is standing there is not drawn. */
    public static final float HIDE_FRACTION = FogData.HIDE_FRACTION;

    /**
     * The bottom-left corner of the cloud tile that cell {@code (cellX, cellY)} draws, in cells.
     *
     * <p>Public because it is the one piece of this class that is arithmetic rather than drawing,
     * and because the tile's own size ({@link #tileSize()}) is a fact about the sprite sheet rather
     * than about the level: this is what a test can check against the art without a GL context.
     */
    public static float[] tileOrigin(float cellX, float cellY, float scale) {
        return new float[]{cellX + TILE_OFFSET_PX_X / REFERENCE_CELL_PX_X * scale,
                cellY + TILE_OFFSET_PX_Y / REFERENCE_CELL_PX_Y * scale};
    }

    /** The tile's size in cells at {@code scale}; see {@link #tileOrigin}. */
    public static float[] tileSize(float scale) {
        return new float[]{TILE_PX_X / REFERENCE_CELL_PX_X * scale,
                TILE_PX_Y / REFERENCE_CELL_PX_Y * scale};
    }

    /** The darkening itself; one per level instance, holding the span it was made from. */
    private static final class Fog implements WorldOverlay {
        private final FogData data;
        /** Cells a lamp reaches into, which are the only ones drawn subdivided. */
        private final boolean[][] lampCells = new boolean[CELL_LOOK_SIZE][CELL_LOOK_SIZE];
        /** The lamps this frame draws with, and the ones the subdivision was computed for. */
        private java.util.List<FogMechanic.Reveal> lamps = java.util.List.of();
        private java.util.List<FogMechanic.Reveal> lampSignature;
        /** Seconds since the client started, latched once at the top of the frame's draw. */
        private float seconds;
        /** The board's scale for this frame; see {@link PvzceCamera#boardScale()}. */
        private float scale = 1F;

        private Fog(FogData data) {
            this.data = data;
        }

        @Override
        public void render(PvzceClient client, PvzceCamera camera) {
            int width = client.level().width();
            this.seconds = client.renderTimeSeconds();
            this.scale = camera.boardScale();
            this.lamps = revealsOf(client.level());
            refreshLamps();

            // The sheet is the layout: how many frames it holds and how tall each one is are read
            // back from the PNG, so reslicing the art needs no change here.
            int frameWidth = Math.max(1, client.textureWidth(CLOUD) / FOG_FRAMES);
            int frameHeight = client.textureHeight(CLOUD);

            float[] tile = tileSize(scale);
            float right = width + RIGHT_MARGIN_CELLS;
            int firstColumn = Math.max(0, (int) Math.floor(data.startColumn() - tile[0]));
            int lastColumn = (int) Math.ceil(right);
            int rows = client.level().height() + 1;

            for (int cellX = firstColumn; cellX <= lastColumn; cellX++) {
                for (int cellY = 0; cellY < rows; cellY++) {
                    // The cell's own middle decides whether it is fogged at all. A cell the fog
                    // does not reach draws nothing, which is also where the hiding test changes
                    // its answer - so a tile is never drawn past the fog and never missing inside
                    // it.
                    if (FogMechanic.alphaAt(data, lamps, cellX + 0.5F, cellY + 0.5F) <= 0F) {
                        continue;
                    }
                    drawCell(client, cellX, cellY, frameWidth, frameHeight);
                }
            }
        }

        /**
         * Marks the cells a lamp reaches into, so the draw can subdivide exactly those.
         *
         * <p>Asked of the lamp rather than of every cell, and only recomputed when the lamp list
         * itself changes - the list is rebuilt by the mechanic sync and by nothing else.
         */
        private void refreshLamps() {
            if (lamps == lampSignature) {
                return;
            }
            lampSignature = lamps;
            for (boolean[] column : lampCells) {
                java.util.Arrays.fill(column, false);
            }
            for (FogMechanic.Reveal lamp : lamps) {
                float[] tile = tileSize(scale);
                int minX = (int) Math.floor(lamp.x() - lamp.radius() - tile[0]);
                int maxX = (int) Math.ceil(lamp.x() + lamp.radius());
                int minY = (int) Math.floor(lamp.y() - lamp.radius() - tile[1]);
                int maxY = (int) Math.ceil(lamp.y() + lamp.radius() + tile[1]);
                for (int cellX = Math.max(0, minX); cellX <= maxX && cellX < CELL_LOOK_SIZE; cellX++) {
                    for (int cellY = Math.max(0, minY);
                         cellY <= maxY && cellY < CELL_LOOK_SIZE; cellY++) {
                        lampCells[cellX][cellY] = true;
                    }
                }
            }
        }

        /**
         * Which of the sheet's frames a cell draws.
         *
         * <p>The sheet is one continuous band cut into eight slices, so frame {@code n} holds the
         * band's pixels {@code 210n .. 210n+210} and which frame a cell wants follows from where
         * the cell is: its x in board pixels, divided by a frame's width. The original picks a
         * frame at random instead ({@code mGridCelLook = Rand(20)}), which works for it because
         * its eight frames are interchangeable shapes laid out to tile; these are not, and a
         * random pick would show each cell the wrong slice and seam the band.
         */
        private static int frameFor(int cellX) {
            return Math.floorMod((int) (cellX * REFERENCE_CELL_PX_X) / FOG_FRAME_PIXELS, FOG_FRAMES);
        }

        /** A cell index inside the fog grid, for the one table that is a grid: {@link #lampCells}. */
        private static int lookX(int cellX) {
            return Math.min(Math.max(cellX, 0), CELL_LOOK_SIZE - 1);
        }

        /** See {@link #lookX}. */
        private static int lookY(int cellY) {
            return Math.min(Math.max(cellY, 0), CELL_LOOK_SIZE - 1);
        }

        /** One cell's cloud: one quad, or {@link #LAMP_SUBDIVISION}^2 of them under a lamp. */
        private void drawCell(PvzceClient client, int cellX, int cellY,
                              int frameWidth, int frameHeight) {
            float[] origin = tileOrigin(cellX, cellY, scale);
            float[] tile = tileSize(scale);
            float u0 = frameFor(cellX) * (float) frameWidth;
            float u1 = u0 + frameWidth;
            float v0 = 0F;
            float v1 = frameHeight;

            // The per-cell drift; see MOTION_CENTRE. The cell's own phase is what keeps neighbouring
            // clouds from breathing in step.
            float drift = (float) (MOTION_CENTRE
                    + MOTION_SWING * Math.sin(seconds * MOTION_RATE_Y + MOTION_PHASE_Y * cellY)
                    + MOTION_SWING * Math.sin(seconds * MOTION_RATE_X + MOTION_PHASE_X * cellX));
            float brightness = Math.max(BRIGHTNESS_FLOOR, Math.min(1F, drift));
            // The opacity is the level's own ceiling and does not drift with the colour: the hiding
            // test reads `FogMechanic.alphaAt`, so an alpha that wandered with the sine would put
            // the cloud the player sees and the cloud the zombie hides behind out of step. What
            // the drift moves is how bright the cloud is, which is what the original's own two
            // terms do to a texture that is white to begin with.
            float cellAlpha = data.maxAlpha();

            if (!lampCells[lookX(cellX)][lookY(cellY)]) {
                drawQuad(client, origin[0], origin[1], tile[0], tile[1],
                        u0, v0, u1, v1, brightness,
                        cellAlpha, cellAlpha, cellAlpha, cellAlpha);
                return;
            }
            float stepX = tile[0] / LAMP_SUBDIVISION;
            float stepY = tile[1] / LAMP_SUBDIVISION;
            for (int ix = 0; ix < LAMP_SUBDIVISION; ix++) {
                for (int iy = 0; iy < LAMP_SUBDIVISION; iy++) {
                    float x = origin[0] + stepX * ix;
                    float y = origin[1] + stepY * iy;
                    // The lamp's circle is sampled at the four corners of each small quad, so a lamp
                    // of radius 2.5 gets about twenty alphas across its own edge.
                    drawQuad(client, x, y, stepX, stepY,
                            u0 + (u1 - u0) * ix / LAMP_SUBDIVISION,
                            v0 + (v1 - v0) * iy / LAMP_SUBDIVISION,
                            u0 + (u1 - u0) * (ix + 1) / LAMP_SUBDIVISION,
                            v0 + (v1 - v0) * (iy + 1) / LAMP_SUBDIVISION,
                            brightness,
                            FogMechanic.alphaAt(data, lamps, x, y),
                            FogMechanic.alphaAt(data, lamps, x + stepX, y),
                            FogMechanic.alphaAt(data, lamps, x + stepX, y + stepY),
                            FogMechanic.alphaAt(data, lamps, x, y + stepY));
                }
            }
        }

        /** One textured quad of cloud, at one brightness and four corner alphas. */
        private void drawQuad(PvzceClient client, float x, float y, float w, float h,
                              float u0, float v0, float u1, float v1,
                              float brightness, float a0, float a1, float a2, float a3) {
            if (a0 <= 0F && a1 <= 0F && a2 <= 0F && a3 <= 0F) {
                return;
            }
            // World cells, like every other draw on this board: the client's projection puts them
            // on the screen, and the cell-to-screen scale is the camera's business, not this
            // class's. (It used to multiply by the cell size itself and draw at board-local
            // pixels, which put the whole band a few hundred pixels off the screen at 1920x1080 -
            // the board does not start at the window's corner.)
            client.drawTextureShaded(CLOUD, u0, v0, u1, v1, x, y, w, h, Z,
                    brightness, brightness, brightness, a0, a1, a2, a3);
        }
    }
}
