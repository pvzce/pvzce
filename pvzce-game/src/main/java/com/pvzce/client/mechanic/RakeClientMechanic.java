package com.pvzce.client.mechanic;

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
import com.pvzce.common.level.mechanic.RakeMechanic;
import com.pvzce.common.network.PacketByteBuf;

/**
 * The rake, on the client: one sprite lying in a lane until something walks into it.
 *
 * <p>The simplest board fixture there is - no states, no movement, no clocks. It is drawn from the
 * moment the level starts (or from the first sync, whichever is later) and stops being drawn the
 * tick the server says it has sprung, which is the same tick the zombie it killed starts dying.
 *
 * <p>Drawn through the plant path rather than the mower's: the rake is a thing lying <em>on the
 * lawn</em> at a cell's ground line, exactly like a plant, so it wants the same anchor lift and the
 * same z band. The mower's own overlay draws at a plant's feet for the same reason, and the two
 * agreeing is what keeps the rake from floating a few pixels above the grass next to it.
 */
final class RakeClientMechanic implements ClientMechanic {
    private static final Identifier RAKE_ANIMATION =
            Identifier.withDefaultNamespace("mechanic/rake");
    private static final String IDLE_CLIP = "idle";
    /**
     * How big the rake is drawn.
     *
     * <p>The converted art is fitted into a one-cell box and comes out about half a cell wide,
     * which is the rake's real proportion - it is a small object. Scaled up a little so it reads
     * as an obstacle at a glance rather than as a twig: this is the one thing on the board the
     * player is meant to notice before the zombies do.
     */
    private static final float RENDER_SCALE = 1.35F;

    @Override
    public Identifier id() {
        return PvzceIds.MECHANIC_RAKE;
    }

    @Override
    public void applySync(ClientLevel level, PacketByteBuf payload) {
        overlay(level).apply(RakeMechanic.State.decode(payload));
    }

    @Override
    public WorldOverlay createWorldOverlay(ClientLevel level) {
        // An overlay is registered for every level that declares the mechanic, including one that
        // declared it and then named no lanes; the state's row is what says whether there is
        // anything to draw, and `-1` means there is not.
        return overlay(level);
    }

    private static RakeOverlay overlay(ClientLevel level) {
        return level.mechanicState(PvzceIds.MECHANIC_RAKE, RakeOverlay::new);
    }

    /** The rake itself; one per level instance, shared by the sync handler and the renderer. */
    private static final class RakeOverlay implements WorldOverlay {
        private int row = -1;
        private boolean spent;
        private ArtTarget target;

        void apply(RakeMechanic.State state) {
            this.row = state.row();
            this.spent = state.spent();
        }

        @Override
        public void render(PvzceClient client, PvzceCamera camera) {
            if (row < 0 || spent) {
                // Not this level, or already sprung. The second case is the whole life cycle: the
                // rake is drawn until the tick it kills something, and then never again.
                return;
            }
            AnimationManager animations = client.animations();
            if (animations == null) {
                return;
            }
            if (target == null) {
                target = new ArtTarget(RAKE_ANIMATION);
                target.attach(animations);
            }
            target.play(IDLE_CLIP);
            AnimationPlayback playback = animations.playback(target);
            if (playback == null) {
                return;
            }
            playback.render(client, RakeMechanic.IDLE_X + 0.5F,
                    row + 0.5F - EntityVisuals.anchorLift(EntityKind.PLANT),
                    EntityVisuals.baseZ(EntityKind.PLANT),
                    client.spriteXScale() * RENDER_SCALE, RENDER_SCALE);
        }
    }
}
