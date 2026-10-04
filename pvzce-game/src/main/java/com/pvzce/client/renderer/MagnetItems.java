package com.pvzce.client.renderer;

import com.pvzce.client.PvzceClient;
import com.pvzce.common.core.EntityArt;

/** Visual interpolation of a server-owned transfer; it never chooses or removes equipment. */
public final class MagnetItems {
    public static void renderWorld(PvzceClient client) { renderWorld(client, null); }
    public static void renderWorld(PvzceClient client, String surface) {
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
            float x = item.x() + (plant.visualCellX() + 0.15F - item.x()) * t;
            float planeY = item.y() + (plant.visualCellY() - item.y()) * t;
            float elevation = item.elevation() + (plant.visualHeight() + 0.4F - item.elevation()) * t;
            var position = new com.pvzce.common.level.WorldPosition(x, planeY, elevation);
            if (surface != null && !client.level().sceneBoard().surfaceBelow(position, plant.surfaceId()).equals(surface)) continue;
            float y = position.projectedY();
            // No fog test: the item is drawn on the board and the fog's own cloud is drawn over
            // it (see `ClientMechanic.WorldOverlay.renderOver`), so a bucket flying into a
            // fogged cell is hidden by the picture rather than deleted from it.
            float alpha = surface != null && !surface.equals(client.level().activeSurface()) ? .35F : 1F;
            client.drawTexture(texture, x - 0.22F, y - 0.22F, 0.44F, 0.44F, 0.55F, 1F, 1F, 1F, alpha);
        }
    }
    private MagnetItems() { }
}
