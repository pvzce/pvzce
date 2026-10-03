package com.pvzce.client.renderer;

import com.pvzce.api.util.Identifier;
import com.pvzce.client.ClientEntity;
import com.pvzce.client.PvzceClient;

/** Persistent light around a plantern; the source sprite needs overlapping additive layers. */
public final class PlanternHalo {
    private static final Identifier TEXTURE = Identifier.withDefaultNamespace("textures/particles/effect/lanternshine");
    private static final int LAYERS = 20;
    private static final float RADIUS = .95F;
    private static final float OPACITY = .5F;

    private PlanternHalo() {}

    public static void render(PvzceClient client, ClientEntity plant) {
        var texture = client.textures().getOrLoad(TEXTURE);
        float x = plant.visualCellX();
        float y = plant.visualCellY() + plant.visualHeight() + .05F;
        float xScale = client.spriteXScale();
        RenderSystem.blendAdditive();
        try {
            for (int i = 0; i < LAYERS; i++) {
                double angle = Math.toRadians(i * 360F / LAYERS
                        + client.level().gameSeconds() * (i % 2 == 0 ? 30 : -30));
                float cos = (float) Math.cos(angle) * RADIUS;
                float sin = (float) Math.sin(angle) * RADIUS;
                client.drawTextureQuad(TEXTURE,
                        x + (-cos + sin) * xScale, y - sin - cos,
                        x + (cos + sin) * xScale, y + sin - cos,
                        x + (cos - sin) * xScale, y + sin + cos,
                        x + (-cos - sin) * xScale, y - sin + cos,
                        0, texture.height(), texture.width(), texture.height(),
                        texture.width(), 0, 0, 0,
                        .1F, 1F, 1F, 1F, OPACITY);
            }
        } finally {
            RenderSystem.blendNormal();
        }
    }
}
