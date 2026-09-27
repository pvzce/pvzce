package com.pvzce.client.gui;

import com.pvzce.api.util.Identifier;
import com.pvzce.client.PvzceClient;

/**
 * The one tooltip in the GUI: a small dark plate with a line of text, drawn under the cursor.
 *
 * <p>Two callers need exactly this and had none: the card bar and the seed chooser want to say
 * what a card is, and the in-game buff icons want to say what a buff is. Before this they simply
 * showed nothing - the names lived in the language file and nothing ever read them on hover.
 *
 * <p>It is a <em>function</em>, not a widget: it has no state, is never in {@code widgets}, and
 * therefore can never eat a click. Whoever draws it decides when it is worth drawing; the rule
 * every caller follows is "only while the thing under the cursor is actually there and fully
 * drawn", because a tip that appears over a panel still sliding in names something the player
 * cannot click yet.
 */
public final class HoverTip {
    /** Distance from the cursor to the plate's top-left corner, in GUI pixels. */
    private static final float OFFSET_X = 12F;
    private static final float OFFSET_Y = 10F;
    private static final float PADDING_X = 5F;
    private static final float PADDING_Y = 3F;
    private static final float DEFAULT_SCALE = 0.62F;

    /** Draws {@code text} beside the cursor. An empty text draws nothing at all. */
    public static void draw(PvzceClient client, String text, float guiX, float guiY, float alpha) {
        draw(client, text, guiX, guiY, alpha, DEFAULT_SCALE);
    }

    public static void draw(PvzceClient client, String text, float guiX, float guiY, float alpha,
                            float scale) {
        if (text == null || text.isEmpty() || alpha <= 0.01F) {
            return;
        }
        float width = client.fonts().body().width(text, scale);
        float height = client.fonts().body().lineHeight(scale);
        float plateW = width + PADDING_X * 2F;
        float plateH = height + PADDING_Y * 2F;
        float x = guiX + OFFSET_X;
        float y = guiY + OFFSET_Y;
        // Flipped rather than clamped when it would leave the window: a tip whose corner is cut
        // off is worse than one on the other side of the cursor.
        if (x + plateW > client.guiWidth() - 2F) {
            x = guiX - OFFSET_X - plateW;
        }
        if (y + plateH > client.guiHeight() - 2F) {
            y = guiY - OFFSET_Y - plateH;
        }
        x = Math.max(2F, x);
        y = Math.max(2F, y);
        client.drawSolid(x, y, plateW, plateH, 0.95F, 0F, 0F, 0F, 0.72F * alpha);
        client.fonts().body().draw(text, x + PADDING_X, y + PADDING_Y, scale,
                1F, 0.96F, 0.86F, alpha);
    }

    /**
     * The display name of a card whose slot id is {@code slotId}.
     *
     * <p>Resolves through {@link com.pvzce.common.core.SlotResolver} rather than naming the slot
     * itself, so a card whose id and content differ (a slot that grants a plant of another name)
     * is named after what it actually gives the player - the same rule the card's icon follows.
     * An id nobody knows falls back to the id's own path, which is still more use than nothing.
     *
     * <p>The language category comes from the resolved card kind, not from the id: a plant card
     * and the plant it grants share an id, so the two halves are named through the same key
     * either way, and a slot that grants a tool is named {@code tool.pvzce.*}.
     */
    public static String cardName(String slotId) {
        Identifier id = Identifier.tryParse(slotId);
        if (id == null) {
            return slotId == null ? "" : slotId;
        }
        com.pvzce.common.core.SlotResolver.ResolvedCard card =
                com.pvzce.common.core.SlotResolver.resolve(id).orElse(null);
        if (card == null) {
            return GuiLang.name(id);
        }
        return GuiLang.name(cardCategory(card.kind()), card.content());
    }

    /**
     * The language category a card kind names.
     *
     * <p>{@code SlotResolver}'s, not a copy: that method exists so "which registry does this
     * card's name live in" has one answer, and this class had quietly grown the second one - which
     * is how a new card kind shows a plant's sentence for a zombie.
     */
    private static String cardCategory(com.pvzce.common.core.Slot.Kind kind) {
        return com.pvzce.common.core.SlotResolver.languageCategory(kind);
    }

    /** The display name of a level buff. Buffs are named off their own id, like content is. */
    public static String buffName(String buffId) {
        Identifier id = Identifier.tryParse(buffId);
        return id == null ? (buffId == null ? "" : buffId) : GuiLang.name("level_buff", id);
    }

    /**
     * The name of whatever a card-bar or chooser entry stands for: a buff if it is one, a card
     * otherwise.
     *
     * <p>One entry point because both screens draw both kinds of entry, and "which id namespace
     * is this" is not something a menu should be deciding per call site.
     */
    public static String nameOf(String entryId, String kind) {
        if (com.pvzce.common.core.SeedOptions.BUFF_KIND.equals(kind)) {
            return buffName(entryId);
        }
        return cardName(entryId);
    }

    private HoverTip() {
    }
}
