package com.pvzce.client.renderer.vertex;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/** Growable CPU-side vertex buffer; {@link Tesselator} uploads it. */
public final class BufferBuilder implements VertexConsumer {
    private ByteBuffer bytes;
    private FloatBuffer floats;
    private final VertexFormat format;
    private int vertexCount;

    private float px, py, pz;
    private float r = 1, g = 1, b = 1, a = 1;
    private float u, v;

    public BufferBuilder(VertexFormat format, int initialVertices) {
        this.format = format;
        this.bytes = ByteBuffer.allocateDirect(initialVertices * format.vertexFloats() * Float.BYTES).order(ByteOrder.nativeOrder());
        this.floats = bytes.asFloatBuffer();
    }

    @Override
    public VertexConsumer vertex(float x, float y, float z) {
        px = x;
        py = y;
        pz = z;
        return this;
    }

    @Override
    public VertexConsumer color(float r, float g, float b, float a) {
        this.r = r;
        this.g = g;
        this.b = b;
        this.a = a;
        return this;
    }

    @Override
    public VertexConsumer uv(float u, float v) {
        this.u = u;
        this.v = v;
        return this;
    }

    @Override
    public void endVertex() {
        ensureCapacity(format.vertexFloats());
        floats.put(px).put(py).put(pz);
        floats.put(r).put(g).put(b).put(a);
        floats.put(u).put(v);
        vertexCount++;
    }

    private void ensureCapacity(int floatsToAdd) {
        if (floats.remaining() < floatsToAdd) {
            int oldCapacity = bytes.capacity();
            int newCapacity = Math.max(oldCapacity * 2, oldCapacity + floatsToAdd * Float.BYTES * 64);
            ByteBuffer newBytes = ByteBuffer.allocateDirect(newCapacity).order(ByteOrder.nativeOrder());
            bytes.flip();
            newBytes.put(bytes);
            bytes = newBytes;
            floats = bytes.asFloatBuffer();
            floats.position(vertexCount * format.vertexFloats());
        }
    }

    public int vertexCount() {
        return vertexCount;
    }

    /** The vertex data, for the {@link Tesselator} to upload. */
    public ByteBuffer bytes() {
        return bytes;
    }

    /**
     * Marks the geometry as finished and tells the tesselator it is waiting to be drawn.
     *
     * <p>No GL work happens here any more, which is why the method is kept at all: every
     * caller writes {@code builder.build()} before drawing, and the only thing left to do at
     * that point is the bookkeeping that catches a quad that is built and then dropped.
     */
    public MeshData build() {
        Tesselator.INSTANCE.markBuilt(vertexCount);
        return new MeshData(vertexCount);
    }

    public void reset() {
        bytes.clear();
        floats.clear();
        vertexCount = 0;
    }

    /**
     * What {@link #build()} returns.
     *
     * <p>It used to be a VAO/VBO pair that had to be closed, so every draw call created and
     * deleted a pair of GPU objects; see {@link Tesselator} for what that cost. The only thing
     * left to carry is the vertex count, and there is nothing to release.
     */
    public record MeshData(int vertexCount) implements AutoCloseable {
        @Override
        public void close() {
        }
    }
}
