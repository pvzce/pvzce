package com.pvzce.client.renderer;

import com.pvzce.client.renderer.sprite.Sprite;
import com.pvzce.client.renderer.vertex.BufferBuilder;
import com.pvzce.client.renderer.vertex.Tesselator;
import com.pvzce.client.renderer.vertex.VertexConsumer;

/** Draws textured or colored quads in world/UI space. */
public final class SpriteRenderer {
    private SpriteRenderer() {
    }

    public static void textured(Sprite sprite, float x, float y, float width, float height, float z,
                                float r, float g, float b, float a) {
        RenderSystem.setShadowMode(false);
        RenderSystem.bindTexture(sprite.texture().glId());
        RenderSystem.setTextured(true);
        BufferBuilder builder = Tesselator.INSTANCE.begin();
        VertexConsumer v = builder;
        quad(v, x, y, x + width, y + height, z, sprite.u0(), sprite.v0(), sprite.u1(), sprite.v1(), r, g, b, a);
        var mesh = builder.build();
        Tesselator.INSTANCE.draw(mesh);
    }

    /**
     * Light-source projected shadow. The sprite is treated as a vertical
     * billboard standing on {@code groundY}; its four corners are projected
     * from {@code (lightX, lightY)} onto the ground plane, giving a trapezoid
     * whose direction and length follow the actual sun/moon position.
     */
    public static void projectedShadow(Sprite sprite, float lightX, float lightY, float groundY,
                                       float entityCenterX, float entityWidth, float entityHeight, float z,
                                       float r, float g, float b, float a) {
        if (entityHeight <= 0F || a <= 0F) {
            return;
        }
        float half = entityWidth / 2F;
        float blX = entityCenterX - half;
        float brX = entityCenterX + half;
        float tlX = projectShadowX(blX, groundY + entityHeight, lightX, lightY, groundY);
        float trX = projectShadowX(brX, groundY + entityHeight, lightX, lightY, groundY);

        // The shadow is a trapezoid lying on the ground plane, so its far edge needs
        // a vertical extent. The old version put all six vertices on `groundY`, which
        // made both triangles degenerate - zero area, no fragments - so the projected
        // shadow never appeared and only the missing-texture fallback ellipse showed.
        // The far edge is squashed towards the ground line so it reads as a shadow
        // cast along the ground rather than a second copy of the sprite.
        float farY = groundY;
        float nearY = groundY + entityHeight * SHADOW_FAR_LIFT;
        if (nearY <= farY) {
            return;
        }

        RenderSystem.bindTexture(sprite.texture().glId());
        RenderSystem.setTextured(true);
        RenderSystem.setShadowColor(r, g, b, a);
        RenderSystem.setShadowMode(true);
        try {
            BufferBuilder builder = Tesselator.INSTANCE.begin();
            VertexConsumer v = builder;
            // Bottom texture edge stays under the entity (U u0..u1 at v0), the tapering
            // far edge uses v1 so the sprite silhouette is preserved along the cast.
            v.vertex(blX, farY, z).color(1F, 1F, 1F, 1F).uv(sprite.u0(), sprite.v0()).endVertex();
            v.vertex(brX, farY, z).color(1F, 1F, 1F, 1F).uv(sprite.u1(), sprite.v0()).endVertex();
            v.vertex(trX, nearY, z).color(1F, 1F, 1F, 1F).uv(sprite.u1(), sprite.v1()).endVertex();

            v.vertex(blX, farY, z).color(1F, 1F, 1F, 1F).uv(sprite.u0(), sprite.v0()).endVertex();
            v.vertex(trX, nearY, z).color(1F, 1F, 1F, 1F).uv(sprite.u1(), sprite.v1()).endVertex();
            v.vertex(tlX, nearY, z).color(1F, 1F, 1F, 1F).uv(sprite.u0(), sprite.v1()).endVertex();
            Tesselator.INSTANCE.draw(builder.build());
        } finally {
            RenderSystem.setShadowMode(false);
        }
    }

    /** How far the cast shadow's far edge is lifted off the ground line. */
    private static final float SHADOW_FAR_LIFT = 0.18F;

    private static float projectShadowX(float x, float height, float lightX, float lightY, float groundY) {
        if (height >= lightY || lightY <= groundY) {
            return x;
        }
        float t = (lightY - groundY) / Math.max(0.001F, lightY - height);
        float projected = lightX + (x - lightX) * t;
        float dx = projected - x;
        return x + Math.max(-0.85F, Math.min(0.85F, dx));
    }

    /**
     * Draws an arbitrary textured quad. Corner order is bl, br, tr, tl and
     * each corner supplies its own UV, which is what 2D controller parts need.
     */
    public static void texturedQuad(com.pvzce.client.renderer.texture.Texture texture,
                                    float x0, float y0, float x1, float y1,
                                    float x2, float y2, float x3, float y3,
                                    float u0, float v0, float u1, float v1,
                                    float u2, float v2, float u3, float v3,
                                    float z, float r, float g, float b, float a) {
        RenderSystem.setShadowMode(false);
        RenderSystem.bindTexture(texture.glId());
        RenderSystem.setTextured(true);
        BufferBuilder builder = Tesselator.INSTANCE.begin();
        VertexConsumer v = builder;
        v.vertex(x0, y0, z).color(r, g, b, a).uv(u0, v0).endVertex();
        v.vertex(x1, y1, z).color(r, g, b, a).uv(u1, v1).endVertex();
        v.vertex(x2, y2, z).color(r, g, b, a).uv(u2, v2).endVertex();

        v.vertex(x0, y0, z).color(r, g, b, a).uv(u0, v0).endVertex();
        v.vertex(x2, y2, z).color(r, g, b, a).uv(u2, v2).endVertex();
        v.vertex(x3, y3, z).color(r, g, b, a).uv(u3, v3).endVertex();
        var mesh = builder.build();
        Tesselator.INSTANCE.draw(mesh);
    }

    public static void solid(float x, float y, float width, float height, float z,
                             float r, float g, float b, float a) {
        RenderSystem.setShadowMode(false);
        RenderSystem.setTextured(false);
        BufferBuilder builder = Tesselator.INSTANCE.begin();
        quad(builder, x, y, x + width, y + height, z, 0, 0, 1, 1, r, g, b, a);
        var mesh = builder.build();
        Tesselator.INSTANCE.draw(mesh);
    }

    /** Draws a soft ellipse shadow centered on {@code (centerX, centerY)}. */
    public static void shadow(float centerX, float centerY, float radiusX, float radiusY, float z,
                              float r, float g, float b, float a) {
        if (radiusX <= 0F || radiusY <= 0F || a <= 0F) {
            return;
        }
        // The only draw entry point that used to leave the shadow-mode uniform alone,
        // so a caller falling back to the ellipse while shadow mode was on got a flat
        // tinted rectangle instead of a soft ellipse.
        RenderSystem.setShadowMode(false);
        RenderSystem.setTextured(false);
        BufferBuilder builder = Tesselator.INSTANCE.begin();
        VertexConsumer v = builder;
        int segments = 16;
        for (int i = 0; i < segments; i++) {
            float angle0 = (float) (Math.PI * 2.0 * i / segments);
            float angle1 = (float) (Math.PI * 2.0 * (i + 1) / segments);
            float x0 = centerX + (float) Math.cos(angle0) * radiusX;
            float y0 = centerY + (float) Math.sin(angle0) * radiusY;
            float x1 = centerX + (float) Math.cos(angle1) * radiusX;
            float y1 = centerY + (float) Math.sin(angle1) * radiusY;
            v.vertex(centerX, centerY, z).color(r, g, b, a).uv(0.5F, 0.5F).endVertex();
            v.vertex(x0, y0, z).color(r, g, b, a * 0.55F).uv(0.5F, 0.5F).endVertex();
            v.vertex(x1, y1, z).color(r, g, b, a * 0.55F).uv(0.5F, 0.5F).endVertex();
        }
        var mesh = builder.build();
        Tesselator.INSTANCE.draw(mesh);
    }

    private static void quad(VertexConsumer v, float x0, float y0, float x1, float y1, float z,
                             float u0, float v0, float u1, float v1,
                             float r, float g, float b, float a) {
        v.vertex(x0, y0, z).color(r, g, b, a).uv(u0, v0).endVertex();
        v.vertex(x1, y0, z).color(r, g, b, a).uv(u1, v0).endVertex();
        v.vertex(x1, y1, z).color(r, g, b, a).uv(u1, v1).endVertex();

        v.vertex(x0, y0, z).color(r, g, b, a).uv(u0, v0).endVertex();
        v.vertex(x1, y1, z).color(r, g, b, a).uv(u1, v1).endVertex();
        v.vertex(x0, y1, z).color(r, g, b, a).uv(u0, v1).endVertex();
    }
}
