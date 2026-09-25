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
        // Straight into the level's own state slot, which is also where the accessors below read
        // it from: the overlay is created once per level and holds what it was made with, so this
        // is what the next frame's overlay sees.
        level.setMechanicState(PvzceIds.MECHANIC_FOG, FogMechanic.Wire.decode(payload));
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
        FogMechanic.Wire synced = wire(level);
        if (synced != null) {
            return synced.data();
        }
        return level.mechanicData(PvzceIds.MECHANIC_FOG, FogData.class);
    }

    /** The lamps the server last reported, or an empty list. */
    public static java.util.List<FogMechanic.Reveal> revealsOf(ClientLevel level) {
        FogMechanic.Wire synced = wire(level);
        return synced == null ? java.util.List.of() : synced.reveals();
    }

    private static FogMechanic.Wire wire(ClientLevel level) {
        return level.mechanicStateOrNull(PvzceIds.MECHANIC_FOG, FogMechanic.Wire.class);
    }

    /**
     * True when something standing at this column is inside the part of the fog that hides it.
     *
     * <p>Asked per entity by the board's render loop. An entity is judged by its own column and
     * vanishes the moment it crosses the line - no fade, because a zombie the player can half see
     * is a zombie the player will argue about. What walks in from the right does the opposite: it
     * appears at the line, fully drawn.
     */
    public static boolean hides(ClientLevel level, float x, float y) {
        FogData fog = fogOf(level);
        if (fog == null || fog.maxAlpha() <= 0F) {
            return false;
        }
        // The same fold the drawing uses, through the same method on `FogMechanic`: a lamp has to
        // light the *view* and the *hiding test* together, or a zombie stands in a lit circle and
        // is not drawn.
        return FogMechanic.alphaAt(fog, revealsOf(level), x, y)
                >= fog.maxAlpha() * HIDE_FRACTION;
    }

    /**
     * How dark a cell has to be before what is standing there is not drawn.
     *
     * <p>Expressed as a fraction of the fog's own ceiling, so a level that lightens its fog
     * lightens what can be seen through it too, and a lamp's hole is judged by the same rule
     * everywhere on the board. Three quarters, and not 1.0: a fully opaque pixel still shows a
     * silhouette, and hiding exactly at "cannot see anything" would leave a band where a zombie is
     * half-visible.
     */
    public static final float HIDE_FRACTION = FogData.HIDE_FRACTION;


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

            java.util.List<FogMechanic.Reveal> lamps = revealsOf(client.level());
            if (!lamps.isEmpty()) {
                // A lamp has to take a *hole* out of the fog, and a hole is not expressible as a
                // shorter gradient. So the fog is drawn as a grid of small quads whose alpha is
                // sampled where each one is - the only shape this renderer can build a circle out
                // of. Only while a lamp is actually standing: the ordinary case keeps the smooth
                // texture, because banding is the price and most boards should not pay it.
                renderGrid(client, lamps, width, height);
                return;
            }

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

        /**
         * The fog as a grid, for when a lamp is standing in it.
         *
         * <p>One eighth of a cell across and half a cell down. The horizontal step is the fine one
         * because the main ramp runs that way and its stepping is what the eye would catch; the
         * vertical step only shows around a lamp, where a slightly stepped glow reads as a glow.
         * Cells the fog does not reach at all are skipped, so an ordinary morning board with one
         * lamp on it draws a few dozen quads rather than the whole grid.
         */
        private void renderGrid(PvzceClient client, java.util.List<FogMechanic.Reveal> lamps,
                                int width, int height) {
            float stepX = 1F / 8F;
            float stepY = 0.5F;
            float right = width + RIGHT_MARGIN_CELLS;
            for (float x = 0F; x < right; x += stepX) {
                float centreX = x + stepX / 2F;
                for (float y = -VERTICAL_MARGIN_CELLS; y < height + VERTICAL_MARGIN_CELLS;
                        y += stepY) {
                    float centreY = y + stepY / 2F;
                    float alpha = FogMechanic.alphaAt(data, lamps, centreX, centreY);
                    if (alpha <= 0.004F) {
                        continue;
                    }
                    client.drawSolid(x, y, stepX, stepY, Z, 0F, 0F, 0F, alpha);
                }
            }
        }
    }
}
