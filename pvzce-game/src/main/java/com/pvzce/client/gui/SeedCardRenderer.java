package com.pvzce.client.gui;

import com.pvzce.api.util.Identifier;
import com.pvzce.client.PvzceClient;

/**
 * Draws one seed packet.
 *
 * <p>The in-game card bar and the seed chooser each had their own painter for the
 * same object, and they had already diverged: the icon box was 72% of the card
 * height in one and 76% in the other, the price sat at 2% from the bottom or 6%,
 * the ink colour was computed from brightness in one and hard-coded in the other,
 * and "收集" was shown for a resource card in one while the other showed it for
 * anything that was not a plant or a tool. Same packet, two looks. One painter now.
 */
public final class SeedCardRenderer {
    /** What the card stands for; decides whether the footer shows a price or a verb. */
    public enum CardKind {
        PLANT,
        TOOL,
        RESOURCE;

        public static CardKind fromJson(String kind) {
            if (kind == null) {
                return PLANT;
            }
            return switch (kind.toLowerCase(java.util.Locale.ROOT)) {
                case "resource" -> RESOURCE;
                case "tool" -> TOOL;
                default -> PLANT;
            };
        }
    }

    /**
     * Everything the painter needs, so it never reaches into a screen.
     *
     * @param icon          sprite to draw in the packet window
     * @param kind          decides the footer text
     * @param costSun       price shown for plants and tools
     * @param brightness    {@code 1} for a normal card, lower when dimmed/unaffordable
     * @param alpha         overall opacity
     * @param ready         false draws the "cannot use yet" wash
     * @param cooldownRatio 0..1 of the card still on cooldown, or 0 for none
     * @param highlighted   true draws the selection flash
     */
    public record CardModel(Identifier icon, CardKind kind, int costSun, float brightness, float alpha,
                            boolean ready, float cooldownRatio, boolean highlighted) {
        public static CardModel of(Identifier icon, CardKind kind, int costSun) {
            return new CardModel(icon, kind, costSun, 1F, 1F, true, 0F, false);
        }
    }

    public static final Identifier CARD_BACKGROUND =
            Identifier.withDefaultNamespace("textures/gui/hud/seed_packet");
    private static final Identifier FALLBACK_ICON =
            Identifier.withDefaultNamespace("textures/resource/generic");

    /** The footer verb shown instead of a price for a resource card. */
    public static final String COLLECT_LABEL = "收集";

    public static void draw(PvzceClient client, CardModel model, float x, float y, float width, float height) {
        float brightness = model.brightness();
        float alpha = model.alpha();
        client.drawTexture(CARD_BACKGROUND, x, y, width, height, 0.2F,
                brightness, brightness, brightness, alpha);

        Identifier icon = model.icon() == null ? FALLBACK_ICON : model.icon();
        // The icon window is a fixed fraction of the packet, and the sprite is fitted
        // to it preserving aspect ratio.
        float iconArea = height * ICON_AREA_FRACTION;
        float iconBoxWidth = width * ICON_BOX_WIDTH_FRACTION;
        float iconBoxHeight = iconArea * ICON_BOX_HEIGHT_FRACTION;
        float iconWidth = iconBoxWidth;
        float iconHeight = iconBoxHeight;
        try {
            var texture = client.textures().getOrLoad(icon);
            float aspect = texture.width() / (float) Math.max(1, texture.height());
            if (iconBoxWidth / iconBoxHeight > aspect) {
                iconWidth = iconBoxHeight * aspect;
            } else {
                iconHeight = iconBoxWidth / Math.max(0.01F, aspect);
            }
        } catch (RuntimeException ignored) {
            // drawTexture falls back to a solid quad and reports the miss.
        }
        client.drawTexture(icon, x + (width - iconWidth) / 2F,
                y + height * ICON_AREA_BOTTOM + (iconArea - iconHeight) / 2F,
                iconWidth, iconHeight, 0.3F, brightness, brightness, brightness, alpha);

        // A conveyor card is handed to the player rather than bought, so it prints no
        // price at all - "0" would read as a price that happened to be free.
        String label = model.kind() == CardKind.RESOURCE
                ? COLLECT_LABEL
                : model.costSun() == com.pvzce.common.network.packet.SlotInfo.NO_PRICE
                        ? ""
                        : String.valueOf(Math.max(0, model.costSun()));
        if (!label.isEmpty()) {
            float labelScale = Math.max(0.4F, Math.min(0.78F, width / 90F));
            float ink = 0.03F + 0.20F * brightness;
            client.font().draw(label, x + (width - client.font().width(label, labelScale)) / 2F,
                    y + height * LABEL_BOTTOM, labelScale, ink, ink * 0.62F, ink * 0.22F, alpha);
        }

        if (!model.ready()) {
            client.drawSolid(x, y, width, height, 0.3F, 0.1F, 0.1F, 0.1F, 0.45F);
        }
        if (model.cooldownRatio() > 0F) {
            float ratio = Math.max(0F, Math.min(1F, 1F - model.cooldownRatio()));
            client.drawSolid(x, y, width, height * ratio, 0.32F, 0.05F, 0.05F, 0.05F, 0.5F);
        }
        if (model.highlighted()) {
            client.drawSolid(x, y, width, height, 0.4F, 1F, 1F, 0.2F, 0.35F);
        }
    }

    /** Vertical space the icon may use, as a fraction of the card height. */
    private static final float ICON_AREA_FRACTION = 0.74F;
    private static final float ICON_BOX_WIDTH_FRACTION = 0.78F;
    private static final float ICON_BOX_HEIGHT_FRACTION = 0.85F;
    /** Where the icon area starts, measured from the bottom of the card. */
    private static final float ICON_AREA_BOTTOM = 0.22F;
    /** Baseline of the footer text, measured from the bottom of the card. */
    private static final float LABEL_BOTTOM = 0.04F;

    private SeedCardRenderer() {
    }
}
