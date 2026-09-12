package com.pvzce.client.gui.screens;

import com.pvzce.api.util.Identifier;
import com.pvzce.client.PvzceClient;
import com.pvzce.client.gui.GuiLang;
import com.pvzce.client.gui.Screen;
import com.pvzce.client.gui.SeedCardRenderer;
import com.pvzce.client.gui.components.Button;
import com.pvzce.common.core.SeedOptions;
import com.pvzce.common.core.SlotResolver;

import java.util.ArrayList;
import java.util.List;

/**
 * The backpack: every card the game knows, grouped by kind, with the ones the
 * player has not unlocked greyed out and padlocked.
 *
 * <p>Showing the locked entries is the point. A list of what you own answers "what
 * can I do now"; a list that also shows what is still missing answers "why can't I
 * plant a wall-nut", which is the question a player actually has. Nothing here is
 * authoritative - the server filters every card pool it sends and re-checks every
 * pick - so this screen is deliberately read-only: it can look at the backpack,
 * never change it.
 */
public final class InventoryScreen extends Screen {
    /** The chooser's wooden board, reused so the backpack reads as the same object. */
    private static final Identifier PANEL_BACKGROUND =
            Identifier.withDefaultNamespace("textures/gui/screen/seeds/seed_chooser_background");
    private static final float PANEL_NATIVE_WIDTH = 465F;
    private static final float PANEL_NATIVE_HEIGHT = 513F;
    private static final float PANEL_BORDER = 20F;
    private static final Identifier LOCK_BADGE = Identifier.withDefaultNamespace("textures/gui/icon/lock");
    private static final Identifier MONEY_BAG = Identifier.withDefaultNamespace("textures/gui/award/money_bag");
    private static final Identifier COIN_ICON = Identifier.withDefaultNamespace("textures/resource/coin");
    private static final Identifier DEFAULT_ICON = Identifier.withDefaultNamespace("textures/resource/generic");

    /** One row of the sheet: the card, what it resolves to, and whether it is owned. */
    private record Entry(Identifier cardId, SlotResolver.ResolvedCard card, boolean owned) {
    }

    /**
     * A card's position in content space, where y = 0 is the top of the sheet and
     * grows downward as negative.
     *
     * <p>Drawn and hit-tested from this one list: computing the grid twice (once to
     * paint, once to click) is how a screen ends up with a click target that is not
     * where the card is.
     */
    private record Placed(Entry entry, float x, float y) {
    }

    /** A section header and its position in content space. */
    private record Header(String text, String note, float y) {
    }

    private final List<Entry> resources = new ArrayList<>();
    private final List<Entry> tools = new ArrayList<>();
    private final List<Entry> plants = new ArrayList<>();
    private final List<Placed> placed = new ArrayList<>();
    private final List<Header> headers = new ArrayList<>();

    private int profileSignature = Integer.MIN_VALUE;
    private float panelX;
    private float panelY;
    private float panelW;
    private float panelH;
    private float panelPad;
    private float bottomBand;
    private float cardW;
    private float cardH;
    private float cardGap;
    private float scrollOffset;
    private float contentHeight;
    private int selectedIndex = -1;
    private final List<Entry> flat = new ArrayList<>();

    public InventoryScreen(PvzceClient client) {
        super(client);
    }

    @Override
    protected void init() {
        client.music().ensureMenu("pvzce:music/choose_your_seeds");
        // The profile travels with the level list, and nothing else requests one for
        // this screen. Without this the backpack showed every card as locked when it
        // was the first thing opened after launch.
        client.connection().send(new com.pvzce.common.network.packet.RequestLevelListC2S(
                client.currentWorld()));
        rebuildEntries();
        layout();

        // The button lives in a reserved band under the sheet rather than on top of
        // it, so it can never sit on the status line or the last row of cards.
        int buttonWidth = Math.min(180, client.guiWidth() / 4);
        int buttonHeight = Math.max(28, Math.min(48, client.guiHeight() / 16));
        addWidget(new Button((int) panelX, (int) (panelY - bottomBand + (bottomBand - buttonHeight) / 2),
                buttonWidth, buttonHeight, GuiLang.raw("pvzce.back", "返回"), client::closeScreen));
    }

    /**
     * Rebuilds the sheet from the registries. Called from {@link #tick} as well as
     * {@link #init}, because the profile travels with the level list and can arrive
     * after this screen was opened - and because a {@code /reload} that adds cards
     * should show them.
     */
    private void rebuildEntries() {
        resources.clear();
        tools.clear();
        plants.clear();
        flat.clear();
        for (Identifier cardId : SeedOptions.allCards()) {
            SlotResolver.ResolvedCard card = SlotResolver.resolve(cardId).orElse(null);
            if (card == null) {
                continue;
            }
            Entry entry = new Entry(cardId, card, client.profile().owns(cardId));
            flat.add(entry);
            switch (card.kind()) {
                case RESOURCE -> resources.add(entry);
                case TOOL -> tools.add(entry);
                case PLANT -> plants.add(entry);
            }
        }
    }

    private void layout() {
        int guiW = client.guiWidth();
        int guiH = client.guiHeight();
        float margin = Math.max(12F, Math.min(guiW * 0.04F, guiH * 0.06F));
        // Cards keep the seed packet's 100:140 ratio at roughly the chooser's size.
        cardH = Math.max(52F, Math.min(92F, guiH * 0.15F));
        cardW = cardH * 100F / 140F;
        cardGap = Math.max(6F, cardW * 0.12F);
        float titleReserve = Math.max(34F, guiH * 0.09F);
        bottomBand = Math.max(40F, guiH * 0.09F);
        panelX = margin;
        panelW = guiW - margin * 2F;
        panelY = margin + bottomBand;
        panelH = Math.max(60F, guiH - panelY - margin - titleReserve);
        // The wooden board is nine-sliced, so its border has to fit inside the panel.
        panelPad = Math.max(12F, Math.min(panelW, panelH) * 0.05F);
        layoutContent();
    }

    /** Places every header and card once; {@link #render} and {@link #cardAt} both read it. */
    private void layoutContent() {
        placed.clear();
        headers.clear();
        float top = 0F;
        top = placeSection("pvzce.inventory.resources", "资源卡", resources, top);
        top = placeSection("pvzce.inventory.tools", "工具", tools, top);
        top = placeSection("pvzce.inventory.plants", "植物", plants, top);
        contentHeight = -top;
        scrollOffset = Math.max(0F, Math.min(Math.max(0F, contentHeight - panelH), scrollOffset));
    }

    /** Lays out one section and returns the content-space y where the next one starts. */
    private float placeSection(String key, String fallback, List<Entry> entries, float top) {
        int owned = 0;
        for (Entry entry : entries) {
            if (entry.owned()) {
                owned++;
            }
        }
        float headerHeight = client.font().lineHeight(1.1F) + 12F;
        headers.add(new Header(GuiLang.raw(key, fallback), owned + " / " + entries.size(), top));
        float gridTop = top - headerHeight;
        float innerLeft = panelX + panelPad;
        float innerWidth = panelW - panelPad * 2F;
        int perRow = Math.max(1, (int) ((innerWidth + cardGap) / (cardW + cardGap)));
        for (int i = 0; i < entries.size(); i++) {
            placed.add(new Placed(entries.get(i),
                    innerLeft + (i % perRow) * (cardW + cardGap),
                    gridTop - cardH - (i / perRow) * (cardH + cardGap)));
        }
        int rows = Math.max(1, (entries.size() + perRow - 1) / perRow);
        return gridTop - rows * cardH - (rows - 1) * cardGap - cardGap * 2F;
    }

    @Override
    public void tick() {
        // The profile travels with the level list and can arrive after this screen
        // was opened; rebuild only when it actually changed, since resolving every
        // card hits the registries.
        int signature = java.util.Objects.hash(client.profile().coins(),
                client.profile().unlocked(), client.profile().unlockAll());
        if (signature != profileSignature) {
            profileSignature = signature;
            rebuildEntries();
            layoutContent();
        }
    }

    @Override
    public void render() {
        client.beginGuiView();
        int guiW = client.guiWidth();
        int guiH = client.guiHeight();
        // Deliberately not renderBackground(...): that draws the title artwork, and the
        // backpack is a page over the game, not another title screen.
        client.drawSolid(0, 0, guiW, guiH, -1F, 0.07F, 0.11F, 0.08F, 1F);
        drawPanel();

        String title = GuiLang.raw("pvzce.inventory.title", "背包");
        float titleScale = Math.min(2.2F, guiH / 95F);
        client.font().draw(title, (guiW - client.font().width(title, titleScale)) / 2F,
                guiH - client.font().lineHeight(titleScale) - 6, titleScale, 1F, 0.94F, 0.4F, 1F);
        drawCoinCounter();

        float contentTop = panelY + panelH - panelPad - scrollOffset;
        client.clipping().push(panelX + panelPad, panelY + panelPad * 0.5F,
                panelW - panelPad * 2F, panelH - panelPad * 1.5F);
        try {
            for (Header header : headers) {
                float y = contentTop + header.y() - client.font().lineHeight(1.1F) - 4F;
                client.font().draw(header.text(), panelX + panelPad, y, 1.1F, 1F, 0.9F, 0.6F, 1F);
                client.font().draw(header.note(),
                        panelX + panelW - panelPad - client.font().width(header.note(), 0.8F),
                        y + 2F, 0.8F, 0.8F, 0.84F, 0.8F, 1F);
            }
            for (Placed spot : placed) {
                drawCard(spot, contentTop + spot.y());
            }
        } finally {
            client.clipping().pop();
        }

        drawStatusLine();
        for (var widget : widgets) {
            widget.render(client);
        }
    }

    /**
     * The wooden board behind the sheet.
     *
     * <p>The same nine-sliced board the seed chooser uses: a backpack full of seed
     * packets should look like the screen those packets come from.
     */
    private void drawPanel() {
        float scale = Math.min(panelH / PANEL_NATIVE_HEIGHT, panelW / PANEL_NATIVE_WIDTH);
        com.pvzce.client.gui.components.NinePatch.drawNineSliceTiled(client, PANEL_BACKGROUND,
                panelX, panelY, panelW, panelH, 0.05F,
                PANEL_NATIVE_WIDTH, PANEL_NATIVE_HEIGHT,
                PANEL_BORDER, PANEL_BORDER, PANEL_BORDER, PANEL_BORDER,
                scale, 1F, 1F, 1F, 1F);
    }

    private void drawCard(Placed spot, float y) {
        Entry entry = spot.entry();
        boolean owned = entry.owned();
        float brightness = owned ? 1F : 0.40F;
        Identifier icon = entry.card().icon().orElse(DEFAULT_ICON);
        SeedCardRenderer.draw(client, new SeedCardRenderer.CardModel(
                        icon, SeedCardRenderer.CardKind.fromJson(entry.card().kind().json()),
                        entry.card().costSun(), brightness, 1F, owned, 0F,
                        flat.indexOf(entry) == selectedIndex),
                spot.x(), y, cardW, cardH);
        if (!owned) {
            float size = Math.min(cardW * 0.46F, cardH * 0.38F);
            client.drawTexture(LOCK_BADGE, spot.x() + (cardW - size) / 2F, y + cardH * 0.44F,
                    size, size, 0.4F, 1F, 1F, 1F, 0.95F);
        }
    }

    /** The strip below the sheet: the selected card's name and cost, or the totals. */
    private void drawStatusLine() {
        String text;
        if (selectedIndex >= 0 && selectedIndex < flat.size()) {
            Entry entry = flat.get(selectedIndex);
            if (entry.owned()) {
                text = GuiLang.name(entry.cardId())
                        + (entry.card().costSun() > 0 ? "    " + entry.card().costSun() + " 阳光" : "");
            } else {
                text = GuiLang.idLabel(entry.cardId()) + "    "
                        + GuiLang.raw("pvzce.inventory.locked", "未解锁");
            }
        } else {
            int owned = 0;
            for (Entry entry : flat) {
                if (entry.owned()) {
                    owned++;
                }
            }
            text = GuiLang.raw("pvzce.inventory.hint", "灰色卡片尚未解锁") + "    "
                    + GuiLang.raw("pvzce.inventory.count", "已解锁 {0} / {1}")
                    .replace("{0}", String.valueOf(owned))
                    .replace("{1}", String.valueOf(flat.size()));
        }
        float band = panelY - bottomBand;
        client.font().draw(text, (client.guiWidth() - client.font().width(text, 1F)) / 2F,
                band + (bottomBand - client.font().lineHeight(1F)) / 2F, 1F, 0.92F, 0.92F, 0.92F, 1F);
    }

    /** Wallet readout, drawn the same way the level select draws it. */
    private void drawCoinCounter() {
        float iconSize = Math.max(18F, Math.min(30F, client.guiHeight() * 0.045F));
        Identifier icon = client.hasTexture(MONEY_BAG) ? MONEY_BAG : COIN_ICON;
        float x = client.guiWidth() - iconSize - 12F;
        float y = client.guiHeight() - iconSize - 8F;
        client.drawTexture(icon, x, y, iconSize, iconSize, 0.35F, 1F, 1F, 1F, 1F);
        String coins = String.valueOf(client.profile().coins());
        client.font().draw(coins, x - client.font().width(coins, 1F) - 6F,
                y + (iconSize - client.font().lineHeight(1F)) / 2F, 1F, 1F, 0.95F, 0.5F, 1F);
    }

    @Override
    protected void onMouseClicked(double guiX, double guiY, int button) {
        if (button != 0) {
            return;
        }
        selectedIndex = cardAt(guiX, guiY);
    }

    @Override
    protected void onMouseScrolled(double guiX, double guiY, double amount) {
        float max = Math.max(0F, contentHeight - panelH);
        scrollOffset = Math.max(0F, Math.min(max, scrollOffset - (float) amount * 42F));
    }

    /** The flat index of the card under the cursor, or -1; the inverse of {@link #placeSection}. */
    private int cardAt(double guiX, double guiY) {
        if (guiX < panelX || guiX > panelX + panelW) {
            return -1;
        }
        float localY = (float) guiY - (panelY + panelH - scrollOffset);
        for (Placed spot : placed) {
            if (guiX >= spot.x() && guiX <= spot.x() + cardW
                    && localY >= spot.y() && localY <= spot.y() + cardH) {
                return flat.indexOf(spot.entry());
            }
        }
        return -1;
    }

    /** How many cards the sheet lists; used by the debug overlay and by tests. */
    public int cardCount() {
        return flat.size();
    }

    /** How many of them the player owns. */
    public int ownedCount() {
        int owned = 0;
        for (Entry entry : flat) {
            if (entry.owned()) {
                owned++;
            }
        }
        return owned;
    }
}
