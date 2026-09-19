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
