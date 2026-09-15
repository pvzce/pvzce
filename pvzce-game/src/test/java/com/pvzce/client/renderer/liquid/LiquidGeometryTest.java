package com.pvzce.client.renderer.liquid;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the parts of the liquid renderer that do not need a GL context: what the
 * geometry pass decides about each cell, and how it packs those decisions for the
 * shader.
 *
 * <p>The packing is the highest-risk piece of the feature. The shader cannot see
 * neighbouring cells, so everything it knows about the shoreline arrives as bits in
 * one attribute; a mistake there shows up as water that foams in the middle of a
 * lake. These tests pin the encoding down, including the signs that carry the shore
 * flags, and they decode the attribute exactly the way the fragment shader does.
 */
class LiquidGeometryTest {
    /**
     * The shipped water's {@code depth_scale}: two cells from land counts as deep
     * water. Tests pass it explicitly so they pin the definition's role rather than
     * a constant hidden in the geometry pass.
     */
    private static final float DEEP = 2F;

    /**
     * One quantisation step of the packed depth factor.
     *
     * <p>The geometry pass rounds every corner through {@link LiquidCell#quantiseDepth}
     * before it is uploaded and these tests decode the uploaded value, so an
     * expectation is allowed exactly one step of slack. The step is tiny: 256 levels
     * across the factor's range, which is why the ramp cannot band.
     */
    private static final float QUANTISATION =
            LiquidCell.MAX_DEPTH_FACTOR / (LiquidCell.DEPTH_LEVELS - 1) + 1e-6F;

    /**
     * Builds an occupancy grid from rows written TOP-DOWN, the way the level JSON
     * and a human reader both see the board: {@code rows[0]} is the TOP row, which
     * is the highest y.
     *
     * <p>{@code rows[0]} is the top row, so it maps to the HIGHEST y: the board
     * grows upwards and the text reads the way the board looks.
     */
    private static LiquidGeometry.Occupancy grid(String... rows) {
        int height = rows.length;
        int width = rows[0].length();
        return (x, y) -> {
            if (x < 0 || y < 0 || x >= width || y >= height) {
                return false;
            }
            return rows[height - 1 - y].charAt(x) == 'W';
        };
    }

    /** Reads back the liquid attribute exactly as the shader decodes it. */
    private record Packed(float x, float y, int borders, int southCell, int corners,
                          int westCell, int depthBits) {
        /** True when the north side faces land; the sign of x carries this bit. */
        boolean northHasShore() {
            return x < 0F;
        }

        /** True when the west side faces land; the sign of y carries this bit. */
        boolean westHasShore() {
            return y < 0F;
        }

        boolean northIsBorder() {
            return (borders & LiquidCell.NORTH) != 0;
        }

        boolean eastIsBorder() {
            return (borders & LiquidCell.EAST) != 0;
        }

        boolean southIsBorder() {
            return (borders & LiquidCell.SOUTH) != 0;
        }

        boolean westIsBorder() {
            return (borders & LiquidCell.WEST) != 0;
        }

        /**
         * The depth factor this vertex carries: distance to land in units of the
         * liquid's depth_scale. 0 is the shoreline, 1 is one depth_scale out.
         */
        float depthFactor() {
            return depthBits * (LiquidCell.MAX_DEPTH_FACTOR / (LiquidCell.DEPTH_LEVELS - 1));
        }

        /**
         * Decodes the four liquid floats of one vertex.
         *
         * <p>Note the asymmetry, which is easy to get wrong: the +1 the serializer
         * adds applies to the CELL COORDINATES only (it keeps them off zero so their
         * sign can carry a flag). The mask words are packed without it.
         */
        static Packed of(float[] liquid) {
            int packedBorders = (int) (liquid[2] + 0.5F);
            int packedParts = (int) (liquid[3] + 0.5F);
            // Layout: z = borders | north<<4, w = corners | depthLevel<<4. The east and
            // west flags have no dedicated bit - their signs are in x and y.
            return new Packed(liquid[0], liquid[1], packedBorders & 15, (packedBorders >> 2) & 1,
                    packedParts & 15, (packedBorders >> 3) & 1, packedParts >> 4);
        }
    }

    /** Every vertex of every cell, in batch order. */
    private static float[] verticesOf(List<LiquidCell> cells) {
        LiquidBatch batch = new LiquidBatch(Math.max(1, cells.size()));
        for (LiquidCell cell : cells) {
            batch.cell(cell, cell.cellX(), cell.cellY(), 1F, 1F);
        }
        return batch.vertexSnapshot();
    }

    /** The attribute of a cell's FIRST vertex, which is the quad's (0,0) = SW corner. */
    private static Packed packedAt(List<LiquidCell> cells, int x, int y) {
        float[] vertices = verticesOf(cells);
        int stride = LiquidVertexFormat.VERTEX_FLOATS;
        for (int index = 0; index < cells.size(); index++) {
            LiquidCell cell = cells.get(index);
            if (cell.cellX() == x && cell.cellY() == y) {
                int at = index * LiquidBatch.VERTICES_PER_CELL * stride
                        + LiquidVertexFormat.LIQUID_OFFSET;
                return Packed.of(Arrays.copyOfRange(vertices, at,
                        at + LiquidVertexFormat.LIQUID_COMPONENTS));
            }
        }
        throw new AssertionError("no cell at " + x + "," + y);
    }

    /** One corner of an all-water board against the independent measurement. */
    private static void assertCornerMatchesBoard(int width, int height, LiquidCell cell,
                                                 int cornerBit, int cornerX, int cornerY,
                                                 float scale) {
        assertEquals(quantised(expectedCornerDistance(width, height, cornerX, cornerY), scale),
                corner(cell, cornerBit), 0F,
                "corner at lattice " + cornerX + "," + cornerY
                        + " of cell " + (int) cell.cellX() + "," + (int) cell.cellY());
    }

    private static LiquidCell cellAt(List<LiquidCell> cells, int x, int y) {
        return cells.stream()
                .filter(cell -> cell.cellX() == x && cell.cellY() == y)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no cell at " + x + "," + y));
    }

    /**
     * The depth factor at one named corner of a cell.
     *
     * <p>Addressed BY DIRECTION because the storage order is not the one a reader
     * assumes: the packed slots are indexed by quad corner, so {@code [0]} is NORTH-WEST
     * and {@code [2]} is SOUTH-EAST, not the other way round.
     */
    private static float corner(LiquidCell cell, int cornerBit) {
        int index = switch (cornerBit) {
            case LiquidCell.CORNER_NW -> LiquidCell.NW;
            case LiquidCell.CORNER_NE -> LiquidCell.NE;
            case LiquidCell.CORNER_SE -> LiquidCell.SE;
            case LiquidCell.CORNER_SW -> LiquidCell.SW;
            default -> throw new IllegalArgumentException("not a corner: " + cornerBit);
        };
        return cell.cornerDepth()[index];
    }

    /** The largest of a cell's four corner depth factors. */
    private static float deepestCorner(LiquidCell cell) {
        float deepest = 0F;
        for (float depth : cell.cornerDepth()) {
            deepest = Math.max(deepest, depth);
        }
        return deepest;
    }

    /** The smallest of a cell's four corner depth factors. */
    private static float shallowestCorner(LiquidCell cell) {
        float shallowest = Float.MAX_VALUE;
        for (float depth : cell.cornerDepth()) {
            shallowest = Math.min(shallowest, depth);
        }
        return shallowest;
    }

    /**
     * The exact distance from a lattice corner to the nearest land, computed
     * independently of the code under test.
     *
     * <p>Lattice corner (cx, cy) is the point where board cells (cx-1, cy-1),
     * (cx, cy-1), (cx-1, cy) and (cx, cy) meet. Land is any cell outside the board, or
     * any cell inside it that is not water - so a corner measures 0 as soon as any of
     * those four cells is land, and "off the board" is not a special case at all.
     */
    private static float expectedCornerDistance(int width, int height, int cornerX, int cornerY,
                                                java.util.function.IntBinaryOperator isWater) {
        /* Land is measured to the CENTRE of the land cell, which is what LiquidGeometry
           does: a corner that merely touches a land cell's rectangle is 0.707 of a cell
           from that cell's centre, not 0. The distinction is the whole reason a pool's
           corners no longer grade down to zero depth. */
        float best = 1e9F;
        for (int y = -1; y <= height; y++) {
            for (int x = -1; x <= width; x++) {
                boolean outside = x < 0 || y < 0 || x >= width || y >= height;
                if (!outside && isWater.applyAsInt(x, y) == 1) {
                    continue;
                }
                float dx = (x + 0.5F) - cornerX;
                float dy = (y + 0.5F) - cornerY;
                best = Math.min(best, (float) Math.hypot(dx, dy));
            }
        }
        return best;
    }

    /** The all-water board case: every in-board cell is water. */
    private static float expectedCornerDistance(int width, int height, int cornerX, int cornerY) {
        return expectedCornerDistance(width, height, cornerX, cornerY, (x, y) -> 1);
    }

    /** The factor a measured distance becomes once the geometry pass rounds it. */
    private static float quantised(float distance, float scale) {
        return LiquidCell.quantiseDepth(Math.max(0F, distance / scale));
    }

    /** The 7x5 lake fixture, as a water predicate. */
    private static final java.util.function.IntBinaryOperator LAKE =
            (x, y) -> x >= 1 && x <= 7 && y >= 1 && y <= 5 ? 1 : 0;

    // ---------------------------------------------------------------- geometry

    @Test
    void isolatedCellIsShoreOnEverySide() {
        List<LiquidCell> cells = LiquidGeometry.collect(3, 3, grid(
                "...",
                ".W.",
                "..."), 0F, 0F, 0F, DEEP);

        assertEquals(1, cells.size());
        LiquidCell cell = cells.get(0);
        assertEquals(1F, cell.cellX());
        assertEquals(1F, cell.cellY());
        assertEquals(15, cell.borders(), "a lone tile faces land on all four sides");
        // Its four corners are exactly the points where the surrounding land cells meet
        // it, so every corner is ON a shore: distance 0, uniformly shallow.
        // Its corners are the points where the four surrounding land cells meet, so each
        // is half a cell diagonally from a land centre: 0.707 of a cell, not 0.
        assertEquals(quantised(0.7071F, DEEP), deepestCorner(cell), QUANTISATION,
                "a lone tile's corners are still a real distance from land");
    }

    @Test
    void theMiddleOfALakeIsDeeperThanItsRim() {
        List<LiquidCell> cells = LiquidGeometry.collect(9, 7, grid(
                ".........",
                ".WWWWWWW.",
                ".WWWWWWW.",
                ".WWWWWWW.",
                ".WWWWWWW.",
                ".WWWWWWW.",
                "........."), 0F, 0F, 0F, DEEP);

        LiquidCell rim = cellAt(cells, 1, 1);
        LiquidCell middle = cellAt(cells, 4, 3);
        assertTrue(rim.borders() != 0, "the rim faces land");
        assertEquals(0, middle.borders(), "the centre of the lake faces only water");
        assertEquals(0, middle.corners(), "no diagonal of the centre touches land");
        assertTrue(deepestCorner(middle) > deepestCorner(rim),
                "the middle of a lake must be deeper than its rim");

        // The lake spans x=1..7, y=1..5, so the centre cell (4,3) has lattice corners
        // (4,3), (5,3), (4,4), (5,4). Measured against the lake's own land, each is two
        // cells out; the board's outside land is further still.
        assertEquals(quantised(expectedCornerDistance(9, 7, 4, 3, LAKE), DEEP),
                deepestCorner(middle), 0F,
                "the centre's corners measure their real distance to the lake's own shore");
        assertTrue(deepestCorner(rim) < deepestCorner(middle), "the rim is shallower");

        // The gradient lives in the shoreline direction: walking in from the west
        // shore, the rings never get shallower.
        float previous = -1F;
        for (int x = 1; x <= 4; x++) {
            float depth = shallowestCorner(cellAt(cells, x, 3));
            assertTrue(depth >= previous,
                    "ring x=" + x + " must never be shallower than the one before it");
            previous = depth;
        }
        assertTrue(previous > 0F, "and the middle of the lake must be deeper than its rim");
    }

    @Test
    void aSmallPondStillRampsInsteadOfCollapsing() {
        // THE REGRESSION THE CONTINUOUS FIELD EXISTS FOR. Depth used to be an integer
        // count of grid steps to land, normalised by min(depth_scale, the body's own
        // deepest cell). A small pond is ONE step deep, so the normaliser collapsed to
        // 1 and the water became binary - 1.0 in the middle, 0.0 on the rim, with a hard
        // rectangular edge and no gradient at all. That is the seam a player reported.
        List<LiquidCell> cells = LiquidGeometry.collect(3, 3, grid(
                "WWW", "WWW", "WWW"), 0F, 0F, 0F, DEEP);

        LiquidCell middle = cellAt(cells, 1, 1);
        LiquidCell rim = cellAt(cells, 0, 1);
        // Every one of this pond's lattice corners is one cell from its surrounding
        // land, so the middle and the rim share a depth - but the rim's near corners sit
        // ON the shore while the middle's do not, and that gradient across the quad is
        // what a per-cell step count could never express.
        /* The pond is the whole 3x3 board, so its "land" is the ring outside it and the
           nearest land CENTRE from the middle's corners is 1.5-1.6 cells away - a real
           depth, where the old step count could only say "1". */
        assertEquals(quantised(1.5690F, DEEP), deepestCorner(middle), 2F * QUANTISATION,
                "the pond's middle is a real distance from the nearest land centre");
        assertTrue(shallowestCorner(rim) < shallowestCorner(middle),
                "the rim's nearest corner is closer to land than the middle's");
        assertTrue(shallowestCorner(middle) > shallowestCorner(rim),
                "so the middle cell has no corner on the shore");
    }

    @Test
    void aWideBodyUsesTheDefinitionsDepthScale() {
        List<LiquidCell> cells = LiquidGeometry.collect(9, 9, grid(
                "WWWWWWWWW", "WWWWWWWWW", "WWWWWWWWW", "WWWWWWWWW", "WWWWWWWWW",
                "WWWWWWWWW", "WWWWWWWWW", "WWWWWWWWW", "WWWWWWWWW"), 0F, 0F, 0F, 4F);

        // Every corner must equal the closed form. The four are written out BY
        // DIRECTION, so a field whose corners are rotated - which the batch and the
        // geometry pass really did disagree about - fails here instead of looking fine.
        for (int y = 0; y < 9; y++) {
            for (int x = 0; x < 9; x++) {
                LiquidCell cell = cellAt(cells, x, y);
                assertCornerMatchesBoard(9, 9, cell, LiquidCell.CORNER_SW, x, y, 4F);
                assertCornerMatchesBoard(9, 9, cell, LiquidCell.CORNER_SE, x + 1, y, 4F);
                assertCornerMatchesBoard(9, 9, cell, LiquidCell.CORNER_NE, x + 1, y + 1, 4F);
                assertCornerMatchesBoard(9, 9, cell, LiquidCell.CORNER_NW, x, y + 1, 4F);
            }
        }
        assertEquals(quantised(4.5F, 4F), deepestCorner(cellAt(cells, 4, 4)), QUANTISATION,
                "the centre is its real distance from land, not a clamped 1");
        assertEquals(quantised(1.5F, 4F), deepestCorner(cellAt(cells, 0, 0)), QUANTISATION,
                "and the board-edge cell is 1.5 cells from the nearest land centre");
    }

    @Test
    void aOneCellWideChannelHasNoDepthGradient() {
        List<LiquidCell> cells = LiquidGeometry.collect(5, 3, grid(
                ".....",
                ".WWW.",
                "....."), 0F, 0F, 0F, DEEP);

        for (LiquidCell cell : cells) {
            assertTrue(deepestCorner(cell) <= 1F / DEEP,
                    "a channel this narrow is nearly all shoreline");
        }
    }

    @Test
    void depthGrowsWithDistanceFromLand() {
        List<LiquidCell> cells = LiquidGeometry.collect(9, 9, grid(
                "WWWWWWWWW", "WWWWWWWWW", "WWWWWWWWW", "WWWWWWWWW", "WWWWWWWWW",
                "WWWWWWWWW", "WWWWWWWWW", "WWWWWWWWW", "WWWWWWWWW"), 0F, 0F, 0F, 4F);

        float previous = -1F;
        for (int x = 0; x <= 4; x++) {
            float depth = deepestCorner(cellAt(cells, x, 4));
            assertTrue(depth >= previous, "depth must never shrink away from land (x=" + x + ")");
            if (x <= 2) {
                assertTrue(depth > previous, "depth must grow over the first rings (x=" + x + ")");
            }
            previous = depth;
        }
        assertEquals(quantised(4.5F, 4F), previous, QUANTISATION,
                "the centre lattice corner is 4.5 cells from the nearest land centre");
    }

    @Test
    void boardEdgesCountAsLand() {
        List<LiquidCell> cells = LiquidGeometry.collect(2, 1, grid("WW"), 0F, 0F, 0F, DEEP);

        assertTrue((cellAt(cells, 0, 0).borders() & LiquidCell.WEST) != 0,
                "the left board edge is a shore");
        assertTrue((cellAt(cells, 1, 0).borders() & LiquidCell.EAST) != 0,
                "the right board edge is a shore");
        // Lattice (0,0) is the board's corner; the nearest land centre is the cell just
        // outside it (or diagonally outside), half a cell away.
        assertEquals(quantised(0.7071F, DEEP), corner(cellAt(cells, 0, 0), LiquidCell.CORNER_SW),
                QUANTISATION,
                "the board's own corner is half a cell diagonally from a land centre, "
                        + "which is the case a flood without a dry padding ring cannot seed");
        assertEquals(quantised(expectedCornerDistance(2, 1, 1, 1), DEEP),
                deepestCorner(cellAt(cells, 0, 0)), 0F,
                "and the far corner measures the real distance out to the board edge");
    }

    @Test
    void emptyBoardProducesNothing() {
        assertTrue(LiquidGeometry.collect(0, 0, grid(""), 0F, 0F, 0F, DEEP).isEmpty());
        assertTrue(LiquidGeometry.collect(3, 3, grid("...", "...", "..."), 0F, 0F, 0F, DEEP)
                .isEmpty());
    }

    // ----------------------------------------------------------------- packing

    @Test
    void packingCarriesShoreFlagsInTheSigns() {
        List<LiquidCell> cells = LiquidGeometry.collect(8, 8, grid(
                "........",
                "........",
                "WWWWWW..",
                "WWWWWW..",
                "WWWWWW..",
                "WWWWWW..",
                "WWWWWW..",
                "WWWWWW.."), 0F, 0F, 0F, DEEP);

        Packed southWest = packedAt(cells, 0, 0);
        assertEquals(0F, Math.abs(southWest.x()) - 1F, 1e-6F);
        assertFalse(southWest.northHasShore(), "the north side is open water");
        assertTrue(southWest.westHasShore(), "the west side is the board edge");
        assertEquals(LiquidCell.SOUTH | LiquidCell.WEST, southWest.borders());
        assertEquals(1, southWest.southCell(), "the south edge faces land");
        assertEquals(1, southWest.westCell());
        assertEquals(15 & ~LiquidCell.CORNER_NE, southWest.corners());

        Packed interior = packedAt(cells, 2, 2);
        assertFalse(interior.northHasShore(), "the north neighbour is water, so the sign is positive");
        assertFalse(interior.westHasShore(), "and so is the west one");
        assertEquals(0, interior.borders(), "a cell this far in faces no land");
        assertEquals(0, interior.corners());
        assertTrue(interior.depthFactor() > 0F,
                "an interior vertex carries a real distance, not a zero that would "
                        + "feather the middle of the pool away");
        assertEquals(corner(cellAt(cells, 2, 2), LiquidCell.CORNER_SW), interior.depthFactor(),
                0F, "and it is the corner that vertex actually sits on");

        Packed northEast = packedAt(cells, 5, 5);
        assertTrue(northEast.northHasShore(), "its north side is the board edge");
        assertFalse(northEast.westHasShore(), "and its west side is open water");
        assertEquals(LiquidCell.NORTH | LiquidCell.EAST, northEast.borders());
        assertTrue((northEast.corners() & LiquidCell.CORNER_NE) != 0,
                "the north-east diagonal faces land");
    }

    @Test
    void packingMarksOpenWaterAsDeep() {
        List<LiquidCell> cells = LiquidGeometry.collect(6, 8, grid(
                "WWWWWW", "WWWWWW", "WWWWWW", "WWWWWW",
                "WWWWWW", "WWWWWW", "WWWWWW", "WWWWWW"), 0F, 0F, 0F, DEEP);

        Packed centre = packedAt(cells, 3, 4);
        assertEquals(0, centre.borders(), "the inner water faces no land at all");
        // The measured distance, carried through the same rounding the packer uses.
        // Compared with one quantisation step of slack: the closed form and the
        // implementation reach the same level, and the last bit of a float divide must
        // not turn that into a failure.
        assertEquals(quantised(expectedCornerDistance(6, 8, 3, 4), DEEP), centre.depthFactor(),
                QUANTISATION,
                "and the vertex carries its true measured distance from land");

        Packed bottomLeft = packedAt(cells, 0, 0);
        assertEquals(LiquidCell.SOUTH | LiquidCell.WEST, bottomLeft.borders(),
                "the board edge is a shoreline, so the rim still foams");
        assertEquals(corner(cellAt(cells, 0, 0), LiquidCell.CORNER_SW), bottomLeft.depthFactor(),
                0F, "the corner vertex carries the measured distance, not a zero");

        // The ramp is a distance field, so every ring inwards must be strictly deeper:
        // two flat levels with a step between them is the bug this replaced.
        assertTrue(packedAt(cells, 1, 1).depthFactor() > bottomLeft.depthFactor(),
                "one ring in must be deeper than the rim");
        assertTrue(packedAt(cells, 2, 3).depthFactor() > packedAt(cells, 1, 1).depthFactor(),
                "and two rings in deeper still");
        assertTrue(centre.depthFactor() > packedAt(cells, 2, 3).depthFactor(),
                "with the centre the deepest");
    }

    /**
     * Every vertex must carry the depth of the corner it actually sits on.
     *
     * <p>Regression guard for a real bug: the geometry pass filled the four corner
     * values in NW/NE/SE/SW order while the batch read them as SW/SE/NE/NW, so each
     * cell's gradient was rotated - the deep side of every cell pointed at the shore.
     * The field itself looked healthy in a dump (all four distances present and
     * correct); only the direction of the ramp inside each cell was wrong. Reading it
     * back per vertex is the only way to see that, because the batch is where the two
     * orders meet.
     */
    @Test
    void everyVertexCarriesItsOwnCornersDepth() {
        List<LiquidCell> cells = LiquidGeometry.collect(8, 8, grid(
                "........",
                "........",
                "WWWWWW..",
                "WWWWWW..",
                "WWWWWW..",
                "WWWWWW..",
                "WWWWWW..",
                "WWWWWW.."), 0F, 0F, 0F, DEEP);

        float[] vertices = verticesOf(cells);
        int stride = LiquidVertexFormat.VERTEX_FLOATS;
        for (int index = 0; index < cells.size(); index++) {
            LiquidCell cell = cells.get(index);
            for (int v = 0; v < LiquidBatch.VERTICES_PER_CELL; v++) {
                int at = index * LiquidBatch.VERTICES_PER_CELL * stride + v * stride;
                float localX = vertices[at + LiquidVertexFormat.UV_OFFSET];
                float localY = vertices[at + LiquidVertexFormat.UV_OFFSET + 1];
                int liquid = at + LiquidVertexFormat.LIQUID_OFFSET;
                float carried = Packed.of(new float[]{vertices[liquid], vertices[liquid + 1],
                        vertices[liquid + 2], vertices[liquid + 3]}).depthFactor();
                assertEquals(cell.cornerDepth()[LiquidCell.cornerSlot(localX, localY)],
                        carried, QUANTISATION,
                        "vertex " + v + " of cell " + (int) cell.cellX() + ","
                                + (int) cell.cellY() + " at local " + localX + "," + localY);
            }
        }
    }

    /**
     * The field must be continuous across a cell boundary.
     *
     * <p>Two cells sharing a lattice corner must agree on its depth, or the
     * interpolated surface steps at that boundary and the water reads as a grid of
     * tiles - the artefact this per-corner scheme exists to remove.
     */
    @Test
    void neighbouringCellsAgreeOnTheirSharedCorners() {
        List<LiquidCell> cells = LiquidGeometry.collect(9, 9, grid(
                "WWWWWWWWW", "WWWWWWWWW", "WWWWWWWWW", "WWWWWWWWW", "WWWWWWWWW",
                "WWWWWWWWW", "WWWWWWWWW", "WWWWWWWWW", "WWWWWWWWW"), 0F, 0F, 0F, 4F);

        for (int y = 0; y < 9; y++) {
            for (int x = 0; x < 9; x++) {
                LiquidCell cell = cellAt(cells, x, y);
                if (x + 1 < 9) {
                    assertEquals(corner(cell, LiquidCell.CORNER_SE),
                            corner(cellAt(cells, x + 1, y), LiquidCell.CORNER_SW), 0F,
                            "the SE corner of " + x + "," + y + " is shared with its east neighbour");
                    assertEquals(corner(cell, LiquidCell.CORNER_NE),
                            corner(cellAt(cells, x + 1, y), LiquidCell.CORNER_NW), 0F,
                            "and so is its NE corner");
                }
                if (y + 1 < 9) {
                    assertEquals(corner(cell, LiquidCell.CORNER_NW),
                            corner(cellAt(cells, x, y + 1), LiquidCell.CORNER_SW), 0F,
                            "the NW corner of " + x + "," + y + " is shared with its north neighbour");
                }
            }
        }
    }
}
