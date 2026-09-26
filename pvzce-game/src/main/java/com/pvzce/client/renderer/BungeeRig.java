package com.pvzce.client.renderer;

import com.pvzce.api.util.Identifier;
import com.pvzce.client.ClientEntity;
import com.pvzce.client.PvzceClient;

/**
 * The bungee zombie's two props: the rope it hangs from and the target it is aiming at.
 *
 * <p>Neither is part of the zombie's own reanim - the original draws them as two objects around
 * it, and the rip keeps them as loose images (`refer/im7/images/BungeeCord.png`,
 * `BungeeTarget.png`). So this is a render-time companion, not another bone: it needs the
 * <em>level's</em> geometry (the top of the screen for the rope, the cell's ground contact for
 * the target), which a bone parented to the zombie does not know.
 *
 * <p>The rope is drawn from the zombie's hands to the top of the viewport rather than to a fixed
 * length, so it looks anchored off-screen no matter how tall the window is. It uses the entity's
 * interpolated height, which is how the descent is drawn at all: the clips are poses and the
 * server moves the body with {@code height} (see {@code BungeeCapability}).
 *
 * <p>Both props are painted before the zombie's own shadow and art, so they read as being
 * <em>behind</em> it: the target is a piece of paper on the lawn and the rope comes down from
 * above and out of the zombie's back.
 */
public final class BungeeRig {
    /** The one zombie this rig belongs to. */
    private static final String BUNGEE_ZOMBIE = "pvzce:bungee_zombie";
    private static final Identifier CORD =
            Identifier.withDefaultNamespace("textures/entities/zombie/special/bungee_rig/cord");
    private static final Identifier TARGET =
            Identifier.withDefaultNamespace("textures/entities/zombie/special/bungee_rig/target");

    /** The rope's drawn width, in cells. The source is 8px wide; at 0.01 cells/px that is thin. */
    private static final float CORD_WIDTH = 0.13F;
    /** The source tile's height/width, for repeating it without stretching. */
    private static final float CORD_TILE_ASPECT = 64F / 8F;
    /** Where the hands are above the entity's own ground contact, in cells (from its clips). */
    private static final float HAND_LIFT = 0.78F;
    /** The dart-on-paper target, at its own proportion (71x76 px). */
    private static final float TARGET_WIDTH = 0.72F;
    private static final float TARGET_HEIGHT = 0.77F;
    /**
     * Both sit just under the entity layer: a zombie's base layer is 0.15, and the ice a held
     * zombie stands in already claims 0.14. The target is under the shadow, the rope under the
     * body.
     */
    private static final float TARGET_Z = 0.115F;
    private static final float CORD_Z = 0.13F;

    private BungeeRig() {
    }

    /** Draws the rig for {@code entity}, or nothing when it is not a bungee zombie. */
    public static void render(PvzceClient client, ClientEntity entity) {
        if (client.camera() == null || !BUNGEE_ZOMBIE.equals(entity.defIdString())) {
            return;
        }
        float cellX = entity.visualCellX();
        // The same ground contact the shadow is drawn on, so the target lies on the lawn rather
        // than floating at whatever height the rope happens to be at.
        float groundY = entity.visualCellY() - EntityVisuals.anchorLift(entity.kind());
        client.drawTexture(TARGET, cellX - TARGET_WIDTH * 0.5F, groundY - TARGET_HEIGHT * 0.5F,
                TARGET_WIDTH, TARGET_HEIGHT, TARGET_Z, 1F, 1F, 1F, 1F);

        float bottom = groundY + entity.visualHeight() + HAND_LIFT;
        float top = client.camera().worldTop();
        float tile = CORD_WIDTH * CORD_TILE_ASPECT;
        for (float y = bottom; y < top; y += tile) {
            float height = Math.min(tile, top - y);
            // The repeat is sampled from the bottom of the source so a partial top tile keeps the
            // rope's thick end at the hands rather than stretching a middle slice.
            float v1 = height / tile;
            client.drawTextureRegion(CORD, 0F, 0F, 1F, v1,
                    cellX - CORD_WIDTH * 0.5F, y, CORD_WIDTH, height, CORD_Z, 1F, 1F, 1F, 1F);
        }
    }
}
