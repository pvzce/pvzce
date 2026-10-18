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
    private final com.pvzce.api.registry.RegistryAccess content;
    private record Key(String kind,Identifier id) {}
    private record Preview(ClientEntity entity, float[] world) {}
    private final Map<Key, Preview> live = new LinkedHashMap<>(16, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Key, Preview> eldest) {
            if (size() <= MAX_ALIVE) {
                return false;
            }
            release(eldest.getValue().entity());
            return true;
        }
    };

    public EntityPreview(PvzceClient client) {
        this(client, com.pvzce.common.core.BuiltInRegistries.ACCESS);
    }

    public EntityPreview(PvzceClient client, com.pvzce.api.registry.RegistryAccess content) {
        this.client = client;
        this.content = content;
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

    /** The square world region must have the same pixel scale on both axes. */
    static float[] previewArea(float x, float y, float width, float height) {
        float side = Math.min(width, height);
        return new float[]{x + (width - side) / 2F, y + (height - side) / 2F, side, side};
    }

    static float[] worldArea(float centerX, float centerY, float width, float height) {
        float side = Math.max(CELLS, Math.max(width, height) * 1.1F);
        return new float[]{centerX-side/2F, centerX+side/2F, centerY-side/2F, centerY+side/2F};
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
        Key key=new Key(kind,contentId);
        Preview preview = live.get(key);
        if (preview == null) {
            ClientEntity entity = new ClientEntity(-1 - live.size(), kind, contentId.toString(),
                    CELLS / 2F, CELLS / 2F, 100, 1, POSE, 0F, "");
            entity.previewCatalog(content);
            entity.attachAnimationManager(client.animations());
            entity.playAnimation(POSE);
            preview = new Preview(entity, worldArea(entity));
            live.put(key, preview);
        }
        ClientEntity entity = preview.entity();
        entity.update(CELLS / 2F, CELLS / 2F, 100, POSE, 0F);
        entity.playAnimation(POSE);
        float[] world = preview.world();
        float[] area = previewArea(x, y, width, height);
        client.clipping().push(area[0], area[1], area[2], area[3]);
        try {
            client.beginOverlayWorldView(area[0], area[1], area[2], area[3], world[0], world[1], world[2], world[3]);
            if (!client.animations().render(entity)) {
                Identifier sprite = EntityArt.sprite(contentId, kind, content);
                if (!client.hasTexture(sprite)) return false;
                var texture = client.textures().getOrLoad(sprite);
                float scale = CELLS * 0.8F / Math.max(texture.width(), texture.height());
                float w = texture.width() * scale, h = texture.height() * scale;
                client.drawTexture(sprite, (CELLS-w)/2F, (CELLS-h)/2F, w, h, 0, 1, 1, 1, 1);
            }
        } finally {
            client.clipping().pop();
            client.beginGuiView();
        }
        return true;
    }

    private float[] worldArea(ClientEntity entity) {
        var playback=client.animations().playback(entity);
        float[] bounds=null;
        if(playback instanceof com.pvzce.client.animation.ControllerPlayback controller) {
            bounds=controller.bounds(client.animations().now());
        } else if(playback!=null && playback.file() instanceof com.pvzce.client.animation.FlipbookFile flipbook) {
            float left=-flipbook.sizeX()*flipbook.anchorX(), bottom=-flipbook.sizeY()*flipbook.anchorY();
            bounds=new float[]{left,left+flipbook.sizeX(),bottom,bottom+flipbook.sizeY()};
        }
        if(bounds==null)return new float[]{0F,CELLS,0F,CELLS};
        float scale=EntityArt.renderScale(entity.defId(),content)*entity.renderScale();
        float[] anchor=client.animations().anchor(entity);
        return worldArea(anchor[0]+(bounds[0]+bounds[1])*scale/2F,
                anchor[1]+(bounds[2]+bounds[3])*scale/2F,(bounds[1]-bounds[0])*scale,(bounds[3]-bounds[2])*scale);
    }

    /** Drops every rig; call this when the screen or page that owns the pool goes away. */
    public void release() {
        for (Preview preview : live.values()) {
            release(preview.entity());
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
        return live.keySet().stream().map(Key::id).toList();
    }

    /** The bound this pool enforces, so a test can assert it without restating the number. */
    public static int maxAlive() {
        return MAX_ALIVE;
    }
}
