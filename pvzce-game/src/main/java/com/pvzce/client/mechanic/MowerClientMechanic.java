package com.pvzce.client.mechanic;

import com.pvzce.api.content.MowerData;
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
import com.pvzce.common.level.mechanic.MowerMechanic;
import com.pvzce.common.network.PacketByteBuf;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lawn mowers, on the client: the mower that waits at the left of each row and the one that
 * drives across it.
 *
 * <p>Its server half is {@code MowerMechanic}. The two share the ids, the block and the state
 * codec and nothing else: this side never decides when a mower starts, it draws what it was
 * told. The rows come from the level's mechanic block (which the server sends for the
 * implicit default too, so an ordinary level's mowers arrive without a word in its file), and
 * the positions come from {@code MechanicSyncS2C} - sent when a mower starts, while it rolls,
 * and when it is spent.
 *
 * <p>The art is the original's reanim ({@code animations/mechanic/lawn_mower.json}), played
 * through the ordinary animation manager by way of {@link ArtTarget}: a mechanic's prop has
 * no content definition for {@code EntityArt} to resolve, so the target names the file
 * itself and everything else - the clock, the model, the draw call - is shared with the
 * entities on the board.
 */
final class MowerClientMechanic implements ClientMechanic {
    /** The converted {@code LawnMower.reanim}; see {@code tools/reanim_to_pvzce_all.py}. */
    static final Identifier MOWER_ANIMATION = Identifier.withDefaultNamespace("mechanic/lawn_mower");
    /**
     * The clip a parked mower plays, and the one a rolling mower plays.
     *
     * <p>Two clips rather than one, because the source reanim's wheels turn in every frame
     * of {@code anim_normal}: exporting the whole range as a single looping clip gave a
     * mower that stood at the kerb with its wheels spinning. {@code idle} is one frozen
     * frame of the same pose; the conversion freezes it, this only picks.
     */
    private static final String MOWER_IDLE_CLIP = "idle";
    private static final String MOWER_DRIVE_CLIP = "drive";

    @Override
    public Identifier id() {
        return PvzceIds.MECHANIC_MOWER;
    }

    @Override
    public WorldOverlay createWorldOverlay(ClientLevel level) {
        MowerData data = level.mechanicData(PvzceIds.MECHANIC_MOWER, MowerData.class);
        if (data == null || data.rowsFor(level.height()).isEmpty()) {
            // A level with no mowers (Wall-nut Bowling) has nothing to draw; an unknown
            // mechanic id is handled a layer up, by ClientMechanics.
            return null;
        }
        return overlay(level);
    }

    @Override
    public void applySync(ClientLevel level, PacketByteBuf payload) {
        MowerMechanic.State state = MowerMechanic.State.CODEC.decode(payload);
        overlay(level).apply(state);
    }

    /**
     * The level's mower overlay; one per level instance, shared by the sync handler and the
     * renderer (a client mechanic is a registry entry and cannot hold it itself).
     */
    private static MowerOverlay overlay(ClientLevel level) {
        return level.mechanicState(PvzceIds.MECHANIC_MOWER,
                () -> new MowerOverlay(level.mechanicData(PvzceIds.MECHANIC_MOWER, MowerData.class),
                        level.height()));
    }

    /**
     * How close the cursor has to be to a parked mower, in cells.
     *
     * <p>Generous on purpose, and the same radius the resource drops use: the mower is a
     * small sprite standing half a cell off the board, and a long press is a deliberate
     * gesture rather than a click that has to land exactly.
     */
    private static final float MOWER_CLICK_RADIUS = 0.45F;

    /** One row's mower as this client last heard about it. */
    private record Placement(int state, float x) {
    }

    /**
     * The mowers still parked in their row, as {@code (row, x)} pairs.
     *
     * <p>What a win turns into coins (see {@code InGameScreen.claimReward}): the level pays
     * for the rows that were never needed, and this is the client's copy of where they are
     * standing, so each coin can leave from its own mower instead of from nowhere.
     */
    static List<MowerMechanic.Row> parkedMowers(ClientLevel level) {
        Object state = level.mechanicState(PvzceIds.MECHANIC_MOWER, () -> null);
        if (!(state instanceof MowerOverlay overlay)) {
            return List.of();
        }
        return overlay.parkedRows();
    }

    /**
     * Marks the parked mowers as turned into coins, and answers where they were.
     *
     * <p>Called once, by {@code InGameScreen.claimReward}. The payout already credited the
     * wallet, so this is the visible half: each mower's coin leaves from where that mower
     * is standing, and the mower itself must not still be standing there afterwards. The
     * rows have to be read <em>before</em> they are marked consumed, which is why this
     * returns them rather than just hiding them.
     *
     * <p>A consumed row is not "spent" ({@code STATE_USED}): that is a mower the zombies
     * got past, and the server said so. This one only ever existed on the client, because
     * the server stopped simulating the level when it was won.
     */
    static List<MowerMechanic.Row> consumeParkedMowers(ClientLevel level) {
        Object state = level.mechanicState(PvzceIds.MECHANIC_MOWER, () -> null);
        if (!(state instanceof MowerOverlay overlay)) {
            return List.of();
        }
        List<MowerMechanic.Row> parked = overlay.parkedRows();
        for (MowerMechanic.Row row : parked) {
            overlay.consumed.add(row.row());
        }
        return parked;
    }

    /**
     * The nearest parked mower to a world point, or {@code null}.
     *
     * <p>What the long press points at; see {@code InGameScreen.mowerHoldTick}.
     */
    static MowerMechanic.Row parkedMowerAt(ClientLevel level, float worldX, float worldY) {
        Object state = level.mechanicState(PvzceIds.MECHANIC_MOWER, () -> null);
        if (!(state instanceof MowerOverlay overlay)) {
            return null;
        }
        return overlay.parkedAt(worldX, worldY);
    }

    /** The mowers of one level: where each one is, and the animation playback per row. */
    private static final class MowerOverlay implements WorldOverlay {
        private final Map<Integer, Placement> rows = new LinkedHashMap<>();
        private final Map<Integer, ArtTarget> targets = new HashMap<>();
        /** Rows whose mower has been paid out and must no longer be drawn. */
        private final java.util.Set<Integer> consumed = new java.util.HashSet<>();

        private MowerOverlay(MowerData data, int height) {
            List<Integer> mowerRows = data == null ? List.of() : data.rowsFor(height);
            for (int row : mowerRows) {
                rows.put(row, new Placement(MowerMechanic.STATE_READY, MowerMechanic.IDLE_X));
            }
        }

        private void apply(MowerMechanic.State state) {
            for (MowerMechanic.Row row : state.rows()) {
                if (rows.containsKey(row.row())) {
                    rows.put(row.row(), new Placement(row.state(), row.x()));
                }
            }
        }

        /**
         * The parked mower nearest a world point, in row order, or {@code null}.
         *
         * <p>Used by the long press on the HUD: the player points at a mower, not at a row,
         * and the mowers stand off the left edge of the board where no cell exists - so this
         * cannot be a cell lookup, and it is deliberately not one.
         */
        private MowerMechanic.Row parkedAt(float worldX, float worldY) {
            MowerMechanic.Row best = null;
            float bestDistance = MOWER_CLICK_RADIUS * MOWER_CLICK_RADIUS;
            for (MowerMechanic.Row row : parkedRows()) {
                float dx = row.x() - worldX;
                float dy = (row.row() + 0.5F) - worldY;
                float distance = dx * dx + dy * dy;
                if (distance <= bestDistance) {
                    best = row;
                    bestDistance = distance;
                }
            }
            return best;
        }

        /** Every row whose mower has not been used, in row order. */
        private List<MowerMechanic.Row> parkedRows() {
            List<MowerMechanic.Row> parked = new java.util.ArrayList<>();
            for (Map.Entry<Integer, Placement> entry : rows.entrySet()) {
                if (entry.getValue().state() == MowerMechanic.STATE_READY) {
                    parked.add(new MowerMechanic.Row(entry.getKey(), MowerMechanic.STATE_READY,
                            entry.getValue().x()));
                }
            }
            return parked;
        }

        @Override
        public void render(PvzceClient client, PvzceCamera camera) {
            AnimationManager animations = client.animations();
            if (animations == null) {
                return;
            }
            for (Map.Entry<Integer, Placement> entry : rows.entrySet()) {
                Placement placement = entry.getValue();
                if (placement.state() == MowerMechanic.STATE_USED || consumed.contains(entry.getKey())) {
                    // Spent mowers are gone for good; that is the whole point of using one.
                    // A consumed one is gone because it has just been paid out as a coin.
                    continue;
                }
                int row = entry.getKey();
                ArtTarget target = targets.computeIfAbsent(row, key -> {
                    ArtTarget created = new ArtTarget(MOWER_ANIMATION);
                    created.attach(animations);
                    return created;
                });
                target.play(placement.state() == MowerMechanic.STATE_ROLLING
                        ? MOWER_DRIVE_CLIP : MOWER_IDLE_CLIP);
                AnimationPlayback playback = animations.playback(target);
                if (playback == null) {
                    continue;
                }
                // Anchored where a plant in this row stands: the converted art has the ground
                // line at y=0, so the row's feet offset is the same one plants and zombies use.
                playback.render(client, placement.x(),
                        row + 0.5F - EntityVisuals.anchorLift(EntityKind.PLANT),
                        EntityVisuals.baseZ(EntityKind.PLANT), client.spriteXScale());
            }
        }
    }
}
