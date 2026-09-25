package com.pvzce.client.gui.screens;

import com.pvzce.api.util.Identifier;
import com.pvzce.client.PvzceClient;
import com.pvzce.client.gui.GuiLang;
import com.pvzce.client.gui.Screen;
import com.pvzce.client.gui.components.Button;
import com.pvzce.client.gui.layout.GuiLayout;
import com.pvzce.common.shop.ShopItems;

import java.util.ArrayList;
import java.util.List;

/**
 * The shop: three things to spend coins on.
 *
 * <p>Not the original's garage page - the project has no garage, and the original's shop is a
 * counter with a shopkeeper in front of it. This is the plain version of that: a list of what is
 * for sale, what it costs, and whether the world already has it.
 *
 * <h2>The client does not own any of the numbers</h2>
 *
 * <p>The catalogue lives in {@code common.shop.ShopItems}, which the client can read because it is
 * the same source set - so the page knows the prices without a packet per item. What it <em>cannot</em>
 * know is what the server will actually charge: the purchase packet names the item and the server
 * re-reads the price. That is deliberate (see {@code PvzceServer.buyShopItem}), and it means the
 * worst a desynced client can do is draw a stale price and be told no.
 *
 * <p>Ownership comes from {@code ClientProfile}, through the same
 * {@code ShopItems.ownedCount} the server uses - one rule, two callers.
 */
public final class ShopScreen extends Screen {
    /**
     * Room kept above the list for the title and below it for the wallet and the back button.
     *
     * <p>Both are in GUI units, and GUI y runs <b>upward from the bottom</b>: "reserved top" is a
     * band at the high end of the axis, which is why the layout below subtracts rather than adds.
     * Getting that backwards is what put the wallet line at the top of the screen and the third
     * row underneath the back button the first time this page was drawn.
     */
    private static final int RESERVED_TOP = 34;
    private static final int RESERVED_BOTTOM = 34;

    /** The row's own hit boxes, in GUI space: the buy button of each item. */
    private final List<BuyButton> buyButtons = new ArrayList<>();

    public ShopScreen(PvzceClient client) {
        super(client);
    }

    @Override
    protected void init() {
        buyButtons.clear();
        int guiW = client.guiWidth();
        int guiH = client.guiHeight();
        int rowHeight = GuiLayout.fitHeight(guiH, 44, ShopItems.ITEMS.size(),
                RESERVED_TOP, RESERVED_BOTTOM);
        int gap = GuiLayout.gapFor(rowHeight);
        int rowWidth = Math.min(360, guiW - 24);
        int x = centerX(rowWidth);

        // Laid out from the top down: the first row sits just under the title, and each later one
        // below it. GUI y counts up from the bottom, so "below" is a smaller number.
        int listTop = guiH - RESERVED_TOP - rowHeight;
        for (int i = 0; i < ShopItems.ITEMS.size(); i++) {
            ShopItems.Item item = ShopItems.ITEMS.get(i);
            int y = listTop - i * (rowHeight + gap);
            buyButtons.add(new BuyButton(item, x, y, rowWidth, rowHeight));
        }

        // One button at the very bottom, spanning the row width; the wallet line sits just above it.
        int buttonHeight = Math.max(14, Math.min(20, rowHeight / 2));
        addWidget(new Button(x, 5, rowWidth, buttonHeight,
                GuiLang.raw("pvzce.back", "返回"), this::requestClose));
        walletY = 5 + buttonHeight + 4;
        noticeY = guiH / 2F;

        // The keyboard's escape and the button do the same thing; `Screen` handles escape already.
        titleScale = Math.min(2.0F, Math.max(0.9F, guiH / 200F));
        titleY = guiH - RESERVED_TOP / 2F - 6F;
    }

    private float titleScale = 1.4F;
    private float titleY;
    private float walletY;
    private float noticeY;

    @Override
    public boolean blurredBackdrop() {
        return true;
    }

    @Override
    public void render() {
        client.beginGuiView();
        if (!renderBlurredBackdrop(0.10F, 0.11F, 0.14F, 0.62F)) {
            renderBackground(0.08F, 0.1F, 0.12F);
        }
        client.fonts().button().draw(GuiLang.raw("gui.pvzce.shop.title", "商店"),
                centeredTextX(GuiLang.raw("gui.pvzce.shop.title", "商店"), titleScale), titleY,
                titleScale, 1F, 1F, 1F, 1F);

        for (BuyButton row : buyButtons) {
            row.render(client);
        }
        for (var widget : widgets) {
            widget.render(client);
        }
        drawWallet();
    }

    /** The coin counter, drawn the way the level list draws it. */
    private void drawWallet() {
        String line = GuiLang.raw("gui.pvzce.shop.wallet", "金币") + " " + client.profile().coins();
        float scale = 1.1F;
        client.fonts().body().draw(line, centeredTextX(line, scale), walletY, scale,
                1F, 0.86F, 0.35F, 1F);
        if (!notice.isEmpty()) {
            // Over the middle of the list rather than in a corner: it is the answer to the click
            // the player just made, and "nothing happened" is what an unshown refusal looks like.
            client.fonts().button().draw(notice, centeredTextX(notice, 1.3F), noticeY, 1.3F,
                    1F, 0.45F, 0.35F, 1F);
        }
    }

    private float centeredTextX(String text, float scale) {
        return (client.guiWidth() - client.fonts().body().width(text, scale)) / 2F;
    }

    /**
     * Clicking a row buys it.
     *
     * <p>Hit testing in {@code onMouseClicked} rather than by adding a widget per row: the row is a
     * drawing (an icon, a name, a price and a state on one line), and a {@link Button} draws exactly
     * one centred label. The pattern is the one {@code TitleScreen}'s player board uses.
     */
    @Override
    protected void onMouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) {
            return;
        }
        for (BuyButton row : buyButtons) {
            if (row.contains(mouseX, mouseY)) {
                buy(row.item());
                return;
            }
        }
    }

    private void buy(ShopItems.Item item) {
        if (ShopItems.maxedOut(client.profile().seedSlots(), client.profile().unlocked(),
                client.profile().unlockedBuffs(), item)) {
            // Refused here rather than sent and refused: the answer is already known, and a round
            // trip that ends in "you already own it" is a worse way to say so.
            showNotice(GuiLang.raw("gui.pvzce.shop.owned", "已拥有"));
            return;
        }
        if (client.profile().coins() < item.price()) {
            showNotice(GuiLang.raw("gui.pvzce.shop.poor", "金币不足"));
            return;
        }
        client.buyShopItem(item.id().toString());
    }

    /** A one-line answer, shown for a couple of seconds; the server also sends its own. */
    private void showNotice(String line) {
        notice = line;
        noticeUntil = System.nanoTime() + 2_000_000_000L;
    }

    private String notice = "";
    private long noticeUntil;

    @Override
    public void tick() {
        if (System.nanoTime() > noticeUntil) {
            notice = "";
        }
    }

    /** One row: the icon, the name, the price and the state, plus its own hit box. */
    private final class BuyButton {
        private final ShopItems.Item item;
        private final int x;
        private final int y;
        private final int width;
        private final int height;

        BuyButton(ShopItems.Item item, int x, int y, int width, int height) {
            this.item = item;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }

        ShopItems.Item item() {
            return item;
        }

        boolean contains(double mouseX, double mouseY) {
            return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
        }

        void render(PvzceClient client) {
            boolean owned = ShopItems.maxedOut(client.profile().seedSlots(),
                    client.profile().unlocked(), client.profile().unlockedBuffs(), item);
            boolean affordable = client.profile().coins() >= item.price();
            float tint = owned ? 0.55F : 1F;
            client.drawSolid(x, y, width, height, 0.12F, 0.16F * tint, 0.18F * tint,
                    0.22F * tint, 0.92F);
            float pad = 6F;
            float textScale = Math.min(1.2F, Math.max(0.7F, height / 34F));
            Identifier icon = iconFor(item);
            if (icon != null && client.hasTexture(icon)) {
                float size = height - pad * 2F;
                client.drawTexture(icon, x + pad, y + pad, size, size, 0.13F, tint, tint, tint, 1F);
            }
            float textX = x + pad * 2F + height;
            String name = GuiLang.raw("gui.pvzce.shop.item." + item.id().path(), item.id().path());
            client.fonts().body().draw(name, textX, y + height * 0.55F, textScale, 1F, 1F, 1F, 1F);
            String state = owned
                    ? GuiLang.raw("gui.pvzce.shop.owned", "已拥有")
                    : item.price() + " " + GuiLang.raw("pvzce.coin", "金币");
            float stateColour = owned ? 0.6F : (affordable ? 1F : 0.85F);
            client.fonts().body().draw(state, textX, y + height * 0.18F, textScale,
                    stateColour, affordable ? 0.85F : 0.45F, 0.25F, 1F);
        }

        private Identifier iconFor(ShopItems.Item item) {
            // A buff's own icon, or the tool's card face: both are what the player will see again
            // in the buff row or on the bar, which is what makes the shop legible.
            if (item.kind() == ShopItems.Item.Kind.BUFF) {
                com.pvzce.api.content.LevelBuff buff =
                        com.pvzce.common.core.BuiltInRegistries.LEVEL_BUFFS.get(item.id());
                if (buff != null && buff.icon() != null) {
                    return buff.icon().texture();
                }
            }
            return Identifier.withDefaultNamespace("textures/gui/cards/" + item.id().path());
        }
    }
}
