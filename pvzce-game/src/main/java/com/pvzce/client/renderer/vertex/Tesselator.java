package com.pvzce.client.renderer.vertex;

public final class Tesselator {
    public static final Tesselator INSTANCE = new Tesselator();
    private static final int INITIAL_VERTICES = 1024;

    private final BufferBuilder builder = new BufferBuilder(VertexFormat.POSITION_COLOR_UV, INITIAL_VERTICES);

    private Tesselator() {
    }

    public BufferBuilder begin() {
        builder.reset();
        return builder;
    }

    public BufferBuilder builder() {
        return builder;
    }

    public void draw(BufferBuilder.MeshData mesh) {
        try (mesh) {
            org.lwjgl.opengl.GL30.glBindVertexArray(mesh.vao());
            org.lwjgl.opengl.GL11.glDrawArrays(org.lwjgl.opengl.GL11.GL_TRIANGLES, 0, mesh.vertexCount());
            org.lwjgl.opengl.GL30.glBindVertexArray(0);
        }
    }
}
