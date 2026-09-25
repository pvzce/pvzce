package com.pvzce.client.mechanic;

import com.pvzce.api.content.FogData;
import com.pvzce.api.util.Identifier;
import com.pvzce.client.ClientLevel;
import com.pvzce.client.PvzceClient;
import com.pvzce.client.renderer.PvzceCamera;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.level.mechanic.FogMechanic;
import com.pvzce.common.network.PacketByteBuf;

/**
 * World 4's fog, on the client: the dark right-hand side of the board.
 *
 * <p>Two jobs, and they are the two halves of "you cannot see what is over there":
 *
 * <ul>
 *   <li><b>Draw the darkening.</b> A gradient quad from the fog's start column to its end, then
 *       flat darkness to the right edge. The engine has no per-vertex colour
 *       ({@code SpriteRenderer.textured} takes one tint for all four corners) and no matrix stack
 *       to build a ramp out of, so the ramp is a <em>texture</em> whose alpha goes 0 to 255 across
 *       its width - {@code assets/pvzce/textures/gui/screen/fog_alpha.png}, baked by
 *       {@code tools/gen_fog_gradient.py} from the same curve {@link FogData#alphaAt} uses.</li>
 *   <li><b>Hide what is inside it.</b> An entity past {@link FogData#hidingColumn()} is not drawn
 *       at all. This is the half the player actually asked for - "a zombie is only drawn once it
 *       walks into view" - and it is why the fog is not merely a translucent sheet over
 *       everything: a black rectangle over a fully drawn lawn still shows you the silhouette you
 *       are not supposed to have.</li>
 * </ul>
 *
 * <p>The span comes from the server (the level's block, a mutation, or the fog-retreat buff) and
 * the lamps come from the plants the client is already drawing, so nothing here re-derives either.
 *
 * <h2>What is deliberately not hidden</h2>
 *
 * <p>The lawn itself, the mowers, the card bar and the sun. Hiding the terrain would mean the fog
 * was a change to the board rather than a change to the view, and hiding the HUD would be hiding
 * the player's own hand. Only the things that <em>move</em> - zombies, projectiles, plants, drops -
 * are subject to it, which is also what makes the boundary legible: a plant that vanishes into the
 * dark is the message.
 */
public final class FogClientMechanic implements ClientMechanic {
    /**
     * How far past the board's right edge the flat darkness is drawn, in cells.
     *
     * <p>A zombie spawns at {@code width + 0.6} and walks in; without a margin the fog would stop
     * exactly at the last column and a zombie would be visible one tick before it steps onto the
     * board. One and a half cells covers the spawn point and the walk-in.
     */
    private static final float RIGHT_MARGIN_CELLS = 1.5F;
    /** Vertical overdraw, in cells, so the fog reaches past the top and bottom rows. */
    private static final float VERTICAL_MARGIN_CELLS = 0.6F;
    /** Above the board and below the HUD; between the lawn's own layers and the cards. */
    private static final float Z = 0.30F;

    private static final Identifier GRADIENT =
            Identifier.withDefaultNamespace("textures/gui/screen/fog_alpha");

    @Override
    public Identifier id() {
        return PvzceIds.MECHANIC_FOG;
    }

    @Override
    public void applySync(ClientLevel level, PacketByteBuf payload) {
        // Straight into the level's own state slot, which is also where `fogOf` reads it from: the
        // overlay is created once per level and holds the span it was made with, so this is what
        // the next frame's overlay sees.
        level.setMechanicState(PvzceIds.MECHANIC_FOG,
                FogMechanic.Wire.decode(payload).data());
    }

    @Override
    public WorldOverlay createWorldOverlay(ClientLevel level) {
        FogData fog = fogOf(level);
        if (fog == null || fog.maxAlpha() <= 0F || fog.endColumn() <= fog.startColumn()) {
            // No fog, or a span that draws nothing. Registering an overlay anyway would make every
            // ordinary level pay for a feature it does not use.
            return null;
        }
        return new Fog(fog);
    }

    /**
     * The fog this level has right now.
     *
     * <p>What the server last synced, falling back to the level's own block before the first sync
     * arrives - a level that declares fog must not be drawn clear for the frame or two between the
     * level starting and its first mechanic sync.
     */
    public static FogData fogOf(ClientLevel level) {
        FogData synced = level.mechanicStateOrNull(PvzceIds.MECHANIC_FOG, FogData.class);
        if (synced != null) {
            return synced;
        }
        return level.mechanicData(PvzceIds.MECHANIC_FOG, FogData.class);
    }

    /**
     * True when something standing at this column is inside the part of the fog that hides it.
     *
     * <p>Asked per entity by the board's render loop. An entity is judged by its own column and
     * vanishes the moment it crosses the line - no fade, because a zombie the player can half see
     * is a zombie the player will argue about. What walks in from the right does the opposite: it
     * appears at the line, fully drawn.
     */
    public static boolean hides(ClientLevel level, float column) {
        FogData fog = fogOf(level);
        return fog != null && fog.maxAlpha() > 0F && column > fog.hidingColumn();
    }

    /** The darkening itself; one per level instance, holding the span it was made from. */
    private record Fog(FogData data) implements WorldOverlay {
        @Override
        public void render(PvzceClient client, PvzceCamera camera) {
            int width = client.level().width();
            int height = client.level().height();
            float alpha = data.maxAlpha();
            float bottom = -VERTICAL_MARGIN_CELLS;
            float top = height + VERTICAL_MARGIN_CELLS;
            float span = top - bottom;

            float darkFrom = Math.min(data.endColumn(), width + RIGHT_MARGIN_CELLS);
            float rampFrom = Math.min(data.startColumn(), darkFrom);
            if (darkFrom > rampFrom) {
                // The ramp: the gradient sprite's own alpha goes 0 at its left edge to 1 at its
                // right, so the tint's alpha is the fog's ceiling and the texture supplies the
                // shape. The two must agree with FogData.alphaAt or the boundary is drawn at a
                // different place than the hiding test uses - see the class doc.
                client.drawTexture(GRADIENT, rampFrom, bottom, darkFrom - rampFrom, span, Z,
                        0F, 0F, 0F, alpha);
            }
            if (width + RIGHT_MARGIN_CELLS > darkFrom) {
                // Flat darkness past the ramp's end, so a zombie walking in is behind solid fog
                // until it reaches the boundary rather than being drawn on bare backdrop.
                client.drawSolid(darkFrom, bottom, width + RIGHT_MARGIN_CELLS - darkFrom, span, Z,
                        0F, 0F, 0F, alpha);
            }
        }
    }
}
