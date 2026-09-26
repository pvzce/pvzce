package com.pvzce.client.gui.screens;

import com.pvzce.api.content.LevelBuff;
import com.pvzce.api.util.Identifier;
import com.pvzce.client.PvzceClient;
import com.pvzce.client.gui.GuiLang;
import com.pvzce.client.gui.Screen;
import com.pvzce.client.gui.components.AbstractSelectionList;
import com.pvzce.client.gui.layout.MenuPageCanvas;
import com.pvzce.client.gui.layout.MenuPageCanvas.Canvas;
import com.pvzce.common.PvzceConstants;
import com.pvzce.common.buff.BuiltInBuffs;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.shop.ShopItems;

/**
 * The shop: what there is to spend coins on, on a page of its own.
 *
 * <p>Not the original's garage page - the project has no garage, and the original's shop is a
 * counter with a shopkeeper in front of it. This is the plain version of that: what is for sale,
 * what it costs, what it does, and whether the world already has it.
 *
 * <h2>Composed, not stacked</h2>
 *
 * <p>The first version was three 44-unit rows with a dark wash behind them, drawn straight over
 * the blurred title screen. At the default window the page is a 427x240 strip, and the wallet line
 * landed on top of the third row: there was no composition for it to land on. It is a page now - a
 * title band with the wallet in it, one wooden panel, and a row per item inside it at a size
 * worth reading. The canvas, the panel and the button placement are {@link MenuPageCanvas}, shared
 * with the packs page, so the two cannot drift into two different-looking games.
 *
 * <p>The rows are a list rather than three fixed cards even though the catalogue is three items
 * today: a list brings its own scrolling, clipping and row geometry, and the page then grows to a
 * fourth item without being redrawn.
 *
 * <h2>The client does not own any of the numbers</h2>
 *
 * <p>The catalogue lives in {@code common.shop.ShopItems}, which the client can read because it is
 * the same source set - so the page knows the prices without a packet per item. What it <em>cannot</em>
 * know is what the server will actually charge: the purchase packet names the item and the server
 * re-reads the price. That is deliberate (see {@code PvzceServer.buyShopItem}), and it means the
 * worst a desynced client can do is draw a stale price and be told no.
 *
 * <p>Ownership comes from {@code ClientProfile}, through the same {@code ShopItems.ownedCount} the
 * server uses - one rule, two callers.
 */
public final class ShopScreen extends Screen {
    /**
     * The page's composition, in canvas units.
     *
     * <p>The panel is inset 24 from the sides and stops clear of the footer; the row is 96 tall
     * because that is what a 64-unit icon, three lines of text and the padding around them come
     * to - the room the old three-row page did not have, at a size worth reading. Two and a half
     * rows fit, so the third item is visibly "below" rather than silently missing, and the list
     * scrolls to it.
     */
    private static final float HEADER_TITLE_X = 28F;
    private static final float HEADER_BASELINE = 40F;
    private static final float HEADER_TITLE_SIZE = 1.9F;
    private static final float HEADER_WALLET_SIZE = 1.2F;
    private static final float HEADER_WALLET_RIGHT = MenuPageCanvas.NATIVE_WIDTH - 28F;
    private static final float WALLET_COIN = 20F;
    private static final float WALLET_COIN_GAP = 8F;

    private static final float PANEL_X = 24F;
    private static final float PANEL_Y = 72F;
    private static final float PANEL_W = MenuPageCanvas.NATIVE_WIDTH - PANEL_X * 2F;
    private static final float PANEL_H = 400F;
    private static final int ROW_HEIGHT = 96;
    private static final float ROW_PAD = 12F;
    private static final float ROW_ICON_BOX = 64F;
    private static final float ROW_TEXT_X = ROW_PAD + ROW_ICON_BOX + 16F;
    private static final float ROW_NAME_BASELINE = 64F;
    private static final float ROW_DESC_BASELINE = 46F;
    private static final float ROW_STATE_BASELINE = 24F;
    private static final float ROW_NAME_SIZE = 1.45F;
    private static final float ROW_DESC_SIZE = 0.82F;
    private static final float ROW_STATE_SIZE = 1.1F;

    private static final float FOOTER_Y = 524F;
    private static final float FOOTER_BUTTON_W = 190F;
    private static final float FOOTER_BUTTON_H = 36F;

    /** The icon the wallet is printed with; the same coin the title screen's tray cell shows. */
    private static final Identifier COIN_ICON =
            Identifier.withDefaultNamespace("textures/resource/coin_gold");

    private Canvas canvas = new Canvas(1F, 0F, 0F);
    private AbstractSelectionList<ShopItems.Item> itemList;
    /** The row the pointer is over: index, or -1. Hover is this page's drawing, not the list's. */
    private int hoveredRow = -1;
    private String notice = "";
    private long noticeUntil;

    public ShopScreen(PvzceClient client) {
        super(client);
    }

    @Override
    protected void init() {
        hoveredRow = -1;
        canvas = MenuPageCanvas.fit(client);

        // The panel is drawn by hand and the rows by the shared list, so both read the panel's
        // interior from the canvas rather than each working it out from the frame's pixel width.
        itemList = new AbstractSelectionList<>(
                Math.round(canvas.interiorX(PANEL_X)),
                Math.round(canvas.interiorBottom(PANEL_Y, PANEL_H)),
                Math.round(canvas.interiorWidth(PANEL_W)),
                Math.round(canvas.interiorHeight(PANEL_H)),
                ROW_HEIGHT, (c, item, x, y) -> renderRow(item, x, y));
        itemList.setEntries(ShopItems.ITEMS);
        // No row is a "current" one: the page has no confirm step and no highlighted item, so the
        // list's own green highlight is switched off by clearing the selection.
        itemList.select(-1);
        addWidget(itemList);

        addWidget(canvas.widgetAt(client, MenuPageCanvas.NATIVE_WIDTH / 2F, FOOTER_Y,
                FOOTER_BUTTON_W, FOOTER_BUTTON_H, GuiLang.raw("pvzce.back", "返回"),
                this::requestClose));
    }

    @Override
    public void tick() {
        if (System.nanoTime() > noticeUntil) {
            notice = "";
        }
        hoveredRow = rowAt(client.guiMouseX(client.window().cursorX()),
                client.guiMouseY(client.window().cursorY()));
    }

    // ------------------------------------------------------------------
    // Geometry
    // ------------------------------------------------------------------

    /**
     * The row index a GUI point is over, or -1.
     *
     * <p>Derived from the list's own geometry - rows laid out bottom-up from its box, offset by the
     * scroll it reports - rather than from a second set of rectangles: two copies of "which row is
     * at this y" is how the drawn row and the clickable row end up one apart. The list clips its
     * rows to its own box, so the box is tested first.
     */
    private int rowAt(double guiX, double guiY) {
        if (itemList == null) {
            return -1;
        }
        if (guiX < itemList.x() || guiX >= itemList.x() + itemList.width()
                || guiY < itemList.y() || guiY >= itemList.y() + itemList.height()) {
            return -1;
        }
        int fromBottom = (int) Math.floor((itemList.y() + itemList.height() - guiY) / ROW_HEIGHT);
        if (fromBottom < 0 || fromBottom >= itemList.visibleCount()) {
            return -1;
        }
        int firstVisible = Math.min(itemList.scrollOffset() / ROW_HEIGHT,
                Math.max(0, itemList.entries().size() - itemList.visibleCount()));
        int index = firstVisible + fromBottom;
        return index < itemList.entries().size() ? index : -1;
    }

    // ------------------------------------------------------------------
    // Interaction
    // ------------------------------------------------------------------

    /**
     * Clicking a row buys it.
     *
     * <p>Hit testing in {@code onMouseClicked} rather than a widget per row: a row is a drawing
     * (an icon, a name, what it does and a price on three lines), and a {@link
     * com.pvzce.client.gui.components.Button} draws exactly one centred label. The pattern is the
     * one {@code TitleScreen}'s player board uses.
     */
    @Override
    protected void onMouseClicked(double guiX, double guiY, int button) {
        if (button != 0) {
            return;
        }
        int index = rowAt(guiX, guiY);
        if (index >= 0) {
            buy(itemList.entries().get(index));
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

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    @Override
    public void render() {
        client.beginGuiView();
        canvas = MenuPageCanvas.fit(client);
        MenuPageCanvas.renderPageBackground(client, this, 0.22F);
        canvas.header(client, MenuPageCanvas.NATIVE_WIDTH);
        canvas.text(client, client.fonts().button(), GuiLang.raw("gui.pvzce.shop.title", "商店"),
                HEADER_TITLE_X, HEADER_BASELINE, HEADER_TITLE_SIZE, 1F, 1F, 1F, 1F);
        renderWallet();
        canvas.panel(client, PANEL_X, PANEL_Y, PANEL_W, PANEL_H);
        for (var widget : widgets) {
            widget.render(client);
        }
        renderNotice();
    }

    /** The coin and the count, right-aligned in the title band. */
    private void renderWallet() {
        String count = String.valueOf(client.profile().coins());
        float textWidth = client.fonts().body().width(count, HEADER_WALLET_SIZE * canvas.scale());
        float coin = canvas.scaled(WALLET_COIN);
        float coinX = canvas.x(HEADER_WALLET_RIGHT) - textWidth - canvas.scaled(WALLET_COIN_GAP) - coin;
        // Sat on the text's own baseline rather than on the band's centre: a coin floating above
        // the number it belongs to reads as a decoration instead of a unit.
        float coinY = canvas.y(HEADER_BASELINE) - coin * 0.14F;
        client.drawTexture(COIN_ICON, coinX, coinY, coin, coin, 0.5F, 1F, 1F, 1F, 1F);
        canvas.textRight(client, client.fonts().body(), count, HEADER_WALLET_RIGHT,
                HEADER_BASELINE, HEADER_WALLET_SIZE, 1F, 0.87F, 0.4F, 1F);
    }

    /**
     * The page's own line, or whatever the server last said, over the top of the panel.
     *
     * <p>Over the panel rather than in a corner: it is the answer to the click the player just
     * made, and "nothing happened" is what an unshown refusal looks like. The page's own line wins
     * while it is set - it is the newer of the two, and the server's is still on screen from the
     * last answer.
     */
    private void renderNotice() {
        String line = notice.isEmpty() ? client.recentServerMessage() : notice;
        if (line.isEmpty()) {
            return;
        }
        float unit = canvas.scale();
        float centerX = canvas.x(MenuPageCanvas.NATIVE_WIDTH / 2F);
        float baseline = canvas.y(PANEL_Y + 26F);
        float plateW = client.fonts().button().width(line, 1.25F * unit) + 30F * unit;
        float plateH = 30F * unit;
        client.drawSolid(centerX - plateW / 2F, baseline - 9F * unit, plateW, plateH, 0.9F,
                0.06F, 0.05F, 0.05F, 0.82F);
        client.fonts().button().drawCentered(line, centerX, baseline, 1.25F * unit,
                1F, 0.5F, 0.42F, 1F);
    }

    /**
     * One row: the item's own art, its name, what it does, and its price or its state.
     *
     * <p>The list hands the renderer the row's own bottom-left corner in GUI units ({@code x},
     * {@code y}); everything below is measured down from {@code y}, which is the list's own
     * convention and not this page's.
     */
    private void renderRow(ShopItems.Item item, int x, int y) {
        int index = itemList.entries().indexOf(item);
        boolean owned = ShopItems.maxedOut(client.profile().seedSlots(), client.profile().unlocked(),
                client.profile().unlockedBuffs(), item);
        boolean affordable = client.profile().coins() >= item.price();
        float unit = canvas.scale();

        if (index == hoveredRow && !owned) {
            client.drawSolid(x - 6F, y + 2F, itemList.width() - 4F, ROW_HEIGHT - 4F, 0.06F,
                    1F, 0.92F, 0.6F, 0.10F);
        }
        if (index > 0) {
            // A hairline between rows rather than a box around each: the panel is the frame, and
            // three boxes inside it would read as three panels.
            client.drawSolid(x + 2F, y + ROW_HEIGHT - 1F, itemList.width() - 14F, 1F, 0.06F,
                    1F, 1F, 1F, 0.10F);
        }

        float box = canvas.scaled(ROW_ICON_BOX);
        float iconX = x + canvas.scaled(ROW_PAD) - 6F;
        float iconY = y + (ROW_HEIGHT - box) / 2F;
        drawFitted(iconFor(item), iconX, iconY, box, owned ? 0.5F : 1F);

        float textX = x + canvas.scaled(ROW_TEXT_X) - 6F;
        client.fonts().body().draw(nameOf(item), textX, y + canvas.scaled(ROW_NAME_BASELINE),
                ROW_NAME_SIZE * unit, owned ? 0.7F : 1F, owned ? 0.7F : 0.95F, owned ? 0.7F : 0.85F, 1F);
        client.fonts().body().draw(descriptionOf(item), textX, y + canvas.scaled(ROW_DESC_BASELINE),
                ROW_DESC_SIZE * unit, 0.78F, 0.8F, 0.78F, 1F);
        renderState(item, owned, affordable, textX, y, unit);
    }

    /** The third line: what it costs, what is already owned, or that it is maxed out. */
    private void renderState(ShopItems.Item item, boolean owned, boolean affordable,
                             float textX, float y, float unit) {
        float baseline = y + canvas.scaled(ROW_STATE_BASELINE);
        if (owned) {
            client.fonts().body().draw(GuiLang.raw("gui.pvzce.shop.owned", "已拥有"),
                    textX, baseline, ROW_STATE_SIZE * unit, 0.5F, 0.9F, 0.5F, 1F);
            return;
        }
        String price = item.price() + " " + GuiLang.raw("pvzce.coin", "金币");
        client.fonts().body().draw(price, textX, baseline, ROW_STATE_SIZE * unit,
                1F, affordable ? 0.84F : 0.6F, 0.28F, 1F);
        // "2/4" only for the item that can be bought more than once: on a one-off the count is
        // the same fact as the state it sits next to.
        int bought = ShopItems.ownedCount(client.profile().seedSlots(), client.profile().unlocked(),
                client.profile().unlockedBuffs(), item);
        if (item.maxOwned() > 1 && bought > 0) {
            float priceWidth = client.fonts().body().width(price, ROW_STATE_SIZE * unit);
            client.fonts().body().draw(bought + "/" + item.maxOwned(),
                    textX + priceWidth + canvas.scaled(12F), baseline,
                    ROW_DESC_SIZE * unit, 0.85F, 0.8F, 0.6F, 1F);
        }
    }

    private String nameOf(ShopItems.Item item) {
        return GuiLang.raw("gui.pvzce.shop.item." + item.id().path(), item.id().path());
    }

    /**
     * What the item does, in one line.
     *
     * <p>The two numbers that make each sentence true come from where they live: the card-slot
     * ceiling from {@code PvzceConstants}, the shovel's refund from the buff. A page that wrote
     * "20%" itself would be the second place to change when balance moves.
     */
    private String descriptionOf(ShopItems.Item item) {
        return switch (item.kind()) {
            case TOOL -> GuiLang.raw("gui.pvzce.shop.desc.rake",
                    "每一关压扁第一只僵尸，保护你的房子");
            case CARD_SLOTS -> GuiLang.raw("gui.pvzce.shop.desc.card_slot", "每副卡组多带一张卡，上限 ")
                    + PvzceConstants.MAX_SEED_SLOTS
                    + GuiLang.raw("gui.pvzce.shop.desc.card_slot_suffix", " 张");
            case BUFF -> GuiLang.raw("gui.pvzce.shop.desc.sun_shovel", "铲掉植物时返还 ")
                    + Math.round(BuiltInBuffs.SUN_SHOVEL_REFUND * 100F)
                    + GuiLang.raw("gui.pvzce.shop.desc.sun_shovel_suffix", "% 的阳光");
        };
    }

    /**
     * The item's own art: a buff's icon, or the tool's card face.
     *
     * <p>Both are what the player will see again in the buff row or on the bar, which is what makes
     * the shop legible. The buff ships as a 1254x1254 sheet, so it is fitted to the row rather than
     * stretched by it - the same reason the seed chooser draws it through {@code SeedCardRenderer}.
     */
    private Identifier iconFor(ShopItems.Item item) {
        if (item.kind() == ShopItems.Item.Kind.BUFF) {
            LevelBuff buff = BuiltInRegistries.LEVEL_BUFFS.get(item.id());
            if (buff != null && buff.icon() != null) {
                return buff.icon().texture();
            }
        }
        return Identifier.withDefaultNamespace("textures/gui/cards/" + item.id().path());
    }

    /** Draws a texture at its own aspect inside a square box; a missing one keeps the row's shape. */
    private void drawFitted(Identifier icon, float x, float y, float box, float tint) {
        if (icon == null || !client.hasTexture(icon)) {
            if (icon != null) {
                client.warnMissingTexture(icon);
            }
            client.drawSolid(x, y, box, box, 0.05F, 0.28F, 0.28F, 0.32F, 1F);
            return;
        }
        var tex = client.textures().getOrLoad(icon);
        float aspect = tex.width() / (float) Math.max(1, tex.height());
        float width = aspect >= 1F ? box : box * aspect;
        float height = aspect >= 1F ? box / aspect : box;
        client.drawTexture(icon, x + (box - width) / 2F, y + (box - height) / 2F,
                width, height, 0.05F, tint, tint, tint, 1F);
    }
}
