package com.pvzce.client.gui.hud.cardbar;

import com.pvzce.api.util.Identifier;
import com.pvzce.client.PvzceClient;
import com.pvzce.common.network.packet.SlotInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A conveyor belt's card bar: the original's tread surface along the top of the screen,
 * with the cards riding on it.
 *
 * <p>Moved out of {@code InGameScreen} unchanged. Top rather than bottom because that is
 * where it is in the original, and because the bottom of a belt level has nothing else to
 * hold: no sun bank, no shovel bank and no seed chooser behind it.
 *
 * <p>Cards travel instead of appearing in place, so their positions are client state keyed
 * by card id - the server's belt is a queue, and drawing it as one is presentation. A card
 * that has been spent is simply no longer in the list, which is why the positions are
 * pruned against the live set every tick.
 */
public final class BeltCardBar implements CardBar {
    /** The tray that frames the belt, and the tread surface inside it. */
    private static final Identifier BELT_BACKDROP =
            Identifier.withDefaultNamespace("textures/gui/hud/conveyor_belt_backdrop");
    private static final Identifier BELT_TREADS =
            Identifier.withDefaultNamespace("textures/gui/hud/conveyor_belt");
    /** Native size of the tray frame, for slicing it without stretching the bevel. */
    private static final float BELT_BACKDROP_WIDTH = 516F;
    private static final float BELT_BACKDROP_HEIGHT = 86F;
    /**
     * How much of the tray the frame takes, in GUI pixels.
     *
     * <p>Drawn at the art's own proportions rather than scaled: the belt tray is much
     * smaller than the 516-pixel original, so a proportional frame would be a hairline.
     * These widths are what makes the bevel read at this size, and they are also the inset
     * the tread surface is clipped to - the belt sits <em>inside</em> the frame instead of
     * covering it.
     */
    private static final float BELT_FRAME_X = 6F;
    private static final float BELT_FRAME_Y = 4F;
    /**
     * The slice of the belt art that repeats, and its aspect (498x96).
     *
     * <p>The treads are periodic every six pixels <em>inside</em> a one-pixel border, so
     * tiling the whole 502-pixel image would scroll a two-pixel seam past the player every
     * few seconds. Sampling only the repeating middle makes the scroll seamless.
     */
    private static final float BELT_U0 = 1F / 502F;
    private static final float BELT_U1 = 499F / 502F;
    private static final float BELT_TILE_ASPECT = 498F / 96F;
    /**
     * How fast the belt - and the cards on it - travel left, in GUI pixels per second.
     *
     * <p>One number for both, because they are one surface: the treads scroll under the
     * cards, so a card that moved at a different speed from the belt it is standing on
     * would look like it was sliding on ice.
     */
    private static final float BELT_SPEED = 70F;
    /** Inner padding of the belt tray, so the cards sit on it rather than cover it. */
    private static final float BELT_PADDING = 8F;
    /** Gap between the top of the window and the belt tray. */
    private static final int BELT_TOP_MARGIN = 6;
    /** Smallest belt card, in GUI pixels; below this the icon stops being readable. */
    private static final int BELT_MIN_CARD_HEIGHT = 34;
    /** The SunBank is HUD, not a card. */
    private static final String SUN_CARD_ID = com.pvzce.common.PvzceIds.SUN.toString();

    private final Host host;
    private int viewportX;
    private int viewportY;
    private int viewportWidth;
    private int viewportHeight;
    private int cardWidth = 44;
    private int cardHeight = 62;
    private int cardGap = 4;
    /** Width of the tray's card track, capacity cards wide. */
    private float trackWidth;
    /** Where each card is drawn right now, in GUI pixels, by card id. */
    private final Map<Integer, Float> cardX = new HashMap<>();
    /** Phase of the tread pattern. */
    private float scrollOffset;
    private long animNanos;

    public BeltCardBar(Host host) {
        this.host = host;
    }

    @Override
    public List<SlotInfo> slots() {
        // Belt order *is* the information: the card on the left has been waiting longest
        // and is the one the next delivery will sit behind. The chooser's "resources, then
        // plants, then tools" grouping would shuffle a queue.
        List<SlotInfo> belt = new ArrayList<>();
        for (SlotInfo slot : host.client().level().slots()) {
            if (!SUN_CARD_ID.equals(slot.defId())) {
                belt.add(slot);
            }
        }
        return belt;
    }

    @Override
    public void tick() {
        // Advanced here rather than in render(): the click that picks a card is dispatched
        // before this frame's render, and it has to hit the card where the player last saw
        // it.
        advance(slots(), System.nanoTime());
    }

    @Override
    public void render() {
        PvzceClient client = host.client();
        List<SlotInfo> slots = slots();
        updateLayout();
        long now = System.nanoTime();
        advance(slots, now);

        float trayX = viewportX - BELT_PADDING;
        float trayY = viewportY - BELT_PADDING;
        float trayWidth = trackWidth;
        float trayHeight = cardHeight + BELT_PADDING * 2F;

        // Frame first, then the tread surface *inside* it: the tray is the original's
        // moulding and the belt is what runs within it. Filling the whole tray with treads
        // buried the frame, which is the one part that says "this is a belt and not a black
        // box".
        com.pvzce.client.gui.components.NinePatch.drawNineSlice(client, BELT_BACKDROP,
                trayX, trayY, trayWidth, trayHeight, 0.05F,
                BELT_BACKDROP_WIDTH, BELT_BACKDROP_HEIGHT,
                BELT_FRAME_X, BELT_FRAME_X, BELT_FRAME_Y, BELT_FRAME_Y, 1F, 1F, 1F, 1F);
        float beltX = trayX + BELT_FRAME_X;
        float beltY = trayY + BELT_FRAME_Y;
        float beltWidth = Math.max(1F, trayWidth - BELT_FRAME_X * 2F);
        float beltHeight = Math.max(1F, trayHeight - BELT_FRAME_Y * 2F);

        // Clipped to the *inner* rectangle, not to the tray: the treads are tiled from a
        // phase that does not land on the frame's edge, so the first and last tile stick out
        // past the belt's ends and would paint over the tray's left and right moulding - the
        // one part of the frame that the scrolling surface must not cover.
        client.clipping().push((int) beltX, (int) beltY,
                (int) Math.ceil(beltWidth), (int) Math.ceil(beltHeight));
        try {
            // The treads scroll left at the same speed the cards travel, so a card looks
            // like it is being carried rather than sliding on ice. Tiled at the repeating
            // slice's own aspect so the treads keep their shape at any card size.
            float tile = Math.max(16F, beltHeight * BELT_TILE_ASPECT);
            float offset = scrollOffset % tile;
            for (float x = beltX - offset; x < beltX + beltWidth; x += tile) {
                client.drawTextureRegion(BELT_TREADS, BELT_U0, 0F, BELT_U1, 1F,
                        x, beltY, tile, beltHeight, 0.08F, 1F, 1F, 1F, 1F);
            }
            for (SlotInfo slot : slots) {
                Float x = cardX.get(slot.index());
                if (x == null) {
                    continue;
                }
                CardPainter.draw(client, slot, x, viewportY, cardWidth, cardHeight, 1F,
                        host.selectedCardIndex() == slot.index());
            }
        } finally {
            client.clipping().pop();
        }
    }

    @Override
    public int slotAt(double guiX, double guiY) {
        List<SlotInfo> slots = slots();
        if (slots.isEmpty()) {
            return -1;
        }
        updateLayout();
        // Belt cards are hit where they are drawn, not where their slot is: they are still
        // travelling when the player reaches for one.
        for (SlotInfo slot : slots) {
            Float x = cardX.get(slot.index());
            if (x == null) {
                continue;
            }
            if (guiX >= x && guiX < x + cardWidth
                    && guiY >= viewportY && guiY < viewportY + cardHeight) {
                return slot.index();
            }
        }
        return -1;
    }

    @Override
    public boolean contains(double guiX, double guiY) {
        List<SlotInfo> slots = slots();
        if (slots.isEmpty()) {
            return false;
        }
        updateLayout();
        return guiX >= viewportX - BELT_PADDING && guiX <= viewportX + trackWidth
                && guiY >= viewportY - BELT_PADDING && guiY <= viewportY + cardHeight + BELT_PADDING;
    }

    @Override
    public boolean scroll(double guiX, double guiY, double amount) {
        // A belt is not scrolled: everything on it is on screen by construction.
        return false;
    }

    @Override
    public int cardHeight() {
        return cardHeight;
    }

    /**
     * Moves the tread pattern and every card one frame's worth to the left.
     *
     * <p>Cards travel at the belt's speed and stop at the end of the queue, so a delivery is
     * something the player watches arrive instead of a card that blinks into place - and a
     * spent card leaves a gap that the ones behind it visibly close.
     */
    private void advance(List<SlotInfo> slots, long now) {
        if (animNanos == 0L) {
            animNanos = now;
        }
        float dt = Math.min(0.1F, (now - animNanos) / 1_000_000_000F);
        animNanos = now;
        scrollOffset += BELT_SPEED * dt;

        // Where a card enters: just off the right end of the track, so it slides on rather
        // than materialising at the last slot when the belt is nearly full.
        float entryX = viewportX + trackWidth - BELT_PADDING * 2F - cardWidth + cardGap;
        float travel = BELT_SPEED * dt;
        for (int i = 0; i < slots.size(); i++) {
            SlotInfo slot = slots.get(i);
            float target = viewportX + i * (cardWidth + cardGap);
            Float current = cardX.get(slot.index());
            float x = current == null ? Math.max(target, entryX) : current;
            if (x > target) {
                x = Math.max(target, x - travel);
            } else if (x < target) {
                x = Math.min(target, x + travel);
            }
            cardX.put(slot.index(), x);
        }
        if (cardX.size() > slots.size()) {
            java.util.Set<Integer> live = new java.util.HashSet<>();
            for (SlotInfo slot : slots) {
                live.add(slot.index());
            }
            cardX.keySet().retainAll(live);
        }
    }

    /**
     * Lays out the belt: a centred tray at the top of the window, as wide as the belt's
     * capacity rather than as wide as what happens to be on it right now.
     *
     * <p>The tray shares the top row with the pause and speed buttons, so on a narrow window
     * it is the <em>cards</em> that give way: they shrink (down to
     * {@link #BELT_MIN_CARD_HEIGHT}) until the tray fits to the left of them. The
     * alternative - a tray that is always six full-size cards wide - is drawn under the
     * pause button on the default window, which reads as a rendering bug rather than as a
     * belt.
     */
    private void updateLayout() {
        PvzceClient client = host.client();
        int guiH = client.guiHeight();
        float right = host.rightBound();
        float available = Math.max(120F, right - BELT_PADDING * 2F - 4F);
        // Two belts, one drawing: a level may declare one (a block in its definition the client
        // can read) or a mutation may install one mid-run (no block at all - it is not in the
        // definition), and the packet carries that one's capacity. Without this the tray of a
        // mutation's belt was drawn one card wide, because a missing block answered "capacity 1".
        int capacity = mutationBeltCapacity(client);
        if (capacity <= 0) {
            com.pvzce.api.content.LevelBelt belt = client.level().mechanicData(
                    com.pvzce.common.PvzceIds.MECHANIC_CONVEYOR, com.pvzce.api.content.LevelBelt.class);
            capacity = belt == null ? 1 : belt.capacity();
        }
        capacity = Math.max(1, capacity);
        cardHeight = Math.max(52, Math.min(70, guiH / 7));
        cardWidth = Math.max(38, Math.round(cardHeight * 100F / 140F));
        cardGap = Math.max(3, cardWidth / 8);
        trackWidth = trackWidth(capacity);
        while (trackWidth > available && cardHeight > BELT_MIN_CARD_HEIGHT) {
            cardHeight--;
            cardWidth = Math.max(18, Math.round(cardHeight * 100F / 140F));
            cardGap = Math.max(2, cardWidth / 8);
            trackWidth = trackWidth(capacity);
        }
        // Centred in the room left of the buttons, never off the left edge.
        viewportX = Math.round(Math.max(BELT_PADDING + 4F, (right - trackWidth) / 2F + BELT_PADDING));
        viewportY = Math.round(guiH - cardHeight - BELT_TOP_MARGIN - BELT_PADDING * 2F);
        viewportWidth = Math.max(1, Math.round(trackWidth - BELT_PADDING * 2F));
        viewportHeight = cardHeight;
    }

    /** The capacity a mutation's belt reported, or 0 when no mutation is dealing the cards. */
    private static int mutationBeltCapacity(PvzceClient client) {
        com.pvzce.common.network.packet.MutationStateS2C mutations = client.level().mutations();
        return mutations == null ? 0 : mutations.beltCapacity();
    }

    /** Width of a tray holding {@code capacity} cards, padding included. */
    private float trackWidth(int capacity) {
        return capacity * (cardWidth + cardGap) - cardGap + BELT_PADDING * 2F;
    }
}
