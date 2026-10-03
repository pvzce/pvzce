package com.pvzce.client.mechanic;

import com.pvzce.api.content.SeedRainData;
import com.pvzce.api.util.Identifier;
import com.pvzce.client.ClientLevel;
import com.pvzce.common.PvzceIds;

/** The original two layers of rain tiles, driven by the mirrored game clock. */
public final class SeedRainClientMechanic implements ClientMechanic {
    private static final Identifier RAIN = PvzceIds.id("textures/particles/water/rain");

    @Override
    public Identifier id() {
        return PvzceIds.MECHANIC_SEED_RAIN;
    }

    @Override
    public WorldOverlay createWorldOverlay(ClientLevel level) {
        SeedRainData data = level.mechanicData(id(), SeedRainData.class);
        if (data == null || !data.falling()) return null;
        return (client, camera) -> renderRain(client, camera, level.smoothLevelTicks());
    }

    /** Shared rain art and motion for seed rain and the night-roof forecast. */
    public static void renderRain(com.pvzce.client.PvzceClient client,
                                  com.pvzce.client.renderer.PvzceCamera camera, double ticks) {
        for (int layer = 0; layer < 2; layer++) {
            float size = layer == 0 ? 1.25F : 1.875F;
            float xOffset = (float) (ticks % (layer == 0 ? 100 : 161))
                    / (layer == 0 ? 100 : 161) * size;
            float yOffset = (float) (ticks % (layer == 0 ? 20 : 33))
                    / (layer == 0 ? 20 : 33) * size;
            for (float x = camera.worldLeft() - size; x < camera.worldRight() + size; x += size) {
                for (float y = camera.worldBottom() - size; y < camera.worldTop() + size; y += size) {
                    client.drawTexture(RAIN, x - xOffset, y - yOffset, size, size,
                            0.32F, 1F, 1F, 1F, 1F);
                }
            }
        }
    }
}
