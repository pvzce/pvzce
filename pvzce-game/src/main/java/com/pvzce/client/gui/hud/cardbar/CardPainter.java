package com.pvzce.client.gui.hud.cardbar;

import com.pvzce.api.util.Identifier;
import com.pvzce.client.PvzceClient;
import com.pvzce.common.core.BuiltInRegistries;
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
                        cost, dark, alpha, ready, slot.cooldownLeft() / 300F, selected, null, false);
        if (SHOVEL_ID.equals(slot.defId())) {
            model = model.chrome(com.pvzce.client.gui.SeedCardRenderer.SHOVEL_SLOT_BACKGROUND, true);
        }
        com.pvzce.client.gui.SeedCardRenderer.draw(client, model, x, y, width, height);
    }

    /** The sprite a card draws in its window: the slot's own icon, else the content's art. */
    public static Identifier icon(SlotInfo slot) {
        Identifier slotId = Identifier.tryParse(slot.defId());
        if (slotId != null) {
            SlotDef slotDef = BuiltInRegistries.SLOT_TYPES.get(slotId);
            if (slotDef != null && slotDef.icon().isPresent()) {
                return slotDef.icon().get();
            }
        }
        String path = slot.defId().contains(":")
                ? slot.defId().substring(slot.defId().indexOf(':') + 1) : slot.defId();
        return Identifier.withDefaultNamespace("textures/entities/" + path);
    }

    private CardPainter() {
    }
}
