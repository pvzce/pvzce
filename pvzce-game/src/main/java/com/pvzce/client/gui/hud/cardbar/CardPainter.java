package com.pvzce.client.gui.hud.cardbar;

import com.pvzce.api.util.Identifier;
import com.pvzce.client.PvzceClient;
import com.pvzce.client.renderer.EntityTextures;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.core.SlotResolver;
import com.pvzce.common.network.packet.SlotInfo;
import com.pvzce.api.content.SlotDef;

/**
 * Draws one card of a bar.
 *
 * <p>Which chrome a card has is part of the card, so it is passed into
 * {@link com.pvzce.client.gui.SeedCardRenderer} rather than painted underneath it: the
 * shovel's slot used to be drawn here and then covered by the seed packet the shared
 * painter draws for every card, which is why the one card that should not look like a seed
 * packet looked exactly like one.
 *
 * <p><strong>Every card is one size.</strong> The shovel's chrome is the original's
 * {@code ShovelBank.png}, which is almost square, and drawing it at its own aspect ratio
 * inside a packet-shaped card shrank the whole card - body and icon both - to about
 * three-quarters height, so the tools stood a head shorter than the plants beside them in the
 * same row. One card height for the whole bar is the rule; a tool that wants different art can
 * have it, at the card's proportions.
 */
public final class CardPainter {
    private static final String SHOVEL_ID = "pvzce:shovel";

    public static void draw(PvzceClient client, SlotInfo slot, float x, float y, float width, float height,
                            float alpha, boolean selected) {
        draw(client, slot, x, y, width, height, alpha, selected, false);
    }

    /**
     * The same card, plus the padlock a mutation may have put on it.
     *
     * <p>A locked card is drawn like a card that is not ready (its picture dimmed) with the lock
     * over it: the mutation's own state says <em>which</em> slots are shut
     * ({@code MutationStateS2C.lockedSlots}), and the server refuses the same slots, so the picture
     * and the refusal cannot disagree.
     */
    public static void draw(PvzceClient client, SlotInfo slot, float x, float y, float width, float height,
                            float alpha, boolean selected, boolean locked) {
        boolean ready = slot.available() && slot.cooldownLeft() <= 0;
        float dark = ready ? 1F : 0.45F;
        Identifier icon = icon(slot);
        // A price of NO_PRICE prints nothing; the shovel has no sun cost, and the original's
        // shovel slot shows none either.
        int cost = SHOVEL_ID.equals(slot.defId()) ? SlotInfo.NO_PRICE : slot.costSun();
        com.pvzce.client.gui.SeedCardRenderer.CardModel model =
                new com.pvzce.client.gui.SeedCardRenderer.CardModel(
                        icon, com.pvzce.client.gui.SeedCardRenderer.CardKind.fromJson(slot.kind()),
                        cost, dark, alpha, ready, slot.cooldownRatio(), selected, null, false);
        if (SHOVEL_ID.equals(slot.defId())) {
            model = model.chrome(com.pvzce.client.gui.SeedCardRenderer.SHOVEL_SLOT_BACKGROUND, false);
        }
        com.pvzce.client.gui.SeedCardRenderer.draw(client, model, x, y, width, height);
        if (locked) {
            drawLockBadge(client, x, y, width, height, alpha);
        }
    }

    /**
     * The padlock a card wears when something has taken it out of play.
     *
     * <p>Drawn from primitives because the UI has no icon font, and here rather than in the seed
     * chooser (where it started) because two screens now need the same mark: the chooser uses it for
     * a card the level fixes in the bar, and the in-game bar for one a mutation has locked. Two
     * copies of six rectangles is how the two marks end up looking different.
     */
    public static void drawLockBadge(PvzceClient client, float x, float y, float width, float height,
                                     float alpha) {
        float size = Math.max(10F, Math.min(width, height) * 0.34F);
        client.drawSolid(x + width - size, y, size, size, 0.4F, 0.15F, 0.16F, 0.2F, 0.85F * alpha);
        // Body.
        float bodyW = size * 0.56F;
        float bodyH = size * 0.42F;
        float bodyX = x + width - size / 2F - bodyW / 2F;
        float bodyY = y + size * 0.18F;
        client.drawSolid(bodyX, bodyY, bodyW, bodyH, 0.45F, 1F, 0.86F, 0.35F, alpha);
        // Shackle: two uprights and a top bar, so it reads as a padlock at this size.
        float legW = Math.max(1F, bodyW * 0.16F);
        float shackleH = size * 0.26F;
        client.drawSolid(bodyX + bodyW * 0.16F, bodyY + bodyH, legW, shackleH, 0.45F,
                1F, 0.86F, 0.35F, alpha);
        client.drawSolid(bodyX + bodyW * 0.68F, bodyY + bodyH, legW, shackleH, 0.45F,
                1F, 0.86F, 0.35F, alpha);
        client.drawSolid(bodyX + bodyW * 0.16F, bodyY + bodyH + shackleH, bodyW * 0.68F,
                Math.max(1F, legW * 0.8F), 0.45F, 1F, 0.86F, 0.35F, alpha);
    }

    /**
     * The sprite a card draws in its window: the slot's own icon, else the content's art.
     *
     * <p>The fallback goes through {@link SlotResolver} rather than building a path here. This
     * method used to strip the namespace and force {@code pvzce}, while the seed chooser's
     * preview kept it - so a modded entity resolved its sprite on one side and requested a
     * {@code pvzce:} texture on the other, and was invisible on whichever side lost. The
     * resolver also knows the slot's <em>content</em>, which is what carries the art when the
     * slot itself does not (a card's id and the plant it grants need not be the same string).
     */
    public static Identifier icon(SlotInfo slot) {
        Identifier slotId = Identifier.tryParse(slot.defId());
        if (slotId != null) {
            SlotDef slotDef = BuiltInRegistries.SLOT_TYPES.get(slotId);
            if (slotDef != null && slotDef.icon().isPresent()) {
                return slotDef.icon().get();
            }
            return SlotResolver.resolve(slotId)
                    .flatMap(SlotResolver.ResolvedCard::icon)
                    .orElseGet(() -> EntityTextures.forEntity(slot.defId()));
        }
        return EntityTextures.forEntity(slot.defId());
    }

    private CardPainter() {
    }
}
