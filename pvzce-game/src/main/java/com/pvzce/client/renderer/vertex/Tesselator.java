package com.pvzce.client.renderer.vertex;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.nio.ByteBuffer;

/**
 * The one vertex buffer every sprite is drawn through.
 *
 * <p>Geometry is built on the CPU, uploaded, drawn and then reused for the next quad. The
 * GPU objects - one VAO and one VBO - are created once and kept; {@link #draw} only re-specifies
 * the buffer's contents.
 *
 * <p>That "only" is the whole point. This used to create a VAO and a VBO per drawn quad and
 * delete both immediately afterwards, which is four GL object operations and two allocations
 * in the driver <em>per quad on screen</em>. A controller entity is 15 to 54 quads (see the
 * shipped models; the zombie boss is the worst), a full lawn plus a wave of zombies is well
 * over a thousand, so a frame was doing thousands of {@code glGenVertexArrays} /
 * {@code glDeleteVertexArrays} pairs. Frame time was dominated by object churn rather than by
 * pixels, and since animation time is sampled once per rendered frame from a wall clock, every
 * dropped frame showed up directly as coarse motion.
 *
 * <p>One builder object for the whole renderer is not a limitation here: a quad is built and
 * drawn in one call and nothing nests, which {@link #begin()} enforces rather than assumes.
 */
public final class Tesselator {
    public static final Tesselator INSTANCE = new Tesselator();
    private static final int INITIAL_VERTICES = 4096;
    /** How much room the GPU-side buffer is given, in vertices; grows on demand. */
    private static final int INITIAL_GPU_VERTICES = 65536;

    private final BufferBuilder builder = new BufferBuilder(VertexFormat.POSITION_COLOR_UV, INITIAL_VERTICES);

    private int vao;
    private int vbo;
    /** Vertices still in the builder, i.e. built but not yet drawn. */
    private int pendingVertices;
    /** Capacity of the GPU buffer, in bytes. */
    private long gpuCapacityBytes;

    private Tesselator() {
    }

    /**
     * Starts a new quad.
     *
     * @throws IllegalStateException if the previous one was built but never drawn, which means
     *                               a caller has lost its geometry and would otherwise have it
     *                               silently replaced. That is a programming error, and finding
     *                               it here is much cheaper than finding it as a missing sprite.
     */
    public BufferBuilder begin() {
        if (pendingVertices > 0) {
            throw new IllegalStateException("Tesselator.begin() while " + pendingVertices
                    + " built vertices were never drawn");
        }
        builder.reset();
        return builder;
    }

    public BufferBuilder builder() {
        return builder;
    }

    /**
     * Uploads what has been built and draws it as triangles.
     *
     * <p>The upload orphans whatever the driver had: {@code glBufferData} with a null-compatible
     * size is the standard way to say "the old contents do not matter", which stops the driver
     * from stalling until the previous frame's draw has finished reading the buffer. It is
     * streamed geometry - used once, then overwritten - so there is nothing to preserve.
     */
    public void draw(BufferBuilder.MeshData mesh) {
        drawPending();
    }

    /** Draws whatever {@link #builder()} currently holds. */
    public void drawPending() {
        int vertexCount = builder.vertexCount();
        if (vertexCount <= 0) {
            pendingVertices = 0;
            return;
        }
        ensureGpuObjects();
        ByteBuffer bytes = builder.bytes();
        long size = (long) vertexCount * VertexFormat.POSITION_COLOR_UV.vertexFloats() * Float.BYTES;
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        bytes.position(0);
        bytes.limit((int) size);
        if (size > gpuCapacityBytes) {
            // A quad bigger than the buffer's initial capacity grows it: the sub-data write
            // below needs storage that exists first. Quads are 6 vertices, so this only fires
            // if the initial capacity is ever lowered.
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, size, GL15.GL_STREAM_DRAW);
            gpuCapacityBytes = size;
        }
        GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, 0L, bytes);
        GL30.glBindVertexArray(vao);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, vertexCount);
        GL30.glBindVertexArray(0);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        pendingVertices = 0;
    }

    /** Releases the GPU objects; the renderer owns them for the life of the context. */
    public void close() {
        if (vao != 0) {
            GL30.glDeleteVertexArrays(vao);
            GL15.glDeleteBuffers(vbo);
            vao = 0;
            vbo = 0;
            gpuCapacityBytes = 0;
        }
    }

    /** Called by {@link BufferBuilder#build()} so a lost quad is detected at the next begin(). */
    void markBuilt(int vertices) {
        pendingVertices = vertices;
    }

    private void ensureGpuObjects() {
        int stride = VertexFormat.POSITION_COLOR_UV.vertexFloats() * Float.BYTES;
        long needed = (long) INITIAL_GPU_VERTICES * stride;
        if (vao == 0) {
            vao = GL30.glGenVertexArrays();
            vbo = GL15.glGenBuffers();
            GL30.glBindVertexArray(vao);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, needed, GL15.GL_STREAM_DRAW);
            // Set once, not per frame: the VAO remembers the attribute layout and the buffer
            // it points at, and the buffer is only ever re-uploaded in place.
            GL20.glEnableVertexAttribArray(0);
            GL20.glVertexAttribPointer(0, 3, GL20.GL_FLOAT, false, stride, 0);
            GL20.glEnableVertexAttribArray(1);
            GL20.glVertexAttribPointer(1, 4, GL20.GL_FLOAT, false, stride, 3L * Float.BYTES);
            GL20.glEnableVertexAttribArray(2);
            GL20.glVertexAttribPointer(2, 2, GL20.GL_FLOAT, false, stride, 7L * Float.BYTES);
            GL30.glBindVertexArray(0);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
            gpuCapacityBytes = needed;
        }
    }
}
