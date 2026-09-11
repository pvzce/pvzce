package com.pvzce.client.renderer.liquid;

import com.pvzce.client.renderer.vertex.VertexFormat;

/**
 * Vertex layout for the liquid pass: the shared sprite layout plus four floats
 * per vertex that only liquid geometry writes.
 *
 * <p>Why an extra attribute instead of reusing the existing ones: the liquid
 * shader needs per-cell facts (which cell this vertex belongs to, which of its
 * sides face land, whether the neighbouring cell continues the liquid) and there
 * is nowhere in {@code POSITION(3) COLOR(4) UV(2)} to put them without stealing a
 * channel something else already uses. Appending to the format instead of
 * repurposing leaves every other draw path untouched - the water program does not
 * bind position/colour/uv to the same locations it would otherwise, and nothing
 * else reads the new attribute.
 *
 * <p>The four floats are, per vertex:
 * <pre>
 *   x = (cellX + 1)                    (+1 keeps the value off zero so the sign is
 *   y = (cellY + 1)                     free to carry a flag)
 *   z = borderMask (bits 0..3) | northIsShore (bit 4)
 *   w = depth (bits 0..2) | cornerMask (bits 3..6)
 * </pre>
 * x is NEGATED when the north side is a shore, y when the west side is. Note the
 * axis pairing, because it is the reverse of the obvious one and it is easy to
 * "fix" into a bug: the NORTH flag lives in x, but the shore MASK measures north
 * on the y coordinate of the vertex's uv. A future change that folds the uv
 * against these signs must therefore fold north against y and west against x.
 *
 * <p>{@code aUV} itself is NOT sign-encoded - it is the vertex's plain 0..1
 * position inside its cell. An earlier revision of this file documented a
 * sign-fold through {@code [0,2)} that the encoder never performed, so the shader
 * branches that consumed it were dead; both were removed rather than implemented,
 * because the raw uv already matches the mask layout.
 */
public final class LiquidVertexFormat {
    public static final int POSITION_COMPONENTS = 3;
    public static final int COLOR_COMPONENTS = 4;
    public static final int UV_COMPONENTS = 2;
    public static final int LIQUID_COMPONENTS = 4;

    public static final int POSITION_OFFSET = 0;
    public static final int COLOR_OFFSET = POSITION_OFFSET + POSITION_COMPONENTS;
    public static final int UV_OFFSET = COLOR_OFFSET + COLOR_COMPONENTS;
    public static final int LIQUID_OFFSET = UV_OFFSET + UV_COMPONENTS;

    public static final int VERTEX_FLOATS =
            POSITION_COMPONENTS + COLOR_COMPONENTS + UV_COMPONENTS + LIQUID_COMPONENTS;

    public static final VertexFormat FORMAT = new VertexFormat(VERTEX_FLOATS);

    private LiquidVertexFormat() {
    }
}
