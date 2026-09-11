package com.pvzce.client.renderer.vertex;

/**
 * Vertex layout width, in floats per vertex.
 *
 * <p>The sprite pipeline has one fixed layout (position 3, colour 4, uv 2). The
 * liquid pass appends its own per-cell attribute, so the width has to be a
 * property of the format rather than a constant - the alternative was to steal a
 * channel from the sprite layout, which would have made every existing draw path
 * carry a value it does not use.
 */
public final class VertexFormat {
    public static final int POSITION_COMPONENTS = 3;
    public static final int COLOR_COMPONENTS = 4;
    public static final int UV_COMPONENTS = 2;

    /** The shared sprite layout: POSITION(3F) COLOR(4F) UV(2F), 9 floats. */
    public static final int SPRITE_FLOATS = POSITION_COMPONENTS + COLOR_COMPONENTS + UV_COMPONENTS;

    public static final VertexFormat POSITION_COLOR_UV = new VertexFormat(SPRITE_FLOATS);

    public enum Mode {
        TRIANGLES
    }

    private final int vertexFloats;

    public VertexFormat(int vertexFloats) {
        this.vertexFloats = Math.max(1, vertexFloats);
    }

    public int vertexFloats() {
        return vertexFloats;
    }

    public int vertexBytes() {
        return vertexFloats * Float.BYTES;
    }
}
