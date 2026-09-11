package com.pvzce.client.renderer.vertex;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/** Growable CPU-side vertex buffer; {@link #build()} uploads a VAO/VBO mesh. */
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

    public MeshData build() {
        int vao = GL30.glGenVertexArrays();
        GL30.glBindVertexArray(vao);

        int vbo = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        int size = vertexCount * format.vertexFloats() * Float.BYTES;
        bytes.position(0);
        bytes.limit(size);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, bytes, GL15.GL_STATIC_DRAW);

        int stride = format.vertexFloats() * Float.BYTES;
        GL20.glEnableVertexAttribArray(0);
        GL20.glVertexAttribPointer(0, 3, GL20.GL_FLOAT, false, stride, 0);
        GL20.glEnableVertexAttribArray(1);
        GL20.glVertexAttribPointer(1, 4, GL20.GL_FLOAT, false, stride, 3L * Float.BYTES);
        GL20.glEnableVertexAttribArray(2);
        GL20.glVertexAttribPointer(2, 2, GL20.GL_FLOAT, false, stride, 7L * Float.BYTES);

        GL30.glBindVertexArray(0);
        return new MeshData(vao, vbo, vertexCount);
    }

    public void reset() {
        bytes.clear();
        floats.clear();
        vertexCount = 0;
    }

    public record MeshData(int vao, int vbo, int vertexCount) implements AutoCloseable {
        @Override
        public void close() {
            GL30.glDeleteVertexArrays(vao);
            GL15.glDeleteBuffers(vbo);
        }
    }
}
