package com.pvzce.client.gui.screens;

import com.pvzce.api.util.Identifier;
import com.pvzce.client.PvzceClient;
import com.pvzce.client.gui.Screen;
import com.pvzce.client.gui.GuiLang;
import com.pvzce.client.gui.components.Button;
import com.pvzce.client.gui.layout.GuiLayout;
import com.pvzce.client.gui.layout.ListPaging;
import com.pvzce.common.network.packet.LevelListS2C;
import com.pvzce.common.network.packet.LevelTabsS2C;
import com.pvzce.common.network.packet.RequestLevelListC2S;

import java.util.ArrayList;
import java.util.List;

/**
 * The level select screen: a theme column, a category row, and the levels of the open
 * page.
 *
 * <p>The shape follows Minecraft's creative inventory, which is also where the levels now
 * come from: levels are grouped by the first two segments of their id
 * ({@code pvzce:yard/adventure/1_1}), the left column picks the theme and the row above
 * the list picks the category. Both lists and the tab order are sent by the server - the
 * screen renders what it is told rather than collecting groups from the levels it happens
 * to have, so an empty page is visibly empty instead of silently showing another page's
 * levels.
 */
public final class LevelSelectScreen extends Screen {
    /** Original money-bag counter art; the coin sprite is the fallback. */
    private static final Identifier MONEY_BAG = Identifier.withDefaultNamespace("textures/gui/award/money_bag");
    private static final Identifier COIN_ICON = Identifier.withDefaultNamespace("textures/resource/coin");

    private static final Identifier BACKGROUND = Identifier.withDefaultNamespace("textures/gui/screen/level/challenge_background");
    private static final Identifier ARROW = Identifier.withDefaultNamespace("textures/gui/screen/level/zombatar_next_button");
    private static final Identifier ARROW_HIGHLIGHT =
            Identifier.withDefaultNamespace("textures/gui/screen/level/zombatar_next_button_highlight");
    private static final Identifier ARROW_DISABLED =
            Identifier.withDefaultNamespace("textures/gui/screen/level/zombatar_next_button_disabled");

    /** Group sentinels and their display names; the ids are the shared ones. */
    static final String UNCATEGORIZED_ID = com.pvzce.api.util.LevelGrouping.UNCATEGORIZED.toString();
    private static final String UNCATEGORIZED_KEY = "pvzce.level_uncategorized";
    private static final String UNCATEGORIZED_TEXT = "未分类";

    private final List<LevelListS2C.LevelInfo> levels = new ArrayList<>();
    private final List<LevelPage.Tab> tabs = new ArrayList<>();
    /** The open page, rebuilt whenever the server's tab table or the level list changes. */
    private LevelPage.Tab openTab;
    private LevelPage.Rows rows = LevelPage.Rows.empty();
    /** Padlock badge drawn on a row the player cannot enter yet. */
    private static final Identifier LOCK_ICON =
            Identifier.withDefaultNamespace("textures/gui/icon/lock");

    /** True once {@code pvzce.smokeTab} has found its page; see {@link #openSmokeTab}. */
    private boolean smokeTabApplied;

    private int selectedRow = -1;
    /**
     * The selected level's id, which is what the selection actually is.
     *
     * <p>{@link #selectedRow} is only its position in the open page: a refresh or a page
     * change can move it, and a slot that outlived the list it came from made the buttons
     * act on whichever level happened to land there.
     */
    private String selectedLevelId = "";
    /**
     * Where the rows are paged. The arithmetic lives in {@link ListPaging} because that is
     * where the overlap bug was; this screen only holds the result.
     */
    private ListPaging paging = ListPaging.of(0, 1);
    private int totalRows;
    private int rowHeight = 48;
    private int rowGap = 6;
    private final List<AbstractTabButton> themeButtons = new ArrayList<>();
    private final List<AbstractTabButton> categoryButtons = new ArrayList<>();
    private int themeColumnX;
    private int themeColumnWidth;
    private int themeTop;
    private int themeBottom;
    private int categoryRowY;
    private int categoryRowHeight;
    private int categoryRowX;
    private int categoryRowWidth;
    private int gridX;
    private int gridWidth;
    private int gridRenderTop;
    private int gridRenderBottom;
    private int visibleRows;
    private int arrowWidth = 40;
    private int arrowHeight = 46;
    private int prevArrowX;
    private int prevArrowY;
    private int nextArrowX;
    private int nextArrowY;
    private boolean prevHover;
    private boolean nextHover;
    private int titleReserve;
    private int actionY;
    private int actionHeight;
    private int createY;
    private int createHeight;
    private Button nextButton;
    private Button editButton;
    private String lastLevelSignature = "";
    private String lastTabSignature = "";

    public LevelSelectScreen(PvzceClient client) {
        super(client);
    }

    /**
     * A button in the theme column or the category row.
     *
     * <p>A theme button carries only the theme id (the column lists themes, and the pages
     * below them are the category row), a category button carries the page it opens.
     */
    private static final class AbstractTabButton {
        private final Button button;
        private final String themeId;
        private final LevelPage.Tab tab;

        AbstractTabButton(Button button, String themeId) {
            this(button, themeId, null);
        }

        AbstractTabButton(Button button, LevelPage.Tab tab) {
            this(button, tab.themeId(), tab);
        }

        private AbstractTabButton(Button button, String themeId, LevelPage.Tab tab) {
            this.button = button;
            this.themeId = themeId;
            this.tab = tab;
        }
    }

    @Override
    protected Identifier backgroundTexture() {
        return BACKGROUND;
    }

    @Override
    protected boolean backgroundCover() {
        return true;
    }

    @Override
    protected void init() {
        client.music().ensureMenu("pvzce:music/choose_your_seeds");
        client.connection().send(new RequestLevelListC2S(client.currentWorld()));

        themeButtons.clear();
        categoryButtons.clear();
        int guiW = client.guiWidth();
        int guiH = client.guiHeight();
        titleReserve = Math.max(44, Math.min(72, guiH / 5));
        int bottomGap = 10;
        int customWidth = Math.min(270, guiW - 16);
        int bottomHeight = GuiLayout.fitHeight(guiH, 40, 1, titleReserve, 0);
        createHeight = bottomHeight;
        createY = 4;
        int actionGap = 10;
        actionY = createY + createHeight + actionGap;
        actionHeight = GuiLayout.fitHeight(guiH, 44, 1, titleReserve, actionY);

        updateLayout();
        rebuildTabs();
        // The list drives the row count, which drives the layout, so it is applied before
        // the grid is measured rather than after the buttons exist.
        updateLevels(client.levelList());
        updateLayout();

        // Four actions share the bottom row: continue, edit, backpack, back. "Edit" is
        // what makes a level you already made reachable again - before this the only way
        // into the editor was a button that always opened the same fixed id.
        int rowGap = 8;
        int quadWidth = Math.min(170, (guiW - 16 - rowGap * 3) / 4);
        int startX = centerX(quadWidth * 4 + rowGap * 3);
        nextButton = new Button(startX, actionY, quadWidth, actionHeight, "下一步", this::openSetup)
                .style(Button.Style.SEED_CHOOSER);
        nextButton.setActive(selectedRow >= 0);
        addWidget(nextButton);
        editButton = new Button(startX + quadWidth + rowGap, actionY, quadWidth, actionHeight,
                GuiLang.raw("pvzce.editor.edit", "编辑关卡"), this::editSelected)
                .style(Button.Style.SEED_CHOOSER);
        editButton.setActive(selectedRow >= 0);
        addWidget(editButton);
        // The backpack belongs here rather than on the title screen: this is the screen
        // where "what may I bring into a level" is the question the player is asking.
        addWidget(new Button(startX + (quadWidth + rowGap) * 2, actionY, quadWidth, actionHeight,
                GuiLang.raw("pvzce.inventory", "背包"),
                () -> client.openScreen(new InventoryScreen(client))).style(Button.Style.SEED_CHOOSER));
        addWidget(new Button(startX + (quadWidth + rowGap) * 3, actionY, quadWidth, actionHeight,
                GuiLang.raw("pvzce.back", "返回"), this::goBack).style(Button.Style.SEED_CHOOSER));

        addWidget(new Button(centerX(customWidth), createY, customWidth, createHeight,
                GuiLang.raw("pvzce.editor.new_level", "新建关卡"), this::openCreateDialog)
                .style(Button.Style.SEED_CHOOSER));
        updateActionButtons();
    }

    /**
     * Recomputes the theme column, the category row and the list area.
     *
     * <p>The list is one level per row rather than the old card grid: a page shows six to
     * eight levels instead of four, which is what makes grouping by theme and category
     * worth anything - a tab that shows four levels at a time is not easier to scan than
     * one flat list.
     */
    private void updateLayout() {
        if (actionHeight <= 0) {
            return;
        }
        int guiW = client.guiWidth();
        int guiH = client.guiHeight();
        int margin = 12;
        int arrowGap = 10;

        themeColumnX = margin;
        themeColumnWidth = Math.max(96, Math.min(180, guiW / 6));
        int columnGap = 10;

        int categoryY = guiH - titleReserve;
        categoryRowHeight = Math.max(22, Math.min(30, guiH / 22));
        categoryRowX = themeColumnX + themeColumnWidth + columnGap;
        categoryRowWidth = Math.max(120, guiW - categoryRowX - margin);
        categoryRowY = categoryY - categoryRowHeight - 4;
        themeTop = categoryRowY - 8;
        themeBottom = actionY + actionHeight + 24;

        int gridAreaX = categoryRowX + arrowWidth + arrowGap;
        int gridAreaWidth = Math.max(120, guiW - gridAreaX - arrowWidth - arrowGap - margin);
        int gridTop = themeTop;
        int gridBottom = themeBottom;
        int gridHeight = Math.max(60, gridTop - gridBottom);

        int maxRowHeight = Math.min(64, Math.max(34, (guiH - titleReserve) / 7));
        rowHeight = maxRowHeight;
        totalRows = rows.size();
        visibleRows = Math.max(1, (gridHeight + rowGap) / (rowHeight + rowGap));
        int actualHeight = visibleRows * (rowHeight + rowGap) - rowGap;
        gridX = gridAreaX;
        gridWidth = Math.max(120, gridAreaWidth);
        gridRenderTop = gridTop - Math.max(0, (gridHeight - actualHeight) / 2);
        gridRenderBottom = gridRenderTop - actualHeight;

        int arrowCenterY = (gridRenderTop + gridRenderBottom) / 2 - arrowHeight / 2;
        prevArrowX = Math.max(8, gridAreaX - arrowGap - arrowWidth);
        prevArrowY = arrowCenterY;
        nextArrowX = guiW - margin - arrowWidth;
        nextArrowY = arrowCenterY;

        refreshPaging();
    }

    @Override
    public void tick() {
        if (updateTabs(client.levelTabs())) {
            rebuildTabs();
        }
        updateLevels(client.levelList());
    }

    /** True when the tab table changed and the widgets have to be rebuilt. */
    private boolean updateTabs(List<LevelTabsS2C.Tab> current) {
        String signature = current.toString();
        if (signature.equals(lastTabSignature)) {
            return false;
        }
        lastTabSignature = signature;
        tabs.clear();
        tabs.addAll(LevelPage.tabs(current));
        if (openTab != null) {
            int index = LevelPage.indexOf(tabs, openTab.themeId(), openTab.categoryId());
            openTab = index < 0 ? null : tabs.get(index);
        }
        if (openTab == null && !tabs.isEmpty()) {
            // Prefer the page the selection is on, so reopening the screen lands where the
            // player left off instead of at the first tab.
            int selectedTab = LevelPage.tabIndexOf(tabs, findSelected());
            openTab = tabs.get(selectedTab < 0 ? 0 : selectedTab);
        }
        openSmokeTab();
        recomputeRows();
        updateLayout();
        return true;
    }

    private void updateLevels(List<LevelListS2C.LevelInfo> current) {
        String signature = current.toString();
        if (signature.equals(lastLevelSignature)) {
            return;
        }
        lastLevelSignature = signature;
        levels.clear();
        levels.addAll(current);
        if (tabs.isEmpty()) {
            tabs.addAll(LevelPage.tabs(client.levelTabs()));
        }
        if (openTab == null && !tabs.isEmpty()) {
            int selectedTab = LevelPage.tabIndexOf(tabs, findSelected());
            openTab = tabs.get(selectedTab < 0 ? 0 : selectedTab);
        }
        recomputeRows();
        // Re-resolve the selection by id: the list that just arrived is a different snapshot,
        // and keeping the old row would silently point at another level.
        selectedRow = indexOfLevel(rows, selectedLevelId);
        if (selectedRow < 0) {
            selectedLevelId = "";
        }
        updateLayout();
        rebuildTabs();
        updateActionButtons();
    }

    private LevelListS2C.LevelInfo findSelected() {
        for (LevelListS2C.LevelInfo info : levels) {
            if (info.id().equals(selectedLevelId)) {
                return info;
            }
        }
        return null;
    }

    /**
     * Opens a named tab for a screenshot run: {@code -Ppvzce.smoke="pvzce.smokeTab=yard/adventure"}.
     *
     * <p>Only reachable from a smoke run. The screen otherwise always starts on the first
     * tab (or the one holding the selection), which makes a screenshot of any other page
     * impossible to take without clicking through the UI first.
     */
    private void openSmokeTab() {
        String wanted = System.getProperty("pvzce.smokeTab", "");
        if (wanted.isBlank() || tabs.isEmpty() || smokeTabApplied) {
            return;
        }
        // The tab table holds plain id strings, so the smoke value is passed through as
        // written rather than parsed into an Identifier and back.
        String[] parts = wanted.split("/", 2);
        String theme = parts[0];
        String category = parts.length > 1 ? parts[1] : parts[0];
        int index = LevelPage.indexOf(tabs, theme, category);
        if (index >= 0) {
            openTab = tabs.get(index);
            smokeTabApplied = true;
            return;
        }
        // Not in the table yet. The screen starts from the client's built-in fallback page
        // list and the server's real one replaces it a frame later, so a one-shot attempt
        // opened the unclassified page instead and stayed there.
        openTab = null;
    }

    /** Recomputes the open page's rows and its pager, keeping the page when it still exists. */
    private void recomputeRows() {
        if (openTab == null && !tabs.isEmpty()) {
            openTab = tabs.get(0);
        }
        rows = LevelPage.rowsFor(levels, openTab);
    }

    /** Where the selected level ended up in a freshly received page; -1 when it is gone. */
    static int indexOfLevel(LevelPage.Rows rows, String levelId) {
        if (levelId == null || levelId.isEmpty()) {
            return -1;
        }
        for (int i = 0; i < rows.size(); i++) {
            if (levelId.equals(rows.get(i).id())) {
                return i;
            }
        }
        return -1;
    }

    /** Makes {@code index} of the open page the selection; out-of-range clears it. */
    private void selectRow(int index) {
        if (index < 0 || index >= rows.size()) {
            selectedRow = -1;
            selectedLevelId = "";
        } else {
            selectedRow = index;
            selectedLevelId = rows.get(index).id();
        }
        // Both actions follow the selection, not just "下一步".
        updateActionButtons();
    }

    /**
     * Enables "下一步" and "编辑关卡" from the current selection.
     *
     * <p>One method because the two used to be set in two places and one of them was
     * forgotten: selecting a card only enabled "下一步", so "编辑关卡" stayed greyed out for
     * every level including the player's own.
     *
     * <p>The action button also renames itself: for a level with a resumable save it goes
     * straight into that run (and then asks continue/restart), so "下一步" would be selling
     * a pre-game screen that no longer happens.
     */
    private void updateActionButtons() {
        LevelListS2C.LevelInfo selected = selectedRow >= 0 && selectedRow < rows.size()
                ? rows.get(selectedRow) : null;
        if (nextButton != null) {
            boolean locked = selected != null && selected.isLocked();
            boolean buyable = locked && selected.unlock().buyable();
            // One button, three meanings. A locked level that cannot be bought yet stays
            // disabled, so "下一步" never turns into a click that the server refuses.
            nextButton.setLabel(buyable ? "解锁 " + selected.unlock().cost() + " 金币"
                    : (locked ? "尚未解锁"
                    : (selected != null && selected.hasRunningSave() ? "继续游戏" : "下一步")));
            nextButton.setActive(selected != null && (!locked || buyable));
        }
        if (editButton != null) {
            editButton.setActive(selected != null);
        }
    }

    /**
     * Rebuilds the theme column and the category row from the tab table.
     *
     * <p>The column lists <b>themes</b> ({@link LevelPage#themeIds}), not pages: a theme with
     * three categories is still one button, and the categories are the row above the list.
     * The unclassified bucket is one of those themes, so it is always reachable - it is a
     * fixed page, not one that appears only when a level happens to be in it.
     *
     * <p>Only the widgets this method owns are re-added, so an open dialog is untouched -
     * the tab widgets have to be rebuilt whenever the tab table arrives, which after {@code
     * init()} is a moment later.
     */
    private void rebuildTabs() {
        for (AbstractTabButton holder : themeButtons) {
            widgets.remove(holder.button);
        }
        for (AbstractTabButton holder : categoryButtons) {
            widgets.remove(holder.button);
        }
        themeButtons.clear();
        categoryButtons.clear();
        if (tabs.isEmpty()) {
            return;
        }

        int gap = 4;
        List<String> themeIds = LevelPage.themeIds(tabs);
        int available = Math.max(40, themeTop - themeBottom);
        int buttonHeight = Math.min(34, Math.max(20, (available - gap * (themeIds.size() - 1))
                / Math.max(1, themeIds.size())));
        int y = themeTop - buttonHeight;
        for (String themeId : themeIds) {
            Button button = new Button(themeColumnX, y, themeColumnWidth, buttonHeight,
                    LevelPage.label(themeId, UNCATEGORIZED_KEY, UNCATEGORIZED_TEXT),
                    () -> switchToTheme(themeId));
            button.style(Button.Style.SEED_CHOOSER);
            addWidget(button);
            themeButtons.add(new AbstractTabButton(button, themeId));
            y -= buttonHeight + gap;
        }

        List<LevelPage.Tab> categories = openTab == null
                ? List.of() : LevelPage.pagesOfTheme(tabs, openTab.themeId());
        if (!categories.isEmpty()) {
            int categoryGap = 4;
            int buttonWidth = Math.min(160, Math.max(70, (categoryRowWidth
                    - categoryGap * (categories.size() - 1)) / categories.size()));
            int x = categoryRowX;
            for (LevelPage.Tab tab : categories) {
                String label = LevelPage.label(tab.categoryId(), UNCATEGORIZED_KEY, UNCATEGORIZED_TEXT);
                Button button = new Button(x, categoryRowY, buttonWidth, categoryRowHeight, label,
                        () -> switchToCategory(tab));
                button.style(Button.Style.SEED_CHOOSER);
                addWidget(button);
                categoryButtons.add(new AbstractTabButton(button, tab));
                x += buttonWidth + categoryGap;
            }
        }
        refreshTabState();
    }

    /** The open theme and the open category are disabled, so the open page cannot be re-picked. */
    private void refreshTabState() {
        for (AbstractTabButton holder : themeButtons) {
            holder.button.setActive(openTab == null || !holder.themeId.equals(openTab.themeId()));
        }
        for (AbstractTabButton holder : categoryButtons) {
            holder.button.setActive(openTab == null
                    || !holder.tab.categoryId().equals(openTab.categoryId()));
        }
    }

    /**
     * Opens a theme: the same category if it has one, otherwise its first page.
     *
     * <p>Keeping the category is what makes the column usable as a column - clicking through
     * themes should not silently change which category you are looking at.
     */
    private void switchToTheme(String themeId) {
        String category = openTab == null ? null : openTab.categoryId();
        int index = LevelPage.indexOf(tabs, themeId, category);
        if (index < 0) {
            LevelPage.Tab first = LevelPage.firstPageOfTheme(tabs, themeId);
            switchTo(first);
            return;
        }
        switchTo(tabs.get(index));
    }

    private void switchToCategory(LevelPage.Tab categoryTab) {
        switchTo(categoryTab);
    }

    /** Opens a page: the selection, if it lives there, comes with it. */
    private void switchTo(LevelPage.Tab tab) {
        if (tab == null || (openTab != null && tab.themeId().equals(openTab.themeId())
                && tab.categoryId().equals(openTab.categoryId()))) {
            return;
        }
        openTab = tab;
        recomputeRows();
        selectedRow = indexOfLevel(rows, selectedLevelId);
        if (selectedRow < 0) {
            selectedLevelId = "";
        }
        paging = ListPaging.of(totalRows(), pageSize());
        updateLayout();
        rebuildTabs();
        updateActionButtons();
    }

    private int totalRows() {
        return rows.size();
    }

    private int pageSize() {
        return Math.max(1, visibleRows);
    }

    /** Rebuilds the pager for the open page, keeping the page when it still exists. */
    private void refreshPaging() {
        paging = ListPaging.of(totalRows(), pageSize()).withFirstRow(paging.firstRow());
    }

    private void turnPage(int delta) {
        paging = paging.turn(delta);
    }

    @Override
    protected void onMouseClicked(double guiX, double guiY, int button) {
        if (inside(guiX, guiY, prevArrowX, prevArrowY, arrowWidth, arrowHeight)) {
            turnPage(-1);
            return;
        }
        if (inside(guiX, guiY, nextArrowX, nextArrowY, arrowWidth, arrowHeight)) {
            turnPage(1);
            return;
        }
        if (guiX >= gridX && guiX < gridX + gridWidth
                && guiY >= gridRenderBottom && guiY <= gridRenderTop) {
            int row = (int) ((gridRenderTop - guiY) / (rowHeight + rowGap));
            if (row >= 0 && row < visibleRows) {
                int index = paging.firstRow() + row;
                if (index >= 0 && index < rows.size()) {
                    selectRow(index);
                }
            }
        }
    }

    @Override
    protected void onMouseMoved(double guiX, double guiY) {
        prevHover = inside(guiX, guiY, prevArrowX, prevArrowY, arrowWidth, arrowHeight);
        nextHover = inside(guiX, guiY, nextArrowX, nextArrowY, arrowWidth, arrowHeight);
    }

    @Override
    protected void onMouseScrolled(double guiX, double guiY, double amount) {
        if (guiX >= gridX && guiX < gridX + gridWidth
                && guiY >= gridRenderBottom && guiY <= gridRenderTop) {
            // The wheel turns pages here, like the arrows: a one-row scroll left the grid
            // showing a mix of two pages, which is the duplication the arrows had.
            turnPage(amount > 0 ? -1 : 1);
        }
    }

    private static boolean inside(double x, double y, int rx, int ry, int rw, int rh) {
        return x >= rx && x < rx + rw && y >= ry && y < ry + rh;
    }

    /**
     * The action button's job, which depends on what is selected.
     *
     * <p>Four cases, one button: enter a level, continue a run, buy a locked level, or
     * refuse - in which case the button is disabled and says why, so a click can never
     * look like it did nothing.
     */
    private void openSetup() {
        LevelListS2C.LevelInfo selected = selected();
        if (selected == null) {
            return;
        }
        if (selected.isLocked()) {
            // Buying is the only useful thing a locked level can offer. Otherwise this is a
            // no-op on purpose: the row and the button already spell out what is missing,
            // and there is no toast channel outside a running level to say it twice.
            if (selected.unlock().buyable()) {
                client.buyLevelUnlock(selected.id());
            }
            return;
        }
        if (selected.hasRunningSave()) {
            // A run is already there: it is loaded and asked about (继续/重开) with no
            // pre-game screen at all. The team and the card bar come from the save, so
            // 关卡准备 and the seed chooser have nothing to ask - going through them is what
            // made the save prompt show up only after cards had been picked.
            client.enterLevelFromMenu(selected);
            return;
        }
        client.openScreen(new LevelSetupScreen(client, selected));
    }

    /** The selected level, or {@code null}; selection lives in {@link #selectedLevelId}. */
    private LevelListS2C.LevelInfo selected() {
        return selectedRow >= 0 && selectedRow < rows.size() ? rows.get(selectedRow) : null;
    }

    /** Opens the selected level in the editor. */
    private void editSelected() {
        LevelListS2C.LevelInfo selected = selected();
        if (selected == null) {
            return;
        }
        Identifier id = Identifier.tryParse(selected.id());
        if (id == null) {
            return;
        }
        client.openEditor(id);
    }

    /**
     * Asks for the new level's name, group and size before the editor opens.
     *
     * <p>The open page is offered as the group: a level created while looking at
     * 庭院/冒险模式 starts out there rather than in some default bucket.
     */
    private void openCreateDialog() {
        String theme = openTab == null ? UNCATEGORIZED_ID : openTab.themeId();
        String category = openTab == null ? UNCATEGORIZED_ID : openTab.categoryId();
        LevelCreateDialog dialog = LevelCreateDialog.create(client, theme, category,
                request -> client.openNewLevelEditor(request.id(), request.name(),
                        request.width(), request.height()));
        showDialog(dialog);
    }

    @Override
    public void render() {
        client.beginGuiView();
        renderBackground(0.1F, 0.2F, 0.12F);
        String title = GuiLang.raw("pvzce.level_select", "选择关卡");
        float scale = Math.min(2.2F, client.guiHeight() / 100F);
        client.font().draw(title, (client.guiWidth() - client.font().width(title, scale)) / 2F,
                client.guiHeight() - client.font().lineHeight(scale) - 8, scale, 1F, 1F, 1F, 1F);
        drawCoinCounter();

        // The theme column's own label, so the column reads as themes and not as an
        // unlabelled stripe of buttons.
        if (openTab != null) {
            String themeName = LevelPage.label(openTab.themeId(), UNCATEGORIZED_KEY, UNCATEGORIZED_TEXT);
            client.font().draw(themeName, themeColumnX, themeTop + 4, 0.8F, 0.85F, 0.9F, 0.75F, 1F);
        }

        if (rows.isEmpty()) {
            String empty = levels.isEmpty()
                    ? GuiLang.raw("pvzce.loading", "载入中…")
                    : GuiLang.raw("pvzce.level_empty_page", "这个分类下还没有关卡");
            client.font().draw(empty, (client.guiWidth() - client.font().width(empty, 1F)) / 2F,
                    (gridRenderTop + gridRenderBottom) / 2F, 1F, 0.9F, 0.9F, 0.9F, 1F);
        } else {
            renderRows();
        }
        for (var widget : widgets) {
            widget.render(client);
        }
    }

    private void renderRows() {
        int firstRow = paging.firstRow();
        int lastRow = Math.min(totalRows, firstRow + visibleRows);
        for (int row = firstRow; row < lastRow; row++) {
            LevelListS2C.LevelInfo level = rows.get(row);
            float cardX = gridX;
            float cardY = gridRenderTop - rowHeight - (row - firstRow) * (rowHeight + rowGap);
            boolean selected = row == selectedRow;

            client.drawSolid(cardX - 2, cardY - 2, gridWidth + 4, rowHeight + 4, 0.1F,
                    0F, 0F, 0F, 0.35F);
            client.drawSolid(cardX, cardY, gridWidth, rowHeight, 0.1F,
                    0.22F, 0.14F, 0.06F, 0.88F);
            if (selected) {
                client.drawSolid(cardX - 3, cardY - 3, gridWidth + 6, rowHeight + 6, 0.2F,
                        1F, 0.9F, 0.2F, 0.75F);
            }

            boolean locked = level.isLocked();
            float iconSize = Math.max(20F, Math.min(rowHeight - 10F, 40F));
            float iconX = cardX + 10F;
            float iconY = cardY + (rowHeight - iconSize) / 2F;
            // A locked level is drawn, but dimmed: hiding it would leave the player with no
            // idea that there is more to do, and the original shows its next level too.
            client.drawTexture(iconTexture(level.icon()), iconX, iconY, iconSize, iconSize,
                    0.2F, 1F, 1F, 1F, locked ? 0.45F : 1F);

            float textX = iconX + iconSize + 10F;
            if (locked) {
                float lockSize = Math.max(12F, iconSize * 0.5F);
                client.drawTexture(LOCK_ICON, textX, cardY + (rowHeight - lockSize) / 2F,
                        lockSize, lockSize, 0.3F, 1F, 1F, 1F, 0.9F);
                textX += lockSize + 6F;
            }
            float availableWidth = Math.max(30F, cardX + gridWidth - 10F - textX);
            String name = level.name().isEmpty() ? level.id() : level.name();
            float nameScale = 1.1F;
            while (nameScale > 0.55F && client.font().width(name, nameScale) > availableWidth) {
                nameScale -= 0.05F;
            }
            // A locked row says what is missing instead of "未通关": the condition is the
            // only thing the player can act on.
            String status = locked ? level.unlock().reason() : statusLabel(level.status());
            if (locked && level.unlock().cost() > 0) {
                status = status + "，或 " + level.unlock().cost() + " 金币";
            }
            float nameY = cardY + rowHeight / 2F - client.font().lineHeight(nameScale) / 2F;
            if (!status.isEmpty()) {
                nameY += 6F;
            }
            client.font().draw(name, textX, nameY, nameScale,
                    locked ? 0.65F : 1F, locked ? 0.65F : 1F, locked ? 0.6F : 1F, 1F);
            if (!status.isEmpty()) {
                client.font().draw(status, textX, cardY + rowHeight / 2F - 14F,
                        0.7F, 1F, locked ? 0.6F : 0.9F, locked ? 0.4F : 0.5F, 1F);
            }
            if (selected) {
                String id = level.id();
                float idScale = 0.6F;
                client.font().draw(id, cardX + gridWidth - 10F - client.font().width(id, idScale),
                        cardY + 5F, idScale, 0.7F, 0.72F, 0.65F, 1F);
            }
        }

        drawArrow(true, prevArrowX, prevArrowY, paging.hasPrevious(), prevHover);
        drawArrow(false, nextArrowX, nextArrowY, paging.hasNext(), nextHover);

        String pageText = paging.pageNumber() + "/" + paging.pageCount();
        client.font().draw(pageText, (client.guiWidth() - client.font().width(pageText, 0.9F)) / 2F,
                themeBottom - 18, 0.9F, 1F, 1F, 1F, 1F);
    }

    private void drawArrow(boolean left, int x, int y, boolean enabled, boolean hovered) {
        Identifier texture = !enabled ? ARROW_DISABLED : (hovered ? ARROW_HIGHLIGHT : ARROW);
        float u0 = left ? 1F : 0F;
        float u1 = left ? 0F : 1F;
        client.drawTextureRegion(texture, u0, 0F, u1, 1F, x, y, arrowWidth, arrowHeight, 0.3F,
                1F, 1F, 1F, enabled ? 1F : 0.7F);
    }

    private static Identifier iconTexture(String icon) {
        String path = switch (icon == null ? "day" : icon) {
            case "night" -> "almanac_groundnight";
            case "pool" -> "almanac_groundpool";
            case "night_pool" -> "almanac_groundnightpool";
            case "roof" -> "almanac_groundroof";
            default -> "almanac_groundday";
        };
        return Identifier.withDefaultNamespace("textures/gui/screen/level/" + path);
    }

    /**
     * Back, to whoever opened this screen.
     *
     * <p>Normally that is the world list underneath. After a level ends the client
     * replaces the whole screen stack with this screen ({@code showLevelList}), so
     * there is nothing to pop and plain {@code closeScreen()} did nothing at all - the
     * 返回 button was simply dead. Falling through to the world list is the same
     * destination the stack would have offered.
     */
    private void goBack() {
        if (client.screenDepth() > 1) {
            client.closeScreen();
        } else {
            client.showWorldSelect();
        }
    }

    /** ESC goes to the same place the 返回 button does. */
    @Override
    public void requestClose() {
        goBack();
    }

    /** Shared with the level setup screen so the same state cannot read differently. */
    static String statusLabel(String status) {
        return com.pvzce.client.gui.GuiStatusText.label(status);
    }

    /**
     * The world's coin balance, top-right beside the title.
     *
     * <p>The wallet is per world, and this screen is always looking at exactly one
     * world, so the number needs no qualifier. The money bag is the original's own
     * counter art; the coin sprite is the fallback when a pack drops that texture.
     */
    private void drawCoinCounter() {
        float iconSize = Math.max(20F, Math.min(34F, client.guiHeight() * 0.05F));
        Identifier icon = client.hasTexture(MONEY_BAG) ? MONEY_BAG : COIN_ICON;
        float x = client.guiWidth() - iconSize - 12F;
        float y = client.guiHeight() - iconSize - 8F;
        client.drawTexture(icon, x, y, iconSize, iconSize, 0.35F, 1F, 1F, 1F, 1F);
        String coins = String.valueOf(client.profile().coins());
        client.font().draw(coins, x - client.font().width(coins, 1F) - 6F,
                y + (iconSize - client.font().lineHeight(1F)) / 2F, 1F, 1F, 0.95F, 0.5F, 1F);
    }

}
