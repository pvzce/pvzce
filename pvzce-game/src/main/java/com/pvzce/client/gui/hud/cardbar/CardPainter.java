package com.pvzce.client.gui.hud.cardbar;

import com.pvzce.api.util.Identifier;
import com.pvzce.client.PvzceClient;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.network.packet.SlotInfo;
import com.pvzce.api.content.SlotDef;

/**
 * Draws one card of a bar.
 *
 * <p>Moved out of {@code InGameScreen} unchanged, including the two quirks it has always
 * had and that this move deliberately preserves: a shovel card first fills its rectangle
 * with the shovel bank's art, and {@link com.pvzce.client.gui.SeedCardRenderer} then draws
 * the packet on top of it. Consolidating that is a rendering change, and this refactor is
 * not one.
 */
public final class CardPainter {
    /** The original's seed packet, the background of every card. */
    private static final Identifier SEED_PACKET =
            Identifier.withDefaultNamespace("textures/gui/hud/seed_packet");
    private static final Identifier SHOVEL_BANK =
            Identifier.withDefaultNamespace("textures/gui/hud/shovel_bank");
    private static final String SHOVEL_ID = "pvzce:shovel";

    public static void draw(PvzceClient client, SlotInfo slot, float x, float y, float width, float height,
                            float alpha, boolean selected) {
        boolean ready = slot.available() && slot.cooldownLeft() <= 0;
        float dark = ready ? 1F : 0.45F;
        Identifier background = SHOVEL_ID.equals(slot.defId()) ? SHOVEL_BANK : SEED_PACKET;
        client.drawTexture(background, x, y, width, height, 0.1F, dark, dark, dark, alpha);
        Identifier icon = icon(slot);
        float iconAreaBottom = y + height * 0.24F;
        float iconAreaHeight = height * 0.76F;
        float iconSize = Math.min(width * 0.80F, iconAreaHeight * 0.78F);
        client.drawTexture(icon,
                x + (width - iconSize) / 2F, iconAreaBottom + (iconAreaHeight - iconSize) / 2F,
                iconSize, iconSize, 0.2F, dark, dark, dark, alpha);

        com.pvzce.client.gui.SeedCardRenderer.draw(client, new com.pvzce.client.gui.SeedCardRenderer.CardModel(
                icon, com.pvzce.client.gui.SeedCardRenderer.CardKind.fromJson(slot.kind()), slot.costSun(),
                dark, alpha, ready, slot.cooldownLeft() / 300F, selected),
                x, y, width, height);
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
