package com.pvzce.client.mechanic;

import com.pvzce.api.content.PortalData;
import com.pvzce.api.entity.EntityKind;
import com.pvzce.api.util.Identifier;
import com.pvzce.client.ClientLevel;
import com.pvzce.client.PvzceClient;
import com.pvzce.client.animation.AnimationManager;
import com.pvzce.client.animation.AnimationPlayback;
import com.pvzce.client.animation.ArtTarget;
import com.pvzce.client.renderer.EntityVisuals;
import com.pvzce.client.renderer.PvzceCamera;
import com.pvzce.common.PvzceIds;

import java.util.ArrayList;
import java.util.List;

/**
 * The portals of Portal Combat, on the client: a ring at each end of each pair.
 *
 * <p>The server streams the current pairs, including relocations. The first pair uses the
 * original square portal art, the remaining pairs use circles; each pair loops its forward
 * and reverse pulse so the two connected ends remain recognizable.
 *
 * <p>Drawn under the plants and zombies rather than over them: a portal is a hole in the lawn,
 * and a zombie standing in one has to be visible doing it. The overlay hook runs after the
 * terrain and before the entities, and the render layer it is given is below both.
 */
public final class PortalClientMechanic implements ClientMechanic {
    /**
     * Which animation file a portal is drawn from.
     *
     * <p>Named outright rather than looked up: a portal is not content - it has no registry
     * entry, no definition and no content id - so there is nothing for {@code EntityArt} to
     * resolve. The lawn mower is the same case and names its file the same way.
     */
    private static final Identifier PORTAL_ANIMATION = PvzceIds.id("mechanic/portal");
    private static final Identifier SQUARE_ANIMATION = PvzceIds.id("mechanic/portal_square");

    /** The phase the {@code a} end of a pair loops; the source art's forward pulse. */
    static final String CLIP_FORWARD = "idle";
    /** The phase the {@code b} end loops; the same pulse run the other way. */
    static final String CLIP_REVERSE = "pulse_reverse";

    /**
     * The render layer a portal sits on.
     *
     * <p>Between the terrain (drawn at 0) and the lowest entity layer (a zombie is at 0.15), so
     * "a ring painted on the grass that everything else stands in front of" is the layer's answer
     * rather than a second rule in the drawing code.
     */
    private static final float PORTAL_Z = 0.05F;

    @Override
    public Identifier id() {
        return PvzceIds.MECHANIC_PORTAL;
    }

    @Override
    public void applySync(ClientLevel level, com.pvzce.common.network.PacketByteBuf payload) {
        level.setMechanicState(PvzceIds.MECHANIC_PORTAL,
                com.pvzce.common.level.mechanic.PortalMechanic.State.CODEC.decode(payload));
    }

    @Override
    public WorldOverlay createWorldOverlay(ClientLevel level) {
        PortalData data = level.mechanicData(PvzceIds.MECHANIC_PORTAL, PortalData.class);
        if (data == null || data.pairs().isEmpty()) {
            // A level that names the mechanic but declares no pair has nothing to draw, and the
            // validator refuses that file anyway; answering null keeps this half from inventing
            // an empty overlay to iterate every frame.
            return null;
        }
        return new PortalOverlay(data.pairs(), level);
    }

    /** Every end of every pair, and one animation playback per end. */
    private static final class PortalOverlay implements WorldOverlay {
        /** One ring: which cell, and which of the two phases it loops. */
        private record End(int x, int y, String clip) {
        }

        private final List<End> ends = new ArrayList<>();
        private final List<ArtTarget> targets = new ArrayList<>();
        private final ClientLevel level;
        private List<PortalData.Pair> pairs;

        private PortalOverlay(List<PortalData.Pair> pairs, ClientLevel level) {
            this.level = level;
            update(pairs);
        }

        private void update(List<PortalData.Pair> pairs) {
            this.pairs = List.copyOf(pairs);
            ends.clear();
            for (PortalData.Pair pair : pairs) {
                ends.add(new End(pair.ax(), pair.ay(), CLIP_FORWARD));
                ends.add(new End(pair.bx(), pair.by(), CLIP_REVERSE));
            }
        }

        @Override
        public void render(PvzceClient client, PvzceCamera camera) {
            var state = level.mechanicStateOrNull(PvzceIds.MECHANIC_PORTAL,
                    com.pvzce.common.level.mechanic.PortalMechanic.State.class);
            if (state != null && !state.pairs().equals(pairs)) {
                update(state.pairs());
            }
            AnimationManager animations = client.animations();
            if (animations == null) {
                return;
            }
            // Built on the first frame rather than in the constructor: the manager lives on the
            // client, and an overlay is created from a level (see ClientMechanic) before there is
            // a client to ask. The same shape MowerClientMechanic's overlay uses.
            while (targets.size() < ends.size()) {
                ArtTarget created = new ArtTarget(targets.size() < 2 ? SQUARE_ANIMATION : PORTAL_ANIMATION);
                created.attach(animations);
                targets.add(created);
            }
            for (int i = 0; i < ends.size(); i++) {
                End end = ends.get(i);
                ArtTarget target = targets.get(i);
                target.play(end.clip());
                AnimationPlayback playback = animations.playback(target);
                if (playback == null) {
                    continue;
                }
                // Anchored on the ground line of its cell, the same offset plants and zombies
                // use: the converted art has the bottom of the ring at y=0, so the ring stands
                // on the row rather than floating over it.
                playback.render(client, end.x() + 0.5F,
                        end.y() + 0.5F - EntityVisuals.anchorLift(EntityKind.PLANT),
                        PORTAL_Z,
                        client.spriteXScale(), 1F);
            }
        }
    }
}
