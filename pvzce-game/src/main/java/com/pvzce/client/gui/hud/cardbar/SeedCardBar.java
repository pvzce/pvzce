package com.pvzce.client.gui.hud.cardbar;

import com.pvzce.client.PvzceClient;
import com.pvzce.common.network.packet.SlotInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * The ordinary card bar: a row of seed packets along the bottom of the screen, scrolled
 * with the wheel when there are more cards than fit.
 *
 * <p>Moved out of {@code InGameScreen} without changing what it draws. The layout is
 * recomputed on every use rather than stored, because the buttons it shares the row with
 * (pause, speed) are rebuilt when the window is resized.
 */
public final class SeedCardBar implements CardBar {
    /** The SunBank is HUD, not a card; it is drawn in the corner instead of in the row, and
     * {@link CardBarLayout} is where its rectangle and the room it takes live. */
    private static final String SUN_CARD_ID = com.pvzce.common.PvzceIds.SUN.toString();

    private final Host host;
    private int cardScrollOffset;
    private int cardMaxScroll;
    private int viewportX;
    private int viewportY;
    private int viewportWidth;
    private int viewportHeight;
    private int cardWidth = 44;
    private int cardHeight = 62;
    private int cardGap = 4;

    public SeedCardBar(Host host) {
        this.host = host;
    }

    @Override
    public List<SlotInfo> slots() {
        return orderCardSlots(host.client().level().slots());
    }

    /**
     * Cards are grouped by kind in the same order the seed chooser uses - resources
     * leftmost, then plants, then shovels/hammers on the far right - so the two views never
     * disagree about where a card lives. The SunBank is skipped because it is HUD, not a
     * card.
     */
    public static List<SlotInfo> orderCardSlots(List<SlotInfo> slots) {
        List<SlotInfo> resources = new ArrayList<>();
        List<SlotInfo> plants = new ArrayList<>();
        List<SlotInfo> tools = new ArrayList<>();
        for (SlotInfo slot : slots) {
            if (SUN_CARD_ID.equals(slot.defId())) {
                continue;
            }
            if ("tool".equals(slot.kind())) {
                tools.add(slot);
            } else if ("resource".equals(slot.kind())) {
                resources.add(slot);
            } else {
                plants.add(slot);
            }
        }
        resources.addAll(plants);
        resources.addAll(tools);
        return resources;
    }

    @Override
    public void tick() {
        // Nothing moves on its own; the row is static until the player scrolls it.
    }

    @Override
    public void render() {
        PvzceClient client = host.client();
        List<SlotInfo> slots = slots();
        if (slots.isEmpty()) {
            return;
        }
        updateLayout(slots.size());
        client.clipping().push(viewportX, viewportY, viewportWidth, viewportHeight);
        try {
            int visibleIndex = 0;
            for (SlotInfo slot : slots) {
                float x = viewportX + visibleIndex * (cardWidth + cardGap) - cardScrollOffset;
                visibleIndex++;
                if (x + cardWidth < viewportX || x > viewportX + viewportWidth) {
                    continue;
                }
                // A card that was just refused wobbles on the spot; everything else is
                // drawn exactly where the hit test below expects it.
                x += host.cardShake(slot.index());
                CardPainter.draw(client, slot, x, viewportY, cardWidth, cardHeight, 1F,
                        host.selectedCardIndex() == slot.index());
            }
        } finally {
            client.clipping().pop();
        }

        if (cardMaxScroll > 0) {
            client.font().draw("<", viewportX + 2, viewportY + cardHeight / 2F - 8, 1F, 1F, 1F, 1F, 1F);
            client.font().draw(">", viewportX + viewportWidth - 12,
                    viewportY + cardHeight / 2F - 8, 1F, 1F, 1F, 1F, 1F);
        }
    }

    @Override
    public int slotAt(double guiX, double guiY) {
        List<SlotInfo> slots = slots();
        if (slots.isEmpty()) {
            return -1;
        }
        updateLayout(slots.size());
        if (guiX < viewportX || guiX > viewportX + viewportWidth
                || guiY < viewportY || guiY > viewportY + viewportHeight) {
            return -1;
        }
        int visibleIndex = 0;
        for (SlotInfo slot : slots) {
            float x = viewportX + visibleIndex * (cardWidth + cardGap) - cardScrollOffset;
            visibleIndex++;
            if (guiX >= x && guiX < x + cardWidth) {
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
        updateLayout(slots.size());
        return guiX >= viewportX && guiX <= viewportX + viewportWidth
                && guiY >= viewportY && guiY <= viewportY + viewportHeight;
    }

    @Override
    public boolean scroll(double guiX, double guiY, double amount) {
        if (!contains(guiX, guiY)) {
            return false;
        }
        int delta = amount > 0 ? -1 : 1;
        cardScrollOffset = Math.max(0, Math.min(cardMaxScroll,
                cardScrollOffset + delta * (cardWidth + cardGap)));
        return true;
    }

    @Override
    public int cardHeight() {
        return cardHeight;
    }

    private void updateLayout(int slotCount) {
        PvzceClient client = host.client();
        int guiW = client.guiWidth();
        int guiH = client.guiHeight();
        int left = CardBarLayout.cardsLeft(host.hasSunBank());
        int right = (int) host.rightBound();
        viewportX = left;
        viewportWidth = Math.max(120, right - left);
        cardHeight = Math.max(56, Math.min(74, guiH / 6));
        cardWidth = Math.max(38, Math.round(cardHeight * 100F / 140F));
        cardGap = Math.max(2, cardWidth / 10);
        viewportY = guiH - cardHeight - 8;
        viewportHeight = cardHeight;

        int contentWidth = slotCount * (cardWidth + cardGap) - cardGap;
        cardMaxScroll = Math.max(0, contentWidth - viewportWidth);
        cardScrollOffset = Math.max(0, Math.min(cardScrollOffset, cardMaxScroll));
    }
}
