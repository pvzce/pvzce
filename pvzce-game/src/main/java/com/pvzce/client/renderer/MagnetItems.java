package com.pvzce.client.renderer;

import com.pvzce.client.PvzceClient;
import com.pvzce.common.core.EntityArt;

/** Visual interpolation of a server-owned transfer; it never chooses or removes equipment. */
public final class MagnetItems {
    public static void renderWorld(PvzceClient client) {
        double now = client.level().smoothLevelTicks();
        var items = client.level().magnetItems();
        items.entrySet().removeIf(e -> !client.level().entities().containsKey(e.getKey())
                || now - e.getValue().startTick() >= e.getValue().holdTicks());
        for (var item : items.values()) {
            var plant = client.level().entities().get(item.plantId());
            var texture = EntityArt.magneticTexture(item.item());
            if (plant == null || texture == null || plant.health() <= 0
                    || com.pvzce.client.mechanic.StormClientMechanic.hides(client.level())) continue;
            float t = Math.max(0F, Math.min(1F, (float) (now - item.startTick()) / Math.max(1, item.pullTicks())));
            t = 1F - (1F - t) * (1F - t);
            float x = item.x() + (plant.cellX() + 0.15F - item.x()) * t;
            float y = item.y() + (plant.cellY() + 0.4F - item.y()) * t;
            if (com.pvzce.client.mechanic.FogClientMechanic.hides(client.level(), x, y)) continue;
            client.drawTexture(texture, x - 0.22F, y - 0.22F, 0.44F, 0.44F, 0.55F, 1F, 1F, 1F, 1F);
        }
    }
    private MagnetItems() { }
}
