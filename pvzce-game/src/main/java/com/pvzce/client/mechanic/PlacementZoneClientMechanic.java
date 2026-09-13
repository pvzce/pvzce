package com.pvzce.client.mechanic;

import com.pvzce.api.content.PlacementZone;
import com.pvzce.api.util.Identifier;
import com.pvzce.client.ClientLevel;
import com.pvzce.client.PvzceClient;
import com.pvzce.client.renderer.PvzceCamera;
import com.pvzce.common.PvzceIds;

/**
 * The plantable area, on the client: the red line drawn at its edge.
 *
 * <p>The line is drawn from the bounds the server sent, which are the bounds it enforces, so
 * it cannot end up somewhere the rule is not. Painted over ordinary grass on purpose - a
 * tile-based answer would have to call that grass something else and would lose the lawn
 * under the line.
 */
final class PlacementZoneClientMechanic implements ClientMechanic {
    @Override
    public Identifier id() {
        return PvzceIds.MECHANIC_PLACEMENT_ZONE;
    }

    @Override
    public WorldOverlay createWorldOverlay(ClientLevel level) {
        PlacementZone zone = level.placementZone();
        if (zone.unrestricted(level.width(), level.height())) {
            // Nothing to draw: an unrestricted zone is the ordinary board, and an
            // unrestricted-looking red line around the whole lawn would say the opposite.
            return null;
        }
        return new RedLine(zone);
    }

    /** The line itself; one per level instance, holding the bounds it was made from. */
    private record RedLine(PlacementZone zone) implements WorldOverlay {
        @Override
        public void render(PvzceClient client, PvzceCamera camera) {
            int width = client.level().width();
            int height = client.level().height();
            float lineWidth = 0.06F;
            float z = 0.19F;
            for (float x : new float[]{zone.minX(), zone.maxX() + 1F}) {
                if (x <= 0F || x >= width) {
                    continue;
                }
                client.drawSolid(x - lineWidth / 2F, 0F, lineWidth, height, z, 0.85F, 0.05F, 0.05F, 0.75F);
            }
            for (float y : new float[]{zone.minY(), zone.maxY() + 1F}) {
                if (y <= 0F || y >= height) {
                    continue;
                }
                client.drawSolid(0F, y - lineWidth / 2F, width, lineWidth, z, 0.85F, 0.05F, 0.05F, 0.75F);
            }
        }
    }
}
