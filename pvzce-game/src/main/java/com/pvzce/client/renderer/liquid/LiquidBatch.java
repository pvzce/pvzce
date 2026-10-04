package com.pvzce.client.renderer.liquid;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.List;

/**
 * Builds and draws one liquid layer as a single mesh.
 *
 * <p>The board renderer currently creates and destroys a VAO and a VBO for every
 * quad it draws. Doing that per water cell would cost a driver round trip for
 * every tile, so the liquid pass collects its cells first and uploads once. That
 * is not a general batching rewrite - the sprite path is untouched - but the
 * liquid layer is the one place where the cell count is known up front and the
 * geometry is uniform, which makes it the cheapest place to stop paying per quad.
 *
 * <p>A cell is two triangles over its full square. The rounded outline and the
 * shoreline foam are NOT geometry: the fragment shader derives them from the
 * cell's border mask and its local coordinate, which keeps the mesh trivial and
 * the softness resolution-independent. The vertex colour's alpha carries "this
 * vertex is on a shore" so the shader can skip the whole edge treatment for the
 * interior of a lake.
 */
public final class LiquidBatch {
    /** Vertices each cell contributes; also the stride a reader of the buffer needs. */
    public static final int VERTICES_PER_CELL = 6;

    /** Two triangles over the cell square, counter-clockwise from the bottom-left. */
    private static final float[][] OUTLINE = {
            {0F, 0F}, {1F, 0F}, {1F, 1F},
            {0F, 0F}, {1F, 1F}, {0F, 1F}};

    private ByteBuffer bytes;
    private FloatBuffer floats;
    private int vertexCount;
    private int capacityVertices;
    private int vao;
    private int vbo;

    public LiquidBatch(int initialCells) {
        allocate(Math.max(64, initialCells * OUTLINE.length));
    }

    private void allocate(int vertices) {
        capacityVertices = Math.max(64, vertices);
        bytes = BufferUtils.createByteBuffer(capacityVertices * LiquidVertexFormat.VERTEX_FLOATS * Float.BYTES)
                .order(ByteOrder.nativeOrder());
        floats = bytes.asFloatBuffer();
        vertexCount = 0;
    }

    /** Drops the previous frame's geometry, keeping the allocation. */
    public void reset() {
        floats.clear();
        vertexCount = 0;
    }

    public boolean isEmpty() {
        return vertexCount == 0;
    }

    public int vertexCount() {
        return vertexCount;
    }

    /**
     * Appends one cell.
     *
     * @param cell    resolved cell facts
     * @param originX where the cell's {@code [0,1]} square starts in the caller's
     *                coordinate space
     * @param originY see {@code originX}
     * @param width   cell width in the caller's units
     * @param height  cell height in the caller's units
     */
    @FunctionalInterface
    public interface Elevation {
        float at(float x, float y);
        Elevation FLAT = (x, y) -> 0F;
    }

    public void cell(LiquidCell cell, float originX, float originY, float width, float height) {
        cell(cell, originX, originY, width, height, Elevation.FLAT);
    }
    public void cell(LiquidCell cell, float originX, float originY, float width, float height, Elevation elevation) {
        boolean shore = cell.borders() != 0 || cell.corners() != 0;
        for (float[] corner : OUTLINE) {
            vertex(cell, originX, originY, width, height, corner[0], corner[1], shore, elevation);
        }
    }

    private void vertex(LiquidCell cell, float originX, float originY, float width, float height,
                        float localX, float localY, boolean shore, Elevation elevation) {
        ensureCapacity(1);

        boolean north = (cell.borders() & LiquidCell.NORTH) != 0;
        boolean west = (cell.borders() & LiquidCell.WEST) != 0;
        // +1 puts the value out of reach of 0 so its sign can carry the
        // "neighbour continues the liquid" flag without a second channel.
        float packedX = north ? -(cell.cellX() + 1F) : (cell.cellX() + 1F);
        float packedY = west ? -(cell.cellY() + 1F) : (cell.cellY() + 1F);
        // Border mask in bits 0..3, and bit 4 carries whether the NORTH side is a
        // shore. The north flag is duplicated here only so the mask reads back
        // completely from one word; the sign of x carries it too.
        float packedZ = cell.borders() | ((north ? 1 : 0) << 4);
        // Corner mask in bits 0..3, and this vertex's CORNER DEPTH FACTOR in bits 4..11.
        //
        // The layout matters: an earlier version put the WEST flag at bit 2, which is
        // also CORNER_SW, so any cell whose south-west diagonal faced land had that
        // bit already set and lost a step of depth. The sign of y carries the west
        // flag, so the fix is simply not to duplicate it in a word that has no spare
        // bit. The depth used to live here too, one value per cell; it is now per
        // vertex so the shader interpolates a gradient across the quad instead of
        // painting a flat plate. See LiquidCell.cornerDepth.
        int depthLevel = Math.round(cell.cornerDepth()[LiquidCell.cornerSlot(localX, localY)]
                / LiquidCell.MAX_DEPTH_FACTOR * (LiquidCell.DEPTH_LEVELS - 1));
        float packedW = cell.corners() | (depthLevel << 4);

        floats.put(originX + localX * width).put(originY + localY * height
                + elevation.at(cell.cellX() + localX, cell.cellY() + localY)).put(0F);
        floats.put(cell.color()[0]).put(cell.color()[1]).put(cell.color()[2]).put(shore ? 1F : 0F);
        floats.put(localX).put(localY);
        floats.put(packedX).put(packedY).put(packedZ).put(packedW);
        vertexCount++;
    }

    private void ensureCapacity(int vertices) {
        if (vertexCount + vertices <= capacityVertices) {
            return;
        }
        int newCapacity = Math.max(capacityVertices * 2, vertexCount + vertices);
        FloatBuffer old = floats;
        old.flip();
        int used = old.remaining();
        ByteBuffer newBytes = BufferUtils.createByteBuffer(
                newCapacity * LiquidVertexFormat.VERTEX_FLOATS * Float.BYTES).order(ByteOrder.nativeOrder());
        FloatBuffer newFloats = newBytes.asFloatBuffer();
        newFloats.put(old);
        newFloats.position(used);
        bytes = newBytes;
        floats = newFloats;
        capacityVertices = newCapacity;
    }

    /**
     * A copy of the raw vertex stream, for tests.
     *
     * <p>Exposed rather than made package-visible state because the encoding it
     * contains is exactly what the shader decodes, and it is the part of the feature
     * that cannot be checked without a GL context any other way.
     */
    public float[] vertexSnapshot() {
        int used = vertexCount * LiquidVertexFormat.VERTEX_FLOATS;
        float[] copy = new float[used];
        for (int index = 0; index < used; index++) {
            copy[index] = floats.get(index);
        }
        return copy;
    }

    /**
     * Uploads the collected geometry and draws it as triangles.
     *
     * <p>The VAO and VBO are created once and reused. Creating and deleting them per
     * pass - which is what this did first - is the driver round trip the class javadoc
     * says it exists to avoid, and it turns every frame into a fresh allocation and a
     * synchronisation point. The buffer is re-specified each frame with glBufferData,
     * which is enough: the contents change every frame anyway.
     */
    public void draw() {
        if (vertexCount == 0) {
            return;
        }
        if (vao == 0) {
            vao = GL30.glGenVertexArrays();
            vbo = GL15.glGenBuffers();
            GL30.glBindVertexArray(vao);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
            int stride = LiquidVertexFormat.VERTEX_FLOATS * Float.BYTES;
            // Locations match the bindings the liquid program requests.
            GL20.glEnableVertexAttribArray(LiquidShader.ATTRIB_POSITION);
            GL20.glVertexAttribPointer(LiquidShader.ATTRIB_POSITION, 3, GL20.GL_FLOAT, false, stride,
                    (long) LiquidVertexFormat.POSITION_OFFSET * Float.BYTES);
            GL20.glEnableVertexAttribArray(LiquidShader.ATTRIB_COLOR);
            GL20.glVertexAttribPointer(LiquidShader.ATTRIB_COLOR, 4, GL20.GL_FLOAT, false, stride,
                    (long) LiquidVertexFormat.COLOR_OFFSET * Float.BYTES);
            GL20.glEnableVertexAttribArray(LiquidShader.ATTRIB_UV);
            GL20.glVertexAttribPointer(LiquidShader.ATTRIB_UV, 2, GL20.GL_FLOAT, false, stride,
                    (long) LiquidVertexFormat.UV_OFFSET * Float.BYTES);
            GL20.glEnableVertexAttribArray(LiquidShader.ATTRIB_LIQUID);
            GL20.glVertexAttribPointer(LiquidShader.ATTRIB_LIQUID, 4, GL20.GL_FLOAT, false, stride,
                    (long) LiquidVertexFormat.LIQUID_OFFSET * Float.BYTES);
            GL30.glBindVertexArray(0);
        }
        floats.position(0);
        floats.limit(vertexCount * LiquidVertexFormat.VERTEX_FLOATS);
        GL30.glBindVertexArray(vao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, floats, GL15.GL_DYNAMIC_DRAW);
        org.lwjgl.opengl.GL11.glDrawArrays(org.lwjgl.opengl.GL11.GL_TRIANGLES, 0, vertexCount);
        GL30.glBindVertexArray(0);
    }

    /** Releases the GL objects; call when the context goes away. */
    public void close() {
        if (vao != 0) {
            GL30.glDeleteVertexArrays(vao);
            GL15.glDeleteBuffers(vbo);
            vao = 0;
            vbo = 0;
        }
    }

    /** Convenience for callers that hold a list and a uniform cell size. */
    public void addAll(List<LiquidCell> cells, float originX, float originY, float width, float height) {
        addAll(cells, originX, originY, width, height, Elevation.FLAT);
    }
    public void addAll(List<LiquidCell> cells, float originX, float originY, float width, float height, Elevation elevation) {
        for (LiquidCell cell : cells) {
            cell(cell, originX + cell.cellX() * width, originY + cell.cellY() * height, width, height, elevation);
        }
    }
}
