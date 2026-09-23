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
        RESOURCE,
        /**
         * A level buff, offered in the chooser's buff page.
         *
         * <p>Its own kind rather than "a plant with no price": a buff is switched on rather than
         * bought, so its footer is blank, and saying so here is what keeps the painter from
         * having to special-case a price of zero.
         */
        BUFF;

        public static CardKind fromJson(String kind) {
            if (kind == null) {
                return PLANT;
            }
            return switch (kind.toLowerCase(java.util.Locale.ROOT)) {
                case "resource" -> RESOURCE;
                case "tool" -> TOOL;
                case "buff" -> BUFF;
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
     * @param background    the card's chrome, or {@code null} for the seed packet
     * @param fitBackground true to draw that chrome at its own aspect ratio, centred, rather
     *                      than stretched to the card - the shovel's slot is nearly square
     *                      and squashing it into a packet-shaped rectangle is visible
     */
    public record CardModel(Identifier icon, CardKind kind, int costSun, float brightness, float alpha,
                            boolean ready, float cooldownRatio, boolean highlighted,
                            Identifier background, boolean fitBackground) {
        public static CardModel of(Identifier icon, CardKind kind, int costSun) {
            return new CardModel(icon, kind, costSun, 1F, 1F, true, 0F, false, null, false);
        }

        /** The same card drawn on different chrome; the icon and the numbers are kept. */
        public CardModel chrome(Identifier chromeBackground, boolean fit) {
            return new CardModel(icon, kind, costSun, brightness, alpha, ready, cooldownRatio,
                    highlighted, chromeBackground, fit);
        }

        /**
         * The same card at a different brightness, with everything else kept.
         *
         * <p>Used by the end-of-level reward packet, which flashes to say "click me": the
         * packet is not dimmed or made transparent, it is the same packet with the light on
         * it going up and down.
         */
        public CardModel withBrightness(float newBrightness) {
            return new CardModel(icon, kind, costSun, newBrightness, alpha, ready, cooldownRatio,
                    highlighted, background, fitBackground);
        }

        /**
         * The same card at a different opacity, with everything else kept.
         *
         * <p>Used by the award page, whose whole frame fades in together: the packet has to
         * arrive with the board behind it rather than before it.
         */
        public CardModel withAlpha(float newAlpha) {
            return new CardModel(icon, kind, costSun, brightness, newAlpha, ready, cooldownRatio,
                    highlighted, background, fitBackground);
        }
    }

    public static final Identifier CARD_BACKGROUND =
            Identifier.withDefaultNamespace("textures/gui/hud/seed_packet");
    /** The original's shovel slot, the chrome of the shovel card. */
    public static final Identifier SHOVEL_SLOT_BACKGROUND =
            Identifier.withDefaultNamespace("textures/gui/hud/shovel_bank");
    /**
     * What a card with no art of its own shows.
     *
     * <p>The shared missing-texture tile, not a private path: a card whose icon does not
     * resolve has to look like every other unresolved reference.
     */
    private static final Identifier FALLBACK_ICON =
            com.pvzce.common.core.EntityArt.MISSING_TEXTURE;

    /** The footer verb shown instead of a price for a resource card. */
    public static final String COLLECT_LABEL = "收集";

    public static void draw(PvzceClient client, CardModel model, float x, float y, float width, float height) {
        float brightness = model.brightness();
        float alpha = model.alpha();
        Identifier background = model.background() == null ? CARD_BACKGROUND : model.background();
        // Where the chrome ended up. For a seed packet it is the whole card; for art that
        // has to keep its proportions it is a smaller, centred rectangle, and the icon
        // follows the chrome rather than the card.
        float chromeX = x;
        float chromeY = y;
        float chromeW = width;
        float chromeH = height;
        if (model.fitBackground()) {
            float[] fitted = fit(client, background, width, height);
            chromeW = fitted[0];
            chromeH = fitted[1];
            chromeX = x + (width - chromeW) / 2F;
            chromeY = y + (height - chromeH) / 2F;
        }
        client.drawTexture(background, chromeX, chromeY, chromeW, chromeH, 0.2F,
                brightness, brightness, brightness, alpha);

        Identifier icon = model.icon() == null ? FALLBACK_ICON : model.icon();
        // The icon window is a fixed fraction of the packet, and the sprite is fitted
        // to it preserving aspect ratio.
        float iconArea = chromeH * ICON_AREA_FRACTION;
        float iconBoxWidth = chromeW * ICON_BOX_WIDTH_FRACTION;
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
            // drawTexture falls back to the missing-texture tile and reports the miss.
        }
        float iconCentreY = model.fitBackground()
                // Chrome with no price bar has no room reserved at the bottom, so the icon
                // is centred in it: the shovel's slot is a square window, not a packet.
                ? chromeY + (chromeH - iconHeight) / 2F
                : chromeY + chromeH * ICON_AREA_BOTTOM + (iconArea - iconHeight) / 2F;
        client.drawTexture(icon, chromeX + (chromeW - iconWidth) / 2F,
                iconCentreY,
                iconWidth, iconHeight, 0.3F, brightness, brightness, brightness, alpha);

        // A conveyor card is handed to the player rather than bought, so it prints no
        // price at all - "0" would read as a price that happened to be free. A buff is
        // switched on rather than bought, so its footer is blank for the same reason.
        String label = switch (model.kind()) {
            case RESOURCE -> COLLECT_LABEL;
            case BUFF -> "";
            default -> model.costSun() == com.pvzce.common.network.packet.SlotInfo.NO_PRICE
                    ? ""
                    : String.valueOf(Math.max(0, model.costSun()));
        };
        if (!label.isEmpty()) {
            float labelScale = Math.max(0.4F, Math.min(0.78F, width / 90F));
            float ink = 0.03F + 0.20F * brightness;
            client.fonts().body().draw(label, x + (width - client.fonts().body().width(label, labelScale)) / 2F,
                    y + height * LABEL_BOTTOM, labelScale, ink, ink * 0.62F, ink * 0.22F, alpha);
        }

        if (!model.ready()) {
            client.drawSolid(x, y, width, height, 0.3F, 0.1F, 0.1F, 0.1F, 0.45F);
        }
        if (model.cooldownRatio() > 0F) {
            // The share of the card that has not come back yet, measured down from the top,
            // so the packet fills up from the bottom as the card recharges - the original's
            // gesture, and the reason the ratio is "left" rather than "elapsed". Drawing the
            // elapsed share up from the bottom in the same dark colour made a card darken on
            // its way to being ready and then pop back to full.
            float remaining = Math.max(0F, Math.min(1F, model.cooldownRatio()));
            client.drawSolid(x, y + height * (1F - remaining), width, height * remaining,
                    0.32F, 0.05F, 0.05F, 0.05F, 0.5F);
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

    /**
     * The largest rectangle of {@code id}'s aspect ratio that fits in {@code width x height}.
     *
     * <p>An unresolvable texture is treated as square: the missing-texture tile is, and a
     * card that cannot measure its own art should still be laid out around something.
     */
    private static float[] fit(PvzceClient client, Identifier id, float width, float height) {
        float aspect = 1F;
        try {
            var texture = client.textures().getOrLoad(id);
            aspect = texture.width() / (float) Math.max(1, texture.height());
        } catch (RuntimeException ignored) {
            // Falls through to the square assumption; the draw reports the miss.
        }
        if (width / height > aspect) {
            return new float[]{height * aspect, height};
        }
        return new float[]{width, width / Math.max(0.01F, aspect)};
    }

    private SeedCardRenderer() {
    }
}
