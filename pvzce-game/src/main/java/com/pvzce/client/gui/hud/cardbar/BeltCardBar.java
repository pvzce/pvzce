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
    /**
     * The gap between the tray's right edge and the cards kept outside it.
     *
     * <p>Wider than the card gap on purpose: the tray's moulding already reads as an edge, and
     * the kept cards have to look like they belong to the bar rather than to the belt.
     */
    private static final float SIDE_GAP = 10F;
    /** Where each card is drawn right now, in GUI pixels, by card id. */
    private final Map<Integer, Float> cardX = new HashMap<>();
    /** Phase of the tread pattern. */
    private float scrollOffset;
    private long animNanos;

    public BeltCardBar(Host host) {
        this.host = host;
    }

    @Override
    public List<SlotInfo> toolSlots() {
        // From the side, not from `slots()`: the belt deals plants and nothing else.
        return split(host.client().level().slots()).tools();
    }

    @Override
    public List<SlotInfo> slots() {
        // Belt order *is* the information: the card on the left has been waiting longest
        // and is the one the next delivery will sit behind. The chooser's "resources, then
        // plants, then tools" grouping would shuffle a queue.
        //
        // Plants only. A belt's queue is plant cards by construction - the shipped belts list
        // plants and the conveyor mutation rolls the player's plant cards - while the cards the
        // server *kept* beside the belt (the shovel, the watering can) are not dealt and must
        // not ride it: they are drawn fixed, outside the tray. See `sideSlots`.
        return split(host.client().level().slots()).belt();
    }

    /**
     * The cards a belt cannot deal: tools and other non-plant cards, drawn fixed to the right of
     * the tray.
     *
     * <p>They stay on the bar because the belt only took over the plant cards - the shovel is how
     * the player fixes a mistake and the sun bank is what the sun is drawn for - and they stay
     * <em>outside</em> the tray because a stationary tool on a moving belt reads as a card the
     * belt is about to deal, and the tray's capacity counts belt cards. The classification is the
     * same one the server made when it rebuilt the bar (belt cards first, then everything else),
     * read from the card's own kind rather than from its position.
     */
    private List<SlotInfo> sideSlots() {
        return split(host.client().level().slots()).side();
    }

    /**
     * Splits a bar into the cards a belt deals and the cards it does not.
     *
     * <p>One pure function rather than two filters written at the call sites, and testable
     * without a client: the two lists have to partition the bar, and the sun card - drawn as the
     * HUD bank rather than as a card - is in neither.
     */
    record Split(List<SlotInfo> belt, List<SlotInfo> side) {
        /**
         * The tools among the cards the belt did not take over: what a tool hotkey may fire here.
         *
         * <p>The side list can hold a non-plant, non-tool card (a resource other than the sun), and
         * a hotkey names a tool - so the kind is filtered rather than the whole side list handed
         * back.
         */
        List<SlotInfo> tools() {
            return side.stream().filter(CardBar::isTool).toList();
        }
    }

    static Split split(List<SlotInfo> all) {
        List<SlotInfo> belt = new ArrayList<>();
        List<SlotInfo> side = new ArrayList<>();
        for (SlotInfo slot : all) {
            if (SUN_CARD_ID.equals(slot.defId())) {
                continue;
            }
            if (isBeltCard(slot)) {
                belt.add(slot);
            } else {
                side.add(slot);
            }
        }
        return new Split(List.copyOf(belt), List.copyOf(side));
    }

    /** True when a card is one the belt deals, i.e. a plant card. */
    static boolean isBeltCard(SlotInfo slot) {
        return "plant".equals(slot.kind());
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
                        host.selectedCardIndex() == slot.index(),
                        host.lockedSlots().contains(slot.index()));
            }
        } finally {
            client.clipping().pop();
        }

        // The cards the belt did not take over. Drawn after the clipping is popped and at their
        // own fixed x, so the treads never run under them: they are on the bar, not on the belt.
        List<SlotInfo> side = sideSlots();
        for (int i = 0; i < side.size(); i++) {
            SlotInfo slot = side.get(i);
            CardPainter.draw(client, slot, sideX(i), viewportY, cardWidth, cardHeight, 1F,
                    host.selectedCardIndex() == slot.index(),
                    host.lockedSlots().contains(slot.index()));
        }
    }

    /** The left edge of the {@code i}-th card kept outside the belt. */
    private float sideX(int i) {
        return viewportX - BELT_PADDING + trackWidth + SIDE_GAP + i * (cardWidth + cardGap);
    }

    @Override
    public int slotAt(double guiX, double guiY) {
        updateLayout();
        // The kept cards are hit where they are drawn - they do not move, so their place is their
        // slot's place.
        List<SlotInfo> side = sideSlots();
        for (int i = 0; i < side.size(); i++) {
            float x = sideX(i);
            if (guiX >= x && guiX < x + cardWidth
                    && guiY >= viewportY && guiY < viewportY + cardHeight) {
                return side.get(i).index();
            }
        }
        List<SlotInfo> slots = slots();
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
        updateLayout();
        float right = lastSideRight();
        return guiX >= viewportX - BELT_PADDING && guiX <= right
                && guiY >= viewportY - BELT_PADDING && guiY <= viewportY + cardHeight + BELT_PADDING;
    }

    /** The right edge of the whole bar: the tray plus the kept cards beside it. */
    private float lastSideRight() {
        int count = sideSlots().size();
        if (count == 0) {
            return viewportX - BELT_PADDING + trackWidth;
        }
        return sideX(count - 1) + cardWidth;
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
     * Lays out the belt: a tray at the top of the window, as wide as the belt's capacity rather
     * than as wide as what happens to be on it right now, with the cards the belt did not take
     * over in a fixed row to its right.
     *
     * <p>The bar shares the top row with the sun bank and the pause/speed buttons, so on a narrow
     * window it is the <em>cards</em> that give way: they shrink (down to
     * {@link #BELT_MIN_CARD_HEIGHT}) until the tray <em>and</em> the kept cards fit. The
     * alternative - a tray that is always six full-size cards wide - is drawn under the pause
     * button on the default window, which reads as a rendering bug rather than as a belt.
     */
    private void updateLayout() {
        PvzceClient client = host.client();
        int guiH = client.guiHeight();
        int sideCount = sideSlots().size();
        // The tray is placed to the right of the sun bank when the level has one, like the
        // ordinary seed row: a mutation's belt keeps the sun card on the bar, so the bank is on
        // screen and the tray must not be drawn over it.
        float left = host.hasSunBank()
                ? com.pvzce.client.gui.hud.cardbar.CardBarLayout.cardsLeft(true)
                : BELT_PADDING + 4F;
        float right = host.rightBound();
        float available = Math.max(120F, right - left);
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
        while (trackWidth + sideWidth(sideCount) > available && cardHeight > BELT_MIN_CARD_HEIGHT) {
            cardHeight--;
            cardWidth = Math.max(18, Math.round(cardHeight * 100F / 140F));
            cardGap = Math.max(2, cardWidth / 8);
            trackWidth = trackWidth(capacity);
        }
        float total = trackWidth + sideWidth(sideCount);
        // Centred in the room the banks and buttons leave, never off the left edge.
        float start = Math.round(Math.max(left, left + (available - total) / 2F));
        // `viewportX` is the first *belt* card's left edge; the tray adds its padding to the left.
        viewportX = Math.round(start + BELT_PADDING);
        viewportY = Math.round(guiH - cardHeight - BELT_TOP_MARGIN - BELT_PADDING * 2F);
        viewportWidth = Math.max(1, Math.round(trackWidth - BELT_PADDING * 2F));
        viewportHeight = cardHeight;
    }

    /** The room the fixed cards take to the right of the tray, gap included. */
    private float sideWidth(int count) {
        if (count <= 0) {
            return 0F;
        }
        return SIDE_GAP + count * (cardWidth + cardGap) - cardGap;
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
