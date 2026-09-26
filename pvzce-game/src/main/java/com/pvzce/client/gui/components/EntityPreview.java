package com.pvzce.client.gui.components;

import com.pvzce.api.util.Identifier;
import com.pvzce.client.ClientEntity;
import com.pvzce.client.PvzceClient;
import com.pvzce.common.core.EntityArt;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Live rigs drawn into small GUI boxes: the only honest picture of skeleton content.
 *
 * <p>A plant or a zombie is not a picture. Its art is a controller file whose parts are separate
 * PNGs hung off bones, so there is no single texture to crop for a list row - and asking for
 * {@code textures/entities/plant/attacker/pea_shooter.png} (which is a <em>directory</em>) is what
 * made every row of the editor's plant, zombie and card palettes draw the missing-texture
 * checkerboard. The almanac and the seed chooser have always shown these correctly, by drawing the
 * rig itself into a viewport; this is that same drawing, in a pool so a scrolling list can do it.
 *
 * <p><b>Why a pool rather than one rig per row.</b> A palette holds forty plants and thirty-six
 * zombies and shows eight of them. Every live rig costs a playback the animation manager ticks
 * every frame, so the cache is bounded: the oldest row that scrolled out of sight is released and
 * its rig rebuilt if the player scrolls back. Insertion-releases-elder is invisible at list sizes
 * and keeps the cost proportional to what is on screen.
 *
 * <p><b>It plays on the draw path, not in a tick.</b> A preview is built lazily on the frame it is
 * first drawn, and that frame's tick has already run - the almanac learned this the hard way
 * ("every zombie card is empty"). Calling {@code update} and {@code playAnimation} immediately
 * before rendering is idempotent, so it does not matter how many times a frame they run.
 */
public final class EntityPreview {
    /**
     * The world rectangle a rig is fitted into.
     *
     * <p>Under one cell on purpose: a full cell leaves the figure rattling around an empty lawn
     * square, and the almanac already draws its previews at this size.
     */
    private static final float CELLS = 0.85F;

    /** How many rigs may be alive at once; see the class note. */
    private static final int MAX_ALIVE = 24;

    /** The state a preview stands in; every rig has one, and it is what a list row should show. */
    private static final String POSE = "idle";

    private final PvzceClient client;
    private final Map<Identifier, ClientEntity> live = new LinkedHashMap<>(16, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Identifier, ClientEntity> eldest) {
            if (size() <= MAX_ALIVE) {
                return false;
            }
            release(eldest.getValue());
            return true;
        }
    };

    public EntityPreview(PvzceClient client) {
        this.client = client;
    }

    /**
     * Whether this content has a rig the client can play.
     *
     * <p>Asked by the caller <em>before</em> it decides what a row draws: content with no rig has
     * to fall back to whatever single picture it does have, and that decision belongs where the row
     * is built rather than here.
     */
    public boolean canDraw(Identifier contentId) {
        return contentId != null && client != null && client.animations() != null
                && EntityArt.animationFile(contentId) != null;
    }

    /**
     * Draws the rig into a GUI box, or answers {@code false} when there is nothing to draw.
     *
     * <p>The caller must already be in the GUI view: this switches to a world view for the box and
     * restores the GUI one on the way out, which is not optional - the world view sets the sprite
     * scale too, and the row's text is drawn in GUI space right after.
     */
    public boolean draw(Identifier contentId, String kind, float x, float y, float width, float height) {
        if (!canDraw(contentId) || width <= 0F || height <= 0F) {
            return false;
        }
        ClientEntity entity = live.get(contentId);
        if (entity == null) {
            entity = new ClientEntity(-1 - live.size(), kind, contentId.toString(),
                    CELLS / 2F, CELLS / 2F, 100, 1, POSE, 0F, "");
            entity.attachAnimationManager(client.animations());
            live.put(contentId, entity);
        }
        entity.update(CELLS / 2F, CELLS / 2F, 100, POSE, 0F);
        entity.playAnimation(POSE);
        client.clipping().push(x, y, width, height);
        try {
            client.beginOverlayWorldView(x, y, width, height, 0F, CELLS, 0F, CELLS);
            client.animations().render(entity);
        } finally {
            client.clipping().pop();
            client.beginGuiView();
        }
        return true;
    }

    /** Drops every rig; call this when the screen or page that owns the pool goes away. */
    public void release() {
        for (ClientEntity entity : live.values()) {
            release(entity);
        }
        live.clear();
    }

    private void release(ClientEntity entity) {
        if (client != null && client.animations() != null) {
            client.animations().release(entity);
        }
    }

    /** How many rigs are alive; for tests that pin the bound. */
    public int alive() {
        return live.size();
    }

    /** The ids currently held, oldest first; for tests. */
    public java.util.List<Identifier> held() {
        return java.util.List.copyOf(new java.util.ArrayList<>(live.keySet()));
    }

    /** The bound this pool enforces, so a test can assert it without restating the number. */
    public static int maxAlive() {
        return MAX_ALIVE;
    }
}
