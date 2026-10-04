package com.pvzce.client.mechanic;

import com.pvzce.common.level.SceneBoard;
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
 * <p>It holds its resting pose until the server publishes a spent transition, plays its spring
 * once, then disappears. Joining a level where it is already spent cannot replay the spring.
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

    private static float terrainHeight(ClientLevel level, float x, int row) {
        return level.sceneBoard().elevationAt(SceneBoard.DEFAULT_SURFACE, x, row + .5F);
    }

    /** The rake itself; one per level instance, shared by the sync handler and the renderer. */
    private static final class RakeOverlay implements WorldOverlay {
        private int row = -1;
        private float x;
        private boolean spent;
        private ArtTarget target;
        private boolean springPending;
        private double springAt = -1;

        void apply(RakeMechanic.State state) {
            this.row = state.row();
            this.x = state.x();
            springPending |= row >= 0 && !this.spent && state.spent() && target != null;
            this.spent = state.spent();
        }

        @Override
        public void render(PvzceClient client, PvzceCamera camera) {
            if (springPending) {
                springPending = false;
                springAt = client.level().gameSeconds();
                target.play("attack");
            }
            if (row < 0 || (spent && springAt < 0)) {
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
            if (!spent) target.play(IDLE_CLIP);
            AnimationPlayback playback = animations.playback(target);
            if (playback == null || (spent && playback.isFinished())) {
                return;
            }
            playback.render(client, x,
                    row + 0.5F - EntityVisuals.anchorLift(EntityKind.PLANT)
                            + terrainHeight(client.level(), x, row),
                    EntityVisuals.baseZ(EntityKind.PLANT),
                    client.spriteXScale() * RENDER_SCALE, RENDER_SCALE);
        }
    }
}
