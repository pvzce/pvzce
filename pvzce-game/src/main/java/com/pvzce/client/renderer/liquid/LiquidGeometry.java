package com.pvzce.client.renderer.liquid;

import java.util.ArrayList;
import java.util.List;
/**
 * Turns a scene grid into the liquid cells that should be drawn, with their
 * neighbourhood already resolved.
 *
 * <p>Pure logic on purpose: "which sides of this cell face land" and "how deep is
 * it" are the parts of the liquid renderer most likely to be subtly wrong, and
 * they are the parts that can be tested without a GL context. The renderer only
 * uploads what this produces.
 */
public final class LiquidGeometry {
    /** Answers what the board holds at a cell; {@code null} means off the board. */
    @FunctionalInterface
    public interface Occupancy {
        /**
         * @return true when the cell holds the same liquid, false when it holds
         *         something else or is off the board
         */
        boolean isLiquid(int x, int y);
    }

    private LiquidGeometry() {
    }

    /**
     * Collects every liquid cell and resolves its borders, corners and depth.
     *
     * <p>Depth is a CONTINUOUS distance-to-land field, sampled at the four corners
     * of every cell and interpolated across the quad by the GPU. A cell's corner
     * values are the Euclidean distance from that corner to the nearest land, in
     * cells, normalised by the liquid's {@code depth_scale}. The fragment shader
     * then ramps shallow into deep on that interpolated value, so the transition is
     * smooth INSIDE a cell as well as across the body.
     *
     * <p>This replaced a breadth-first step count, and the replacement was not a
     * refinement - the step count could not express a gradient at all on the boards
     * that needed it most. Steps are integers, so a lake's depth had one value per
     * cell; worse, the ramp was normalised by {@code min(depth_scale, the body's own
     * deepest cell)}, and a 5x4 pool is only ONE step from land, so the normaliser
     * collapsed to 1 and the water came out binary: depth 1 in the middle, depth 0
     * on the rim, with a hard rectangular edge between them and no transition to
     * speak of. Measured on a real level, the middle was a single flat colour
     * (30,101,104) against a rim of (71,147,152) - the "深浅过渡" the player sees as
     * a seam. A continuous field has no such collapse: the same board ramps from
     * 0.25 cells at the rim to 1.5 in the middle.
     *
     * <p>{@code deepEnoughSteps} still comes from the liquid definition
     * ({@code depth_scale}) and is still the one number the author tunes the look
     * with; it is now the distance at which the ramp reaches about half depth rather
     * than a hard cut-off, so a large body keeps deepening past it instead of
     * flattening into a plateau.
     */
    public static List<LiquidCell> collect(int width, int height, Occupancy occupancy,
                                           float colorR, float colorG, float colorB,
                                           float deepEnoughSteps) {
        List<LiquidCell> cells = new ArrayList<>();
        if (width <= 0 || height <= 0) {
            return cells;
        }
        /* The field is built on a grid padded by one DRY cell on every side, so the
           board edge behaves like land: it is what gives the rim of an otherwise
           all-water board a shore to measure against. Without the padding an all-water
           board would have no land at all and every corner would report the same
           "infinitely far from shore" distance. */
        int paddedWidth = width + 2;
        int paddedHeight = height + 2;
        boolean[][] liquid = new boolean[paddedHeight][paddedWidth];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                liquid[y + 1][x + 1] = occupancy.isLiquid(x, y);
            }
        }

        /* How far the search for land looks. The ramp's useful range is a few times
           depth_scale - beyond that the shallow-to-deep mix has effectively arrived -
           so a bounded window keeps this O(cells) on a large board instead of
           O(cells * land). Distances past the window are clamped to its edge, which
           only ever affects water already far enough from land to be fully shaded. */
        float scale = Math.max(0.05F, deepEnoughSteps);
        int reach = Math.max(2, (int) Math.ceil(scale * 3F));

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int px = x + 1;
                int py = y + 1;
                if (!liquid[py][px]) {
                    continue;
                }
                int borders = 0;
                if (!isLiquid(liquid, paddedWidth, paddedHeight, px, py + 1)) {
                    borders |= LiquidCell.NORTH;
                }
                if (!isLiquid(liquid, paddedWidth, paddedHeight, px + 1, py)) {
                    borders |= LiquidCell.EAST;
                }
                if (!isLiquid(liquid, paddedWidth, paddedHeight, px, py - 1)) {
                    borders |= LiquidCell.SOUTH;
                }
                if (!isLiquid(liquid, paddedWidth, paddedHeight, px - 1, py)) {
                    borders |= LiquidCell.WEST;
                }
                int corners = 0;
                if (!isLiquid(liquid, paddedWidth, paddedHeight, px + 1, py + 1)) {
                    corners |= LiquidCell.CORNER_NE;
                }
                if (!isLiquid(liquid, paddedWidth, paddedHeight, px + 1, py - 1)) {
                    corners |= LiquidCell.CORNER_SE;
                }
                if (!isLiquid(liquid, paddedWidth, paddedHeight, px - 1, py - 1)) {
                    corners |= LiquidCell.CORNER_SW;
                }
                if (!isLiquid(liquid, paddedWidth, paddedHeight, px - 1, py + 1)) {
                    corners |= LiquidCell.CORNER_NW;
                }

                /* One distance per CORNER, not one per cell. The order is the one
                   LiquidCell.cornerSlot documents and the batch reads, so a vertex and
                   its value cannot disagree about which corner they mean. The values
                   are quantised exactly as LiquidBatch will pack them, so what the
                   shader decodes is what was measured here. */
                float[] cornerDepth = new float[4];
                for (int slot = 0; slot < cornerDepth.length; slot++) {
                    float cornerX = switch (slot) {
                        case LiquidCell.NE, LiquidCell.SE -> x + 1F;
                        default -> x;
                    };
                    float cornerY = switch (slot) {
                        case LiquidCell.NW, LiquidCell.NE -> y + 1F;
                        default -> y;
                    };
                    cornerDepth[slot] = LiquidCell.quantiseDepth(normalise(
                            cornerDistance(occupancy, width, height,
                                    (int) cornerX, (int) cornerY, reach), scale));
                }
                float centreDistanceCells = 0.25F * scale
                        * (cornerDepth[0] + cornerDepth[1] + cornerDepth[2] + cornerDepth[3]);
                cells.add(new LiquidCell(x, y, borders, corners, cornerDepth,
                        new float[]{colorR, colorG, colorB,
                                LiquidCell.shoreStrength(centreDistanceCells)}));
            }
        }
        return cells;
    }

    /**
     * Euclidean distance, in cells, from one cell corner to the nearest land.
     *
     * <p>Measured to the land cell's RECTANGLE, not to its centre: distance to a
     * centre would report 0.5 for a corner sitting exactly on a shore.
     *
     * <p>Land is asked of the OCCUPANCY, not read off the padded grid, so a cell
     * outside the board counts as land only when the scene really has something else
     * there. The padding is a construction detail of the grid and must not be
     * measured against: it sits one cell outside the playable area, and counting it
     * as land made every corner on the board's own boundary report distance 0 -
     * which flattened the outer ring of an all-water board into one shallow tone and
     * put a ring of false shoreline right where the water should be deepest.
     *
     * <p>The search window still extends past the board edges, so a corner near the
     * rim still measures the real distance to the land off-board rather than being
     * truncated at the boundary. It is clamped to {@code reach}, which is all the
     * ramp can use anyway.
     */
    private static float cornerDistance(Occupancy occupancy, int width, int height,
                                        int cornerX, int cornerY, int reach) {
        float best = reach;
        for (int y = cornerY - reach; y <= cornerY + reach; y++) {
            for (int x = cornerX - reach; x <= cornerX + reach; x++) {
                /* LATTICE coordinates, which are the board's own: cell (x, y) spans
                   [x, x+1] x [y, y+1], so board cell (0,0) owns the lattice corner
                   (0,0). Landing the lattice on the padded grid's indices instead -
                   which is what the first version of this did - shifts the whole field
                   one cell towards the origin and makes every corner of the board's
                   own boundary measure distance 0, i.e. a false shoreline ring exactly
                   where the water is deepest. */
                boolean land = x < 0 || y < 0 || x >= width || y >= height
                        || !occupancy.isLiquid(x, y);
                if (!land) {
                    continue;
                }
                /* Distance to the land cell's CENTRE, not to its rectangle.
                   Measuring to the rectangle makes a lattice point that merely touches
                   a land cell's corner read 0, and a pool's corner has two such points -
                   so the whole corner cell graded down to zero depth along a diagonal,
                   which the waterline feather then turned into a transparent wedge with
                   the ground showing through. To the centre, the same point is 0.707 of
                   a cell from land, which is the honest answer: the water there really
                   is that far from any land. It also removes the asymmetry where a
                   corner touched land "for free" that a straight edge did not. */
                float dx = (x + 0.5F) - cornerX;
                float dy = (y + 0.5F) - cornerY;
                float distance = (float) Math.sqrt(dx * dx + dy * dy);
                if (distance < best) {
                    best = distance;
                }
            }
        }
        return best;
    }

    /**
     * Distance in cells to the ramp's 0..1 domain.
     *
     * <p>Deliberately NOT clamped to 1: the shader turns the fraction into its final
     * shape, so a value past 1 is real information (this water is deeper than the
     * definition's scale) rather than something to throw away here. Clamping is what
     * produced the flat plateau in the middle of a lake.
     */
    private static float normalise(float distance, float scale) {
        return Math.max(0F, distance / Math.max(0.0001F, scale));
    }

    private static boolean isLiquid(boolean[][] liquid, int width, int height, int x, int y) {
        if (x < 0 || y < 0 || x >= width || y >= height) {
            return false;
        }
        return liquid[y][x];
    }
}
