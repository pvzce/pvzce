package com.pvzce.client.gui.editor.pages;

import com.google.gson.JsonElement;
import com.pvzce.api.content.LevelDef;
import com.pvzce.api.util.Identifier;
import com.pvzce.client.PvzceClient;
import com.pvzce.client.gui.GuiLang;
import com.pvzce.client.gui.GuiText;
import com.pvzce.client.gui.components.AbstractSelectionList;
import com.pvzce.client.gui.components.Button;
import com.pvzce.client.gui.components.PaletteList;
import com.pvzce.client.gui.editor.EditorContext;
import com.pvzce.client.gui.screens.ListEditorSupport;
import com.pvzce.client.gui.editor.EditorPage;
import com.pvzce.client.gui.editor.LevelFileWriter;
import com.pvzce.client.gui.screens.CardPoolEditorDialog;
import com.pvzce.client.renderer.SpriteRenderer;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.core.SlotResolver;
import com.pvzce.common.level.mechanic.LevelMechanic;
import com.pvzce.common.level.mechanic.LevelMechanics;
import com.pvzce.common.util.MathUtil;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The card page: the level's own cards, and every card the game can offer.
 *
 * <p>Two lists, and no per-card "fixed" flag to think about. The rule is positional: the
 * cards listed here are the level's and are pinned, and whatever is left of the slot count is
 * the player's to fill from the right-hand list. A page of fixed/optional toggles was more
 * machinery than the rule needs.
 *
 * <p>The page is hidden for a level that deals its own cards: a level whose card source is a
 * mechanic - a conveyor belt - never grants the cards listed here, so the tab would open a
 * list whose edits are discarded. See {@link #visibleFor}.
 */
public final class CardsPage implements EditorPage {

    private final CardPoolEditorDialog.Config cardPoolConfig = new CardPoolEditorDialog.Config();
    private int maxSeedSlots = LevelDef.UNSET_MAX_SEED_SLOTS;

    private AbstractSelectionList<String> poolList;
    private AbstractSelectionList<AvailableRow> availableList;
    /** Where the card page's three lists begin; its labels sit in the strip above. */
    private int cardListTop;

    private final List<AvailableRow> availableRows = new ArrayList<>();

    @Override
    public String id() {
        return "cards";
    }

    @Override
    public int order() {
        return 50;
    }

    @Override
    public String label() {
        return GuiLang.raw("pvzce.editor.page.cards", "cards");
    }

    @Override
    public boolean hasPalette() {
        return false;
    }

    /**
     * A page the level has no use for is hidden, not greyed out.
     *
     * <p>The card page is the case: a level whose card source is a conveyor belt never grants
     * the cards it lists, so the page would open a list whose edits are discarded. The question
     * is asked of the level file's {@code mechanics} - "does it declare a self-dealt card
     * source" - which is the same answer the server reads.
     */
    @Override
    public boolean visibleFor(EditorContext context) {
        return !declaresSelfDealtCards(context);
    }

    private static boolean declaresSelfDealtCards(EditorContext context) {
        for (JsonElement element : context.draft().getArray("mechanics")) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonElement type = element.getAsJsonObject().get("type");
            if (type == null || !type.isJsonPrimitive()) {
                continue;
            }
            Identifier id = Identifier.tryParse(type.getAsString());
            LevelMechanic<?> mechanic = id == null ? null : LevelMechanics.get(id);
            if (mechanic != null && mechanic.dealsItsOwnCards()) {
                return true;
            }
        }
        return false;
    }

    /** The loaded file's card block; a level with no {@code slots} starts from the defaults. */
    @Override
    public void readFrom(EditorContext context) {
        boolean hasSlots = context.draft().has("slots");
        cardPoolConfig.replaceWith(CardPoolEditorDialog.Config.fromJson(context.draft().json()));
        refreshCardPool(!hasSlots);
        maxSeedSlots = cardPoolConfig.maxSeedSlots;
    }

    /**
     * The card bar, written whole: the list is the contract and {@code max_seed_slots} says how
     * many slots there are.
     *
     * <p>The clamp belongs here rather than only in the buttons that adjust the count: a page
     * that was never opened still saves, and the file must never carry a count the wire format
     * cannot express.
     */
    @Override
    public void writeTo(EditorContext context) {
        maxSeedSlots = cardPoolConfig.maxSeedSlots < 0
                ? LevelDef.UNSET_MAX_SEED_SLOTS
                : MathUtil.clamp(cardPoolConfig.maxSeedSlots, 1, CardPoolEditorDialog.MAX_SEED_SLOTS_LIMIT);
        LevelFileWriter.cards(context.draft(), cardPoolConfig.pool, maxSeedSlots);
    }

    @Override
    public void build(EditorContext context) {
        buildCardPage(context);
    }

    @Override
    public void render(EditorContext context) {
        renderCardPage(context);
    }

    /**
     * The two lists and their buttons.
     *
     * <p>The pool column sits in the centre and the available column spans across the side
     * panel, because the card page declares no palette.
     */
    private void buildCardPage(EditorContext context) {
        EditorContext.Rect content = context.content();
        int sideW = context.side().width();
        int rowH = MathUtil.clamp(content.height() / 14, 26, 34);
        int actionRows = 2;
        int actionBlock = rowH * actionRows + 18;
        cardListTop = content.y() + actionBlock;
        int listH = Math.max(90, content.height() - actionBlock - 24);
        int gap = 10;
        int colW = Math.max(90, (content.width() + sideW + 10 - gap) / 2);
        int availX = content.x() + colW + gap;

        poolList = context.own(new AbstractSelectionList<String>(content.x(), cardListTop, colW, listH,
                MathUtil.clamp(listH / 8, 26, 38),
                (renderClient, id, rx, ry) -> renderCardRow(renderClient, id, rx, ry, false)));
        poolList.setEntries(new ArrayList<>(cardPoolConfig.pool));

        rebuildAvailableRows();
        availableList = context.own(new AbstractSelectionList<AvailableRow>(availX, cardListTop, colW, listH,
                MathUtil.clamp(listH / 8, 26, 38), this::renderAvailableRow));
        availableList.setEntries(new ArrayList<>(availableRows));

        int actionW = Math.max(72, (colW - gap) / 2);
        int y = content.y() + 8;
        context.own(new Button(content.x(), y, actionW, rowH, "加入卡池", this::addPoolEntry));
        context.own(new Button(content.x() + actionW + gap, y, actionW, rowH, "移出卡池", this::removePoolEntry));
        context.own(new Button(availX, y, actionW, rowH, "全部加入", this::addAllPool));
        context.own(new Button(availX + actionW + gap, y, actionW, rowH, "恢复默认卡", this::keepOnlyDefaults));
        context.own(new Button(content.x(), y + rowH + 4, actionW, rowH, "卡池上移", () -> movePoolEntry(-1)));
        context.own(new Button(content.x() + actionW + gap, y + rowH + 4, actionW, rowH, "卡池下移",
                () -> movePoolEntry(1)));
        context.own(new Button(availX, y + rowH + 4, actionW, rowH, "卡槽上限 +", () -> adjustMaxSeedSlots(1)));
        context.own(new Button(availX + actionW + gap, y + rowH + 4, actionW, rowH, "卡槽上限 -",
                () -> adjustMaxSeedSlots(-1)));
    }

    /**
     * The column titles and the slot summary.
     *
     * <p>The summary sits on the band's bottom edge, where the page's own reserve above it
     * leaves room; the note about a full bar shares the line rather than landing on the column
     * titles.
     */
    private void renderCardPage(EditorContext context) {
        PvzceClient renderClient = context.client();
        EditorContext.Rect area = context.fullContent();
        float labelY = cardListTop - 17F;
        float colW = (area.width() - 10) / 2F;
        int levelCards = cardPoolConfig.pool.size();
        boolean followsBackpack = cardPoolConfig.maxSeedSlots < 0;
        int declared = followsBackpack
                ? com.pvzce.common.PvzceConstants.DEFAULT_SEED_SLOTS : cardPoolConfig.maxSeedSlots;
        int slots = Math.max(levelCards, declared);
        int free = Math.max(0, slots - levelCards);
        float summaryY = area.y() + area.height() + 4F;
        renderClient.fonts().body().draw("总卡槽 " + slots
                        + (followsBackpack ? "（跟随背包）" : "")
                        + "　关卡固定 " + levelCards
                        + "　玩家可选 " + free, area.x() + 2, summaryY, 0.74F, 1F, 1F, 1F, 1F);
        if (free == 0) {
            // On the summary line, not above the column labels: the note and the
            // column title landed on the same pixels.
            renderClient.fonts().body().draw("（关卡卡槽已占满总卡槽：玩家拿到固定卡组，没有可选内容）",
                    area.x() + 2 + renderClient.fonts().body().width(
                            "总卡槽 " + slots + "　关卡固定 " + levelCards
                                    + "　玩家可选 " + free, 0.74F) + 10F,
                    summaryY, 0.72F, 1F, 0.85F, 0.45F, 1F);
        }
        renderClient.fonts().body().draw("关卡卡槽（顺序即游戏内卡槽顺序）", area.x() + 2, labelY, 0.7F,
                1F, 0.9F, 0.6F, 1F);
        renderClient.fonts().body().draw("全部卡（按类别）", area.x() + colW + 12, labelY, 0.7F,
                0.9F, 0.9F, 0.9F, 1F);
    }

    /** A row in the available list: a section header or a card. */
    private record AvailableRow(String header, String cardId) {
        static AvailableRow header(String text) {
            return new AvailableRow(text, null);
        }

        static AvailableRow card(String id) {
            return new AvailableRow(null, id);
        }

        boolean isHeader() {
            return cardId == null;
        }
    }

    /** Regroups the card list by kind, so plants, resources and tools read as blocks. */
    private void rebuildAvailableRows() {
        availableRows.clear();
        for (String kind : List.of("plant", "resource", "tool", "other")) {
            List<String> ids = cardPoolConfig.available.stream()
                    .filter(id -> kind.equals(kindOf(id)))
                    .sorted()
                    .toList();
            if (ids.isEmpty()) {
                continue;
            }
            availableRows.add(AvailableRow.header(kindLabel(kind) + "（" + ids.size() + "）"));
            for (String id : ids) {
                availableRows.add(AvailableRow.card(id));
            }
        }
    }

    /** A slot's kind from its resolved card, defaulting to "other". */
    private static String kindOf(String slotId) {
        Identifier id = Identifier.tryParse(slotId);
        if (id == null) {
            return "other";
        }
        return SlotResolver.resolve(id)
                .map(card -> card.kind().json())
                .orElse("other");
    }

    private static String kindLabel(String kind) {
        return switch (kind) {
            case "plant" -> "植物";
            case "resource" -> "资源";
            case "tool" -> "工具";
            default -> "其它";
        };
    }

    /** The card ids currently in the list, headers skipped. */
    private List<String> availableIds() {
        return availableRows.stream().filter(row -> !row.isHeader()).map(AvailableRow::cardId).toList();
    }

    private String selectedAvailableId() {
        if (availableList == null) {
            return null;
        }
        AvailableRow row = availableList.selected();
        return row == null || row.isHeader() ? null : row.cardId();
    }

    private void renderAvailableRow(PvzceClient renderClient, AvailableRow row, int x, int y) {
        int rowH = availableList.entryHeight();
        if (row.isHeader()) {
            SpriteRenderer.solid(x - 6, y, Math.max(10, availableList.width() - 10), rowH, 0.05F,
                    0.22F, 0.28F, 0.34F, 0.9F);
            renderClient.fonts().body().draw(row.header(), x - 2, y + rowH / 2F + 1F, 0.78F, 1F, 0.95F, 0.75F, 1F);
            return;
        }
        renderCardRow(renderClient, row.cardId(), x, y, true);
    }

    /** One card row: icon, name, short id, and whether the level already uses it. */
    private void renderCardRow(PvzceClient renderClient, String id, int x, int y, boolean inAvailable) {
        Identifier parsed = Identifier.tryParse(id);
        AbstractSelectionList<?> list = inAvailable ? availableList : poolList;
        int rowH = list == null ? 30 : list.entryHeight();
        Identifier icon = PaletteList.iconFor(renderClient, PaletteList.Kind.ENTITY, parsed);
        int iconSize = Math.max(18, (int) (rowH * 0.7F));
        if (icon != null) {
            renderClient.drawTexture(icon, x, y + (rowH - iconSize) / 2F, iconSize, iconSize,
                    0.1F, 1F, 1F, 1F, 1F);
        }
        float textX = x + iconSize + 6;
        renderClient.fonts().body().draw(GuiLang.name(parsed), textX, y + rowH / 2F + 1F, 0.78F, 1F, 1F, 1F, 1F);
        renderClient.fonts().body().draw(GuiText.shortId(id), textX, y + rowH / 2F - 12F, 0.6F, 0.6F, 0.65F, 0.7F, 1F);
        if (inAvailable && cardPoolConfig.pool.contains(id)) {
            String marker = "已选";
            float scale = 0.6F;
            float markerW = renderClient.fonts().body().width(marker, scale);
            renderClient.fonts().body().draw(marker, x + Math.max(0F, list.width() - markerW - 10F),
                    y + rowH - 12F, scale, 0.95F, 0.85F, 0.5F, 1F);
        }
    }

    private void addPoolEntry() {
        String id = selectedAvailableId();
        if (id == null || cardPoolConfig.pool.contains(id)) {
            return;
        }
        cardPoolConfig.pool.add(id);
        refreshCardLists();
    }

    private void addAllPool() {
        Set<String> existing = new LinkedHashSet<>(cardPoolConfig.pool);
        for (String id : availableIds()) {
            if (existing.add(id)) {
                cardPoolConfig.pool.add(id);
            }
        }
        refreshCardLists();
    }

    /** Resets the bar to the level's default cards, which is what a new level starts from. */
    private void keepOnlyDefaults() {
        cardPoolConfig.pool.clear();
        for (String slot : LevelFileWriter.defaultLevelCards()) {
            if (cardPoolConfig.available.contains(slot)) {
                cardPoolConfig.pool.add(slot);
            }
        }
        // Only a declared count is raised to fit the level's own cards; "follow the
        // backpack" is not a number and must stay one.
        if (cardPoolConfig.maxSeedSlots >= 0) {
            cardPoolConfig.maxSeedSlots = Math.max(cardPoolConfig.pool.size(), cardPoolConfig.maxSeedSlots);
        }
        maxSeedSlots = cardPoolConfig.maxSeedSlots;
        refreshCardLists();
    }

    private void removePoolEntry() {
        String card = poolList == null ? null : poolList.selected();
        ListEditorSupport.remove(cardPoolConfig.pool, card);
        refreshCardLists();
    }

    private void movePoolEntry(int delta) {
        String card = poolList == null ? null : poolList.selected();
        if (ListEditorSupport.move(cardPoolConfig.pool, card, delta)) {
            refreshCardLists();
            poolList.select(cardPoolConfig.pool.indexOf(card));
        }
    }

    /**
     * Adjusts the slot count.
     *
     * <p>Floored at the number of cards the level lists: the level's own cards cannot be
     * dropped by shrinking the bar, because {@code LevelDef} raises the count back to fit
     * them when the level loads.
     */
    private void adjustMaxSeedSlots(int delta) {
        int floor = Math.min(Math.max(1, cardPoolConfig.pool.size()), CardPoolEditorDialog.MAX_SEED_SLOTS_LIMIT);
        // Stepping away from "follow the backpack" starts at the number it resolves to,
        // so the first press shows the player what they are changing rather than a 0.
        int current = cardPoolConfig.maxSeedSlots < 0
                ? com.pvzce.common.PvzceConstants.DEFAULT_SEED_SLOTS : cardPoolConfig.maxSeedSlots;
        cardPoolConfig.maxSeedSlots = MathUtil.clamp(current + delta, floor,
                CardPoolEditorDialog.MAX_SEED_SLOTS_LIMIT);
        maxSeedSlots = cardPoolConfig.maxSeedSlots;
    }

    /**
     * Rebuilds both card lists, each keeping its own selection.
     *
     * <p>By item, not by row: the "available cards" list is rebuilt from the registries each
     * time, so a row number can point at a different card after an edit - which is exactly the
     * class of bug {@link ListEditorSupport} exists to prevent.
     */
    private void refreshCardLists() {
        if (poolList != null) {
            ListEditorSupport.refresh(poolList, new ArrayList<>(cardPoolConfig.pool), poolList.selected());
        }
        if (availableList != null) {
            AvailableRow keep = availableList.selected();
            rebuildAvailableRows();
            ListEditorSupport.refresh(availableList, new ArrayList<>(availableRows), keep);
        }
    }

    /** Keeps the editor's card pool and "all slots" list in sync with the registries. */
    private void refreshCardPool(boolean fillDefaults) {
        Set<String> available = new LinkedHashSet<>();
        BuiltInRegistries.SLOT_TYPES.keySet().stream()
                .map(Identifier::toString)
                .sorted()
                .forEach(available::add);
        available.addAll(cardPoolConfig.pool);
        cardPoolConfig.available.clear();
        cardPoolConfig.available.addAll(available);
        if (fillDefaults && cardPoolConfig.pool.isEmpty()) {
            for (String slot : LevelFileWriter.defaultLevelCards()) {
                if (available.contains(slot)) {
                    cardPoolConfig.pool.add(slot);
                }
            }
        }
    }

}
