package com.pvzce.client.renderer.liquid;

import com.pvzce.common.util.MathUtil;

/**
 * One liquid cell's geometry, with everything the shader cannot work out for
 * itself already encoded.
 *
 * <p>A liquid cell cannot be drawn as a plain textured quad. The shader has to
 * know where the cell is (so the base texture tiles continuously across a whole
 * body of water instead of restarting in every cell), which of its four sides
 * face dry land (so the shoreline foam and the softened edge only appear on real
 * shores), and how deep it is (so the middle of a lake is deep and its rim is
 * shallow).
 *
 * <p>All of that is derived from the scene grid ONCE per cell here, not per
 * pixel in the shader: the shader has no access to neighbouring cells, and giving
 * it a neighbourhood texture would mean a second render target for a handful of
 * bits.
 *
 * @param cellX       cell column; the quad covers {@code [cellX, cellX + 1]}
 * @param cellY       cell row; the quad covers {@code [cellY, cellY + 1]}
 * @param borders     bit mask of dry-land sides, see {@link #NORTH} etc.
 * @param corners     bit mask of diagonal dry-land corners, see {@link #CORNER_NE}
 * @param cornerDepth the four corner depth factors, in {@link #CORNER_NE} bit order
 *                    (NW, NE, SE, SW - matching the quad's uv corners), where 0 is
 *                    the shoreline and 1 is one {@code depth_scale} from land.
 *                    Per CORNER rather than per cell because the shader interpolates
 *                    them across the quad: a single value per cell made the depth a
 *                    flat plate with a hard step at every cell boundary, which is the
 *                    "深浅过渡不平滑" the surface showed.
 * @param color       rgb = the water colour already resolved from the definition,
 *                    a = the cell's shore strength (see {@link #shoreStrength})
 */
record LiquidCell(float cellX, float cellY, int borders, int corners,
                  float[] cornerDepth, float[] color) {
    /** Neighbour offsets, in the order the border bits are packed. */
    static final int NORTH = 1;
    static final int EAST = 2;
    static final int SOUTH = 4;
    static final int WEST = 8;

    /** Diagonal bits, in the order the corner bits are packed. */
    static final int CORNER_NE = 1;
    static final int CORNER_SE = 2;
    static final int CORNER_SW = 4;
    static final int CORNER_NW = 8;

    /** Corners of a cell, indexed the way {@link #cornerDepth} is ordered. */
    static final int NW = 0;
    static final int NE = 1;
    static final int SE = 2;
    static final int SW = 3;

    /**
     * The slot in {@link #cornerDepth} that a given local position inside the cell
     * belongs to.
     *
     * <p>One function, used by the geometry pass to fill the array and by the batch to
     * pick a vertex's value, because having each derive the mapping itself is exactly
     * how the depth ends up on the wrong corner. It did: the geometry passed the four
     * distances in {@link #NW}/{@link #NE}/{@link #SE}/{@link #SW} order while the batch
     * read them as {@link #SW}/{@link #SE}/{@link #NE}/{@link #NW}, so every cell's
     * gradient was rotated - the deep side of a lake cell pointed at the shore. The
     * field still looked plausible in a dump (all four values were present and
     * correct), which is why only a test that knows WHICH corner is deep can catch it.
     *
     * @param localX 0 at the cell's west edge, 1 at its east edge
     * @param localY 0 at the cell's south edge, 1 at its north edge
     */
    static int cornerSlot(float localX, float localY) {
        boolean east = localX >= 0.5F;
        boolean north = localY >= 0.5F;
        if (north) {
            return east ? NE : NW;
        }
        return east ? SE : SW;
    }

    /**
     * Levels one corner depth is quantised to before it is packed into the vertex.
     *
     * <p>Eight bits, and the geometry pass rounds each corner through this exact
     * function, so the shader decodes what was measured rather than a nearby value.
     * The quantisation is applied to the depth FACTOR rather than to a raw distance,
     * which keeps it uniform wherever the ramp actually is instead of spending most
     * of its levels on water that is already fully deep.
     */
    static final int DEPTH_LEVELS = 256;

    /**
     * Largest depth factor a corner can carry.
     *
     * <p>The shader's ramp has arrived by this point - it is an asymptotic curve, not
     * a clamp - so anything past 4 would be invisible anyway, and spending levels on
     * it would only coarsen the part of the ramp that IS visible.
     */
    static final float MAX_DEPTH_FACTOR = 4F;

    /** Rounds a depth factor to the value that will actually reach the shader. */
    static float quantiseDepth(float depthFactor) {
        float clamped = MathUtil.clamp01(depthFactor / MAX_DEPTH_FACTOR) * MAX_DEPTH_FACTOR;
        int level = Math.round(clamped / MAX_DEPTH_FACTOR * (DEPTH_LEVELS - 1));
        return level / (float) (DEPTH_LEVELS - 1) * MAX_DEPTH_FACTOR;
    }

    /**
     * Per-cell readability boost for the shoreline foam.
     *
     * <p>Without it a one-cell-wide inlet is all rim and reads as a solid white
     * blob; with it a wide shore keeps a crisp line and an enclosed puddle does not
     * turn into foam. Takes the cell's distance from land in CELLS rather than its
     * normalised depth, because the normalised value saturates and would hand every
     * deep cell the same strength.
     */
    static float shoreStrength(float centreDistanceCells) {
        return 0.75F + 0.25F * MathUtil.clamp01(centreDistanceCells / 3F);
    }
}
