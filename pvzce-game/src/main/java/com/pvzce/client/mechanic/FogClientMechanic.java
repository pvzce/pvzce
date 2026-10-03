package com.pvzce.client.mechanic;

import com.pvzce.api.content.FogData;
import com.pvzce.api.util.Identifier;
import com.pvzce.client.ClientLevel;
import com.pvzce.client.PvzceClient;
import com.pvzce.client.renderer.FogCloud;
import com.pvzce.client.renderer.PvzceCamera;
import com.pvzce.common.PvzceConstants;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.level.mechanic.FogMechanic;
import com.pvzce.common.network.PacketByteBuf;

import java.util.List;

/**
 * World 4's fog, on the client: the cloud sitting over the right-hand side of the board.
 *
 * <p>One job, and it is the whole of "you cannot see what is over there": <b>draw the cloud on
 * top of the board.</b> The grid itself is {@link FogCloud}'s - one 210x190 frame of the sheet per
 * cell, at the original's own spacing - and what this class owns is which fog the level has right
 * now and how the picture travels when it is blown away.
 *
 * <h2>What the cloud covers, and what it deliberately does not</h2>
 *
 * <p>Everything standing on the board: zombies, plants, projectiles, drops, the mower rig. The
 * fog is an overlay drawn <em>after</em> them ({@link WorldOverlay#renderOver}), so a zombie deep
 * in it is a silhouette rather than a deleted entity - which is the original's own arrangement and
 * what the player asked for ("the zombies in the fog should not simply vanish, they should be
 * hidden by the fog"). It used to be the other way round: the cloud was drawn under the entities
 * and an entity past a hiding column was skipped outright, so a zombie popped in and out at a line
 * the player could not see.
 *
 * <p>The lawn, the mowers, the card bar and the sun are not covered: hiding the terrain would make
 * the fog a change to the board rather than a change to the view, and hiding the HUD would be
 * hiding the player's own hand. The player's own gesture - the seed ghost in hand, the placement
 * tint, the mallets - is drawn over the cloud for the same reason.
 *
 * <p>The span comes from the server (the level's block, a mutation, or the fog-retreat buff) and
 * the lamps come from the plants the client is already drawing, so nothing here re-derives either.
 *
 * <h2>How the picture moves</h2>
 *
 * <p>The server publishes the span it wants, and this class slides the cloud toward it instead of
 * jumping: the whole band travels right when a blover blows the fog away, and drifts back when the
 * level's clock runs the clear period out. The movement is the original's {@code mFogOffset} - one
 * offset for the whole grid - and the two durations are this build's own: {@link #BLOW_SECONDS} out
 * and {@link #RETURN_SECONDS} back, against the original's single 2000-update slide.
 */
public final class FogClientMechanic implements ClientMechanic {
    /** Above the board and below the HUD; the storm's own layer, since no level has both. */
    private static final float Z = 0.30F;

    /**
     * How long the cloud takes to sweep clear, in seconds.
     *
     * <p>The gust is a gesture, so it is quick: a second and a bit of movement reads as the wind
     * taking the fog, where the instant jump this used to do read as the fog being switched off.
     */
    public static final float BLOW_SECONDS = 1.2F;

    /**
     * How long the cloud takes to drift back, in seconds.
     *
     * <p>Deliberately much slower than the blow. The server keeps the fog away for
     * {@code BLOVER_FOG_CLEAR_TICKS} and then ramps the span home over
     * {@code BLOVER_FOG_RETURN_TICKS}, and this is that same duration: the cloud creeps back in
     * rather than snapping, which is what the player asked for ("the fog does not come back in an
     * instant either").
     */
    public static final float RETURN_SECONDS =
            PvzceConstants.BLOVER_FOG_RETURN_TICKS / (float) PvzceConstants.TICKS_PER_SECOND;

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

    /**
     * The cloud, when this level has fog that draws anything.
     *
     * <p>The span the overlay is built with is the level's own block, not the last sync: the block
     * is what the picture is a picture <em>of</em>, and how far the current span has been pushed
     * away from it is the slide the overlay animates.
     */
    @Override
    public WorldOverlay createWorldOverlay(ClientLevel level) {
        FogData declared = level.mechanicData(PvzceIds.MECHANIC_FOG, FogData.class);
        FogData live = fogOf(level);
        FogData base = declared != null ? declared : live;
        if (base == null || live == null || base.maxAlpha() <= 0F || live.maxAlpha() <= 0F
                || base.endColumn() <= base.startColumn()) {
            // No fog, or a span that draws nothing. Registering an overlay anyway would make every
            // ordinary level pay for a feature it does not use.
            return null;
        }
        return new Fog(base);
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
    public static List<FogMechanic.Reveal> revealsOf(ClientLevel level) {
        FogMechanic.Wire synced = wire(level);
        return synced == null ? List.of() : synced.reveals();
    }

    private static FogMechanic.Wire wire(ClientLevel level) {
        return level.mechanicStateOrNull(PvzceIds.MECHANIC_FOG, FogMechanic.Wire.class);
    }

    /**
     * A level's own fog block, read off a level payload.
     *
     * <p>For the screens that draw a board <em>before</em> there is a level to ask - the seed
     * chooser, which is where the player meets the fog for the first time and which used to show
     * them a clear lawn. The payload carries the same blocks the running level gets, so this is a
     * read of what the level declared rather than a second copy of it.
     */
    public static FogData declaredIn(
            List<com.pvzce.common.network.packet.LevelPayload.MechanicPayload> mechanics) {
        if (mechanics == null) {
            return null;
        }
        for (var payload : mechanics) {
            if (!PvzceIds.MECHANIC_FOG.equals(payload.type())) {
                continue;
            }
            return com.pvzce.common.level.mechanic.LevelMechanics
                    .decodeBlock(payload.type(), payload.block())
                    .filter(FogData.class::isInstance)
                    .map(FogData.class::cast)
                    .orElse(null);
        }
        return null;
    }

    /**
     * True when the cloud is drawn over this point, so what stands there cannot be made out.
     *
     * <p>For the layers that cannot simply be covered by the cloud because they are drawn after
     * it: a HUD label pinned to a plant in the fog would tell the player what the fog is there to
     * hide (see {@code EchoLilyLinks}' host label). The board's own entities are not asked - they
     * are drawn under the cloud, which is what hiding them means now.
     */
    public static boolean covers(ClientLevel level, float x, float y) {
        FogData fog = fogOf(level);
        if (fog == null || fog.maxAlpha() <= 0F) {
            return false;
        }
        return FogMechanic.alphaAt(fog, revealsOf(level), x, y) > 0F;
    }

    /** The longest frame gap the slide will believe, in ticks; see {@link #slideStep}. */
    private static final double MAX_SLIDE_STEP_TICKS = 20D;

    /**
     * One step of the cloud's slide: where the band's near edge lands after {@code elapsedTicks}.
     *
     * <p>Pure arithmetic, and public because that is the only half of the animation a test can
     * reach - the other half is the quads it moves. The rate is the span's own width over
     * {@link #BLOW_SECONDS} or {@link #RETURN_SECONDS}, so a level whose fog is five columns deep
     * takes as long to clear as one whose fog is two; a frame gap longer than
     * {@code MAX_SLIDE_STEP_TICKS} is clamped rather than allowed to finish the whole sweep in one
     * step, which is what a pause or a level reload would otherwise do.
     */
    public static float slideStep(float shown, float target, float span, double elapsedTicks) {
        if (elapsedTicks <= 0D || shown == target) {
            return shown;
        }
        float seconds = target > shown ? BLOW_SECONDS : RETURN_SECONDS;
        float step = Math.max(0.0001F, span) / (seconds * PvzceConstants.TICKS_PER_SECOND)
                * (float) Math.min(elapsedTicks, MAX_SLIDE_STEP_TICKS);
        float difference = target - shown;
        return shown + Math.max(-step, Math.min(step, difference));
    }

    /**
     * The cloud over one level's board, and the slide that carries it there.
     *
     * <p>One per level instance, holding the band's own span and where its near edge is drawn
     * right now. The slide is a rate rather than a tween: the server moves its span in ten-tick
     * steps (and ramps it home over the return period), so an animation that restarted on every
     * sync would stutter, while a rate-limited follower tracks all of it and still turns the
     * blover's instant jump into a sweep.
     */
    private static final class Fog implements WorldOverlay {
        /** The span the picture is a picture of; the slide is measured against this. */
        private final FogData base;
        /** Where the band's near edge is drawn, in cells; the server's span is the target. */
        private float shownStart;
        /** The clock the last step ran at; see {@code ClientLevel.smoothLevelTicks}. */
        private double lastTick;
        private boolean started;

        private Fog(FogData base) {
            this.base = base;
            this.shownStart = base.startColumn();
        }

        /** Nothing under the entities: the cloud is what covers them; see {@link #renderOver}. */
        @Override
        public void render(PvzceClient client, PvzceCamera camera) {
        }

        @Override
        public void renderOver(PvzceClient client, PvzceCamera camera) {
            ClientLevel level = client.level();
            FogData target = fogOf(level);
            if (target == null) {
                return;
            }
            advance(level, target);
            FogCloud.render(client, 0F, 0F, 1F, 1F, level.width(), level.height(),
                    base, revealsOf(level), client.renderTimeSeconds(),
                    shownStart - base.startColumn(), Z);
        }

        /**
         * Moves the band's near edge toward the span the server wants; see {@link #slideStep}.
         *
         * <p>The step is measured against the span's own width so that a level whose fog is five
         * columns deep does not come back faster than one whose fog is two.
         */
        private void advance(ClientLevel level, FogData target) {
            double now = level.smoothLevelTicks();
            double elapsed = started ? now - lastTick : 0D;
            started = true;
            lastTick = now;
            shownStart = slideStep(shownStart, target.startColumn(),
                    base.endColumn() - base.startColumn(), elapsed);
        }
    }
}
