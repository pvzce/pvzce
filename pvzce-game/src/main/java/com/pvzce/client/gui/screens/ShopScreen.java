package com.pvzce.client.gui.screens;

import com.pvzce.api.content.LevelBuff;
import com.pvzce.api.util.Identifier;
import com.pvzce.client.PvzceClient;
import com.pvzce.client.gui.GuiLang;
import com.pvzce.client.gui.Screen;
import com.pvzce.client.gui.components.AbstractSelectionList;
import com.pvzce.client.gui.components.Button;
import com.pvzce.client.gui.layout.MenuPageCanvas;
import com.pvzce.client.gui.layout.MenuPageCanvas.Canvas;
import com.pvzce.common.PvzceConstants;
import com.pvzce.common.buff.BuiltInBuffs;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.shop.ShopItems;

import java.util.ArrayList;
import java.util.List;

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
 * <h2>Selecting and buying are two steps</h2>
 *
 * <p>Clicking a row <em>selects</em> it; the purchase is a button. That is what the page was always
 * meant to be, and the version before this one was neither: the click handler was written on the
 * screen's own {@code onMouseClicked}, which a list never lets run - {@link
 * AbstractSelectionList#mouseClicked} consumes the click, moves its own selection and calls the
 * callback it was given, and this page gave it none. So a click on a row did nothing at all, which
 * is the reported "商店购买点击无效". The callback is the fix for that half; the button on the right
 * is the other half, because "one click spends 1500 coins" leaves no room to read what is being
 * bought and no way to change your mind.
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
     *
     * <p>The panel is split: {@link #listWidth} of list on the left, then a detail column with the
     * buy button under it. The split is a fraction rather than two fixed widths, so a fourth item
     * and a longer description cannot make one half the wrong size for the other - only the
     * description wraps, and it has a column of its own to wrap in.
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

    /** The gap between the list's column and the detail's, in canvas units. */
    private static final float SPLIT_GAP = 14F;
    /** The detail column's share of the panel's interior width. */
    private static final float DETAIL_SHARE = 0.34F;
    /** The buy button: under the detail, as wide as the detail column and as tall as the footer's. */
    private static final float BUY_BUTTON_H = 44F;
    private static final float BUY_BUTTON_MARGIN = 12F;
    /** The detail column's own padding and type sizes, in canvas units. */
    private static final float DETAIL_PAD = 12F;
    private static final float DETAIL_ICON_BOX = 68F;
    private static final float DETAIL_NAME_SIZE = 1.5F;
    private static final float DETAIL_BODY_SIZE = 0.9F;
    private static final float DETAIL_LINE = 26F;

    /** The icon the wallet is printed with; the same coin the title screen's tray cell shows. */
    private static final Identifier COIN_ICON =
            Identifier.withDefaultNamespace("textures/resource/coin_gold");

    private Canvas canvas = new Canvas(1F, 0F, 0F);
    private AbstractSelectionList<ShopItems.Item> itemList;
    /** The row the pointer is over: index, or -1. Hover is this page's drawing, not the list's. */
    private int hoveredRow = -1;
    /**
     * What the buy button will buy, or {@code null} when nothing is selected.
     *
     * <p>Held as the item rather than as an index: the list's selection is the list's business, and
     * the row that was clicked must stay the selected one even if the catalogue is rebuilt under it
     * (the shop's own rebuy path re-reads the profile and the page re-renders from that).
     */
    private ShopItems.Item selected;
    private Button buyButton;
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
        // The list takes the left column only; the detail column is drawn by this page.
        itemList = new AbstractSelectionList<>(
                Math.round(canvas.interiorX(PANEL_X)),
                Math.round(canvas.interiorBottom(PANEL_Y, PANEL_H)),
                Math.round(listWidth()),
                Math.round(canvas.interiorHeight(PANEL_H)),
                ROW_HEIGHT, (c, item, x, y) -> renderRow(item, x, y));
        itemList.setEntries(ShopItems.ITEMS);
        // The list's own green highlight is off, and "what is selected" is the callback's business:
        // the page draws the selected row itself, because the detail column has to agree with it
        // and two highlights for one fact is one too many.
        itemList.select(-1);
        itemList.setOnRowClick(this::select);
        addWidget(itemList);

        addWidget(canvas.widgetAt(client, MenuPageCanvas.NATIVE_WIDTH / 2F, FOOTER_Y,
                FOOTER_BUTTON_W, FOOTER_BUTTON_H, GuiLang.raw("pvzce.back", "返回"),
                this::requestClose));
        // The buy button, pinned inside the detail column's bottom-right corner. It buys *the
        // selection*, so it is enabled by the selection rather than by the pointer: the row the
        // player clicked is still the row they are buying after they move down to it.
        buyButton = canvas.widgetAtInterior(client, PANEL_X, PANEL_Y, PANEL_W, PANEL_H,
                SPLIT_GAP, BUY_BUTTON_MARGIN, detailNativeWidth(), BUY_BUTTON_H,
                GuiLang.raw("gui.pvzce.shop.buy", "购买"), this::buySelected);
        addWidget(buyButton);
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

    /** The list column's GUI width: the panel interior minus the detail column and the gap. */
    private float listWidth() {
        return Math.max(1F, canvas.interiorWidth(PANEL_W) - detailWidth() - canvas.scaled(SPLIT_GAP));
    }

    /** The detail column's GUI width, a fixed share of the panel's interior. */
    private float detailWidth() {
        return Math.max(1F, canvas.interiorWidth(PANEL_W) * DETAIL_SHARE);
    }

    /** The same width in canvas units, which is what a widget's placement is stated in. */
    private float detailNativeWidth() {
        return detailWidth() / canvas.scale();
    }

    /** The detail column's left edge, in GUI units. */
    private float detailLeft() {
        return canvas.interiorX(PANEL_X) + listWidth() + canvas.scaled(SPLIT_GAP);
    }

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
     * Clicking a row selects it and shows it in the detail column.
     *
     * <p>This is the callback the list calls after it moves its own selection, and it is the only
     * click path the rows have: {@code AbstractSelectionList.mouseClicked} consumes the click, so
     * the screen's {@code onMouseClicked} hook never runs over a row. Writing the purchase there -
     * which is what the page did - is why a click bought nothing at all.
     */
    private void select(ShopItems.Item item) {
        selected = item;
        notice = "";
        noticeUntil = 0L;
    }

    /** The buy button: what the selection is worth, or why it cannot be bought. */
    private void buySelected() {
        if (selected == null) {
            showNotice(GuiLang.raw("gui.pvzce.shop.pick", "先选一件商品"));
            return;
        }
        buy(selected);
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
        renderDetail();
        for (var widget : widgets) {
            widget.render(client);
        }
        renderNotice();
    }

    /**
     * The right-hand column: what the selected item is, in full, and what it costs.
     *
     * <p>One column of its own rather than a fourth line on the row, because the row is 96 units
     * tall and has to be read at a glance while this is the text the player reads before spending
     * 1500 coins on it. A rule between the two columns is what makes them read as two columns
     * rather than as rows that stop early.
     */
    private void renderDetail() {
        float left = detailLeft();
        float right = canvas.interiorRight(PANEL_X, PANEL_W);
        float top = canvas.interiorTop(PANEL_Y);
        float bottom = buyButton == null ? canvas.interiorBottom(PANEL_Y, PANEL_H) : buyButton.y() - 8F;
        float unit = canvas.scale();
        // The rule, drawn the full height of the interior: the list and the detail are two columns
        // of one panel and this is the only thing that says so.
        client.drawSolid(left - canvas.scaled(SPLIT_GAP) / 2F, canvas.interiorBottom(PANEL_Y, PANEL_H),
                1F, canvas.interiorHeight(PANEL_H), 0.05F, 1F, 1F, 1F, 0.12F);
        if (selected == null) {
            client.fonts().body().draw(GuiLang.raw("gui.pvzce.shop.pick", "先选一件商品"),
                    left, top - canvas.scaled(DETAIL_LINE), DETAIL_BODY_SIZE * unit,
                    0.75F, 0.76F, 0.75F, 1F);
            return;
        }
        boolean owned = ShopItems.maxedOut(client.profile().seedSlots(), client.profile().unlocked(),
                client.profile().unlockedBuffs(), selected);
        float textX = left + canvas.scaled(DETAIL_PAD);
        float iconBox = canvas.scaled(DETAIL_ICON_BOX);
        drawFitted(iconFor(selected), textX, top - iconBox - canvas.scaled(DETAIL_PAD),
                iconBox, owned ? 0.5F : 1F);
        client.fonts().body().draw(nameOf(selected), textX, top - iconBox - canvas.scaled(DETAIL_LINE),
                DETAIL_NAME_SIZE * unit, owned ? 0.7F : 1F, owned ? 0.7F : 0.95F,
                owned ? 0.7F : 0.85F, 1F);
        float line = top - iconBox - canvas.scaled(DETAIL_LINE * 2F);
        for (String row : wrapped(descriptionOf(selected), right - textX)) {
            if (line < bottom) {
                break;
            }
            client.fonts().body().draw(row, textX, line, DETAIL_BODY_SIZE * unit,
                    0.78F, 0.8F, 0.78F, 1F);
            line -= canvas.scaled(DETAIL_LINE * 0.82F);
        }
    }

    /**
     * One sentence broken to fit a column.
     *
     * <p>Greedy per <em>character</em>: the shipped descriptions are Chinese, which has no spaces to
     * break on, so "break on spaces" would either overflow the column or throw away the sentence.
     * Latin text breaks at the same places a reader would accept, just less tidily.
     */
    private List<String> wrapped(String text, float width) {
        if (text == null || text.isEmpty() || width <= 0F) {
            return List.of();
        }
        float size = DETAIL_BODY_SIZE * canvas.scale();
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (!line.isEmpty()
                    && client.fonts().body().width(line.toString() + ch, size) > width) {
                lines.add(line.toString());
                line.setLength(0);
            }
            line.append(ch);
        }
        if (!line.isEmpty()) {
            lines.add(line.toString());
        }
        return List.copyOf(lines);
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

        if (item == selected) {
            // The selected row, drawn by the page: the list's own highlight is off, because "which
            // row is selected" and "what the detail column shows" have to be one fact.
            client.drawSolid(x - 6F, y + 2F, itemList.width() - 4F, ROW_HEIGHT - 4F, 0.06F,
                    0.55F, 0.45F, 0.20F, 0.28F);
        } else if (index == hoveredRow && !owned) {
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
     *
     * <p>An {@code UNLOCK} item describes itself, because there is no per-kind sentence that is
     * true of both things sold that way: a plant bought here is described by the plant (the same
     * {@code .desc} the almanac shows), and the rake, which is not a card, has its own line in the
     * shop's table. This used to be a per-kind branch, which meant a second item of the same kind
     * printed the first one's sentence.
     */
    private String descriptionOf(ShopItems.Item item) {
        return switch (item.kind()) {
            case CARD_SLOTS -> GuiLang.raw("gui.pvzce.shop.desc.card_slot", "每副卡组多带一张卡，上限 ")
                    + PvzceConstants.MAX_SEED_SLOTS
                    + GuiLang.raw("gui.pvzce.shop.desc.card_slot_suffix", " 张");
            case BUFF -> GuiLang.raw("gui.pvzce.shop.desc.sun_shovel", "铲掉植物时返还 ")
                    + Math.round(BuiltInBuffs.SUN_SHOVEL_REFUND * 100F)
                    + GuiLang.raw("gui.pvzce.shop.desc.sun_shovel_suffix", "% 的阳光");
            case UNLOCK -> unlockDescriptionOf(item);
        };
    }

    /** The sentence for something bought into the unlocked set: its own, or the content's. */
    private String unlockDescriptionOf(ShopItems.Item item) {
        var card = com.pvzce.common.core.SlotResolver.resolve(item.id());
        if (card.isPresent()) {
            String content = GuiLang.contentOr(
                    com.pvzce.common.core.SlotResolver.languageCategory(card.get().kind()),
                    item.id(), "desc", "");
            if (!content.isEmpty()) {
                return content;
            }
        }
        return GuiLang.raw("gui.pvzce.shop.desc." + item.id().path(), item.id().path());
    }

    /**
     * The item's own art: a buff's icon, the card's declared icon, or the shop's own convention.
     *
     * <p>All three are what the player will see again in the buff row or on the bar, which is what
     * makes the shop legible. The buff ships as a 1254x1254 sheet, so it is fitted to the row rather
     * than stretched by it - the same reason the seed chooser draws it through
     * {@code SeedCardRenderer}. The convention path is the last resort for an item that names
     * neither a buff nor a card (the rake, whose art is a card face nobody plants).
     */
    private Identifier iconFor(ShopItems.Item item) {
        if (item.kind() == ShopItems.Item.Kind.BUFF) {
            LevelBuff buff = BuiltInRegistries.LEVEL_BUFFS.get(item.id());
            if (buff != null && buff.icon() != null) {
                return buff.icon().texture();
            }
        }
        var card = com.pvzce.common.core.SlotResolver.resolve(item.id());
        if (card.isPresent() && card.get().icon().isPresent()) {
            return card.get().icon().get();
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
