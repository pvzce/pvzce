package com.pvzce.client.gui.screens;

import com.pvzce.api.util.Identifier;
import com.pvzce.client.PvzceClient;
import com.pvzce.client.gui.Navigation;
import com.pvzce.client.input.ScrollRegion;
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
    private static final Identifier COIN_ICON = Identifier.withDefaultNamespace("textures/resource/coin_gold");

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

    /**
     * The medal a beaten mini-game earns, pinned to the right of its row.
     *
     * <p>The original's own trophy sprite ({@code refer/im7/images/trophy.png}), drawn at its
     * own aspect ratio: it is a cup, and stretching it to a square would read as a shield.
     */
    private static final Identifier TROPHY =
            Identifier.withDefaultNamespace("textures/gui/screen/level/minigame_trophy");
    private static final float TROPHY_ART_WIDTH = 83F;
    private static final float TROPHY_ART_HEIGHT = 63F;

    /** True once {@code pvzce.smokeTab} has found its page; see {@link #openSmokeTab}. */
    private boolean smokeTabApplied;
    /**
     * True once the page was chosen on purpose, by the player or by {@code pvzce.smokeTab}.
     *
     * <p>The screen picks a page it can only see part of: the level list arrives with no
     * {@code theme}/{@code category} on it, and the server's tab table arrives a round trip
     * after that. Whatever page is open at that moment was not really chosen, so a table that
     * then turns out to hold real pages is allowed to move off it - without this, the bucket
     * picked from an empty list survived every later refresh (the bucket is always in the
     * table, appended last), and the player opened 选择关卡 on "这个分类下还没有关卡".
     */
    private boolean pagePickedByUser;

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
    /** The 剧情 switch; its label carries the state, so it is re-labelled on every click. */
    private Button storyToggle;
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
        // The almanac belongs here rather than on the title screen: this is the screen where
        // "what may I bring into a level" is the question the player is asking, and the book
        // answers both halves of it - which cards are unlocked, and what each one is.
        addWidget(new Button(startX + (quadWidth + rowGap) * 2, actionY, quadWidth, actionHeight,
                GuiLang.raw("gui.pvzce.almanac.title", "图鉴"),
                () -> client.openScreen(new AlmanacScreen(client))).style(Button.Style.SEED_CHOOSER));
        addWidget(new Button(startX + (quadWidth + rowGap) * 3, actionY, quadWidth, actionHeight,
                GuiLang.raw("pvzce.back", "返回"), this::requestClose).style(Button.Style.SEED_CHOOSER));

        addWidget(new Button(centerX(customWidth), createY, customWidth, createHeight,
                GuiLang.raw("pvzce.editor.new_level", "新建关卡"), this::openCreateDialog)
                .style(Button.Style.SEED_CHOOSER));

        addWidget(storyToggle(guiW, guiH));
        updateActionButtons();
    }

    /**
     * The 剧情 switch, in the screen's top-left corner.
     *
     * <p>A plain {@link Button} whose label carries the state, like the world screen's sandbox
     * switch: a click toggles it, and the label is the checkbox. It belongs on this screen
     * because this is where a level is chosen - the one moment before a conversation would
     * play - and it is saved with the rest of the client config, so "I have read 1-5's
     * dialogue three times" is answered once instead of every run.
     */
    private Button storyToggle(int guiW, int guiH) {
        int height = Math.max(22, Math.min(30, guiH / 24));
        int width = Math.max(110, Math.min(themeColumnWidth, guiW / 4));
        storyToggle = new Button(themeColumnX, guiH - height - 8, width, height, storyLabel(),
                this::toggleStory).style(Button.Style.SEED_CHOOSER);
        return storyToggle;
    }

    private void toggleStory() {
        client.setStoryEnabled(!client.storyEnabled());
        if (storyToggle != null) {
            storyToggle.setLabel(storyLabel());
        }
    }

    private String storyLabel() {
        return GuiLang.raw("pvzce.story", "剧情") + "：" + (client.storyEnabled() ? "开" : "关");
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
            boolean keep = index >= 0 && (pagePickedByUser || !openTab.uncategorized());
            openTab = keep ? tabs.get(index) : null;
        }
        if (openTab == null && !tabs.isEmpty()) {
            // Prefer the page the selection is on, so reopening the screen lands where the
            // player left off instead of at the first page.
            int selectedTab = LevelPage.tabIndexOf(tabs, findSelected());
            openTab = selectedTab < 0 ? LevelPage.firstRealPage(tabs) : tabs.get(selectedTab);
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
            openTab = selectedTab < 0 ? LevelPage.firstRealPage(tabs) : tabs.get(selectedTab);
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
            pagePickedByUser = true;
            smokeTabApplied = true;
            return;
        }
        // Not in the table yet. The screen starts from the client's built-in fallback page
        // list and the server's real one replaces it a frame later, so a one-shot attempt
        // opened the unclassified page instead and stayed there.
        openTab = null;
    }

    /**
     * Recomputes the open page's rows and its pager, keeping the page when it still exists.
     *
     * <p>Filling in a page here is a guess, not a choice: this runs from {@code tick()} with
     * whatever table and list have arrived so far, so it deliberately leaves
     * {@link #pagePickedByUser} alone and a later, real table may still move off it.
     */
    private void recomputeRows() {
        if (openTab == null && !tabs.isEmpty()) {
            openTab = LevelPage.firstRealPage(tabs);
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
            // One button, four meanings. A locked level that cannot be bought yet stays
            // disabled, so "下一步" never turns into a click that the server refuses.
            nextButton.setLabel(selected != null && selected.isCollection() ? "进入"
                    : buyable ? "解锁 " + selected.unlock().cost() + " 金币"
                    : (locked ? "尚未解锁"
                    : (selected != null && selected.hasRunningSave() ? "继续游戏" : "下一步")));
            nextButton.setActive(selected != null && (!locked || buyable));
        }
        if (editButton != null) {
            // A collection is not a level: there is no file behind it and nothing to open in the
            // editor, so the button greys out rather than opening whatever shares its id.
            editButton.setActive(selected != null && !selected.isCollection());
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
                    LevelPage.label(themeId, "level_theme", UNCATEGORIZED_KEY, UNCATEGORIZED_TEXT),
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
                String label = LevelPage.label(tab.categoryId(), "level_category", UNCATEGORIZED_KEY, UNCATEGORIZED_TEXT);
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
        // Only a click lands here, so from now on the open page is the player's to keep -
        // including the bucket, which a refresh must not move them off.
        pagePickedByUser = true;
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
                    int previous = selectedRow;
                    selectRow(index);
                    // A second click on the row that is already selected opens it - the one
                    // interaction this screen has besides the button, and the one a player tries
                    // first on a row that looks like a box. Read off "was it already selected"
                    // rather than off a click timer: the selection is the state the player can see,
                    // so a double click is exactly "clicked it twice", with no window to tune.
                    if (previous == index) {
                        openSetup();
                    }
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
        if (gridTurnsPageAt(guiX, guiY)) {
            // The wheel turns pages here, like the arrows: a one-row scroll left the grid
            // showing a mix of two pages, which is the duplication the arrows had.
            turnPage(amount > 0 ? -1 : 1);
        }
    }

    /** True when the card grid is what is under this point. */
    private boolean gridTurnsPageAt(double guiX, double guiY) {
        return guiX >= gridX && guiX < gridX + gridWidth
                && guiY >= gridRenderBottom && guiY <= gridRenderTop;
    }

    /**
     * The card grid is a vertical scroll region, one row of finger travel per row of cards.
     *
     * <p>{@link ScrollRegion.Swipe#DRAGS_CONTENT}: rows run downward from {@code gridRenderTop}, so
     * dragging the cards up brings the next page up, which is what the finger suggests. The arrows and
     * the wheel keep working exactly as they did - this only decides that a swipe over the grid is a
     * scroll rather than a press on whichever level happened to be under the finger.
     */
    @Override
    protected ScrollRegion onScrollRegionAt(double guiX, double guiY) {
        if (!gridTurnsPageAt(guiX, guiY) || (!paging.hasNext() && !paging.hasPrevious())) {
            return null;
        }
        return ScrollRegion.dragsContent(ScrollRegion.Axis.VERTICAL, rowHeight + rowGap);
    }

    private static boolean inside(double x, double y, int rx, int ry, int rw, int rh) {
        return x >= rx && x < rx + rw && y >= ry && y < ry + rh;
    }

    /**
     * The action button's job, which depends on what is selected.
     *
     * <p>Four cases, one button: enter a level, continue a run, buy a locked level, or
     * refuse - in which case the button is disabled and says why, so a click can never
     * look like it did nothing. A collection adds the fifth: open the box, which is what
     * "进入" says.
     */
    private void openSetup() {
        LevelListS2C.LevelInfo selected = selected();
        if (selected == null) {
            return;
        }
        if (selected.isCollection()) {
            // A box is not a level and has nothing to prepare: the row it opens is a list, and the
            // levels inside it go through this same decision when they are picked from there.
            client.openScreen(new LevelCollectionScreen(client, selected.id()));
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
        // Everything past "which level" is the one entry decision, including whether 关卡准备
        // has anything to ask. The three cases used to be spelled out here as well, which is
        // how the smoke hook and the editor's 测试 reached the seed chooser for a level that
        // offers a team choice.
        client.enterLevelFromMenu(selected);
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
        client.fonts().button().draw(title, (client.guiWidth() - client.fonts().button().width(title, scale)) / 2F,
                client.guiHeight() - client.fonts().body().lineHeight(scale) - 8, scale, 1F, 1F, 1F, 1F);
        drawCoinCounter();
        drawDifficultyBadge();

        // The theme column's own label, so the column reads as themes and not as an
        // unlabelled stripe of buttons.
        if (openTab != null) {
            String themeName = LevelPage.label(openTab.themeId(), "level_theme", UNCATEGORIZED_KEY, UNCATEGORIZED_TEXT);
            client.fonts().body().draw(themeName, themeColumnX, themeTop + 4, 0.8F, 0.85F, 0.9F, 0.75F, 1F);
        }

        if (rows.isEmpty()) {
            String empty = levels.isEmpty()
                    ? GuiLang.raw("pvzce.loading", "载入中…")
                    : GuiLang.raw("pvzce.level_empty_page", "这个分类下还没有关卡");
            client.fonts().body().draw(empty, (client.guiWidth() - client.fonts().body().width(empty, 1F)) / 2F,
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
        // Built once per frame rather than asked per row: a collection's progress line counts how
        // many of its members are cleared, and the members are rows of this same list.
        LevelRowRenderer renderer = new LevelRowRenderer(client, LevelRowRenderer.clearedIndex(levels));
        for (int row = firstRow; row < lastRow; row++) {
            LevelListS2C.LevelInfo level = rows.get(row);
            float cardX = gridX;
            float cardY = gridRenderTop - rowHeight - (row - firstRow) * (rowHeight + rowGap);
            renderer.render(level, cardX, cardY, gridWidth, rowHeight, row == selectedRow);
        }

        drawArrow(true, prevArrowX, prevArrowY, paging.hasPrevious(), prevHover);
        drawArrow(false, nextArrowX, nextArrowY, paging.hasNext(), nextHover);

        String pageText = paging.pageNumber() + "/" + paging.pageCount();
        client.fonts().body().draw(pageText, (client.guiWidth() - client.fonts().body().width(pageText, 0.9F)) / 2F,
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
        return LevelRowRenderer.iconTexture(icon);
    }

    /**
     * Back, to whoever opened this screen - declared rather than inferred.
     *
     * <p>The level list is reachable two ways, and they want different destinations:
     *
     * <ul>
     *   <li>Drilled into from the world list (settings, the editor, a level's 编辑关卡):
     *       {@link Navigation#POP} reveals it, because it is still on the stack.</li>
     *   <li>Installed by the client as the root after a level ended or a world was chosen
     *       ({@code showLevelList()}): there is nothing underneath, and the world list is the
     *       step back the stack would have offered.</li>
     * </ul>
     *
     * <p>The depth test that used to live in {@code goBack()} survives as the <em>condition</em>
     * here, which is the honest version of it: both facts are about how this screen was
     * entered, and the screen is the only place that knows them. What is gone is the version
     * that popped and hoped - a screen alone on the stack can no longer be popped into a
     * client with nothing to render.
     */
    @Override
    public Navigation backTarget() {
        return client.screenDepth() > 1
                ? Navigation.POP
                : Navigation.replaceRoot(TitleScreen::new);
    }

    /** ESC goes to the same place the 返回 button does. */
    @Override
    public void requestClose() {
        client.navigateBack();
    }

    /** Shared with the level setup screen so the same state cannot read differently. */
    static String statusLabel(String status) {
        return com.pvzce.client.gui.GuiStatusText.label(status);
    }

    /**
     * The page currently open, as {@code theme/category}; diagnostics and tests.
     *
     * <p>{@code tick()} is what keeps this current - it makes the same two calls
     * {@code init()} does - and unlike {@code init()} it needs no window, so a test can drive
     * the screen's page choice without a GL context or a music engine.
     */
    String openPageForTest() {
        return openTab == null ? "<none>" : openTab.themeId() + "/" + openTab.categoryId();
    }

    /** Opens a page by id, exactly what a click in the theme column or the category row does. */
    void switchToPageForTest(String themeId, String categoryId) {
        int index = LevelPage.indexOf(tabs, themeId, categoryId);
        if (index >= 0) {
            switchTo(tabs.get(index));
        }
    }

    /**
     * The tier this world is playing, under the title on the left.
     *
     * <p>Next to the coin counter's corner rather than in it: the counter is "what you have" and
     * this is "how hard it is", and the two used to be one line only because one of them did not
     * exist. It is drawn from the profile the server sent, never from a local choice - a client
     * that showed its own would keep showing it after the server refused.
     */
    private void drawDifficultyBadge() {
        com.pvzce.common.level.Difficulty tier = client.profile().difficulty();
        if (tier.isOriginal()) {
            // The original's difficulty says nothing: a badge on every ordinary world would be
            // noise, and "no badge" then means exactly what it says.
            return;
        }
        String name = GuiLang.raw("pvzce.difficulty." + tier.key(), tier.key());
        String text = GuiLang.raw("pvzce.difficulty.badge", "难度：{0}").replace("{0}", name);
        // Under the "剧情" widget rather than on the top line with it: widgets are drawn after the
        // screen's own text, so the first attempt sat behind that button's plate and was never
        // seen. This offset is below it and above the theme column, which is the free band here.
        client.fonts().body().draw(text, 12F, client.guiHeight() - 58F,
                1F, 1F, 0.82F, 0.62F, 1F);
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
        client.fonts().body().draw(coins, x - client.fonts().body().width(coins, 1F) - 6F,
                y + (iconSize - client.fonts().body().lineHeight(1F)) / 2F, 1F, 1F, 0.95F, 0.5F, 1F);
    }

}
