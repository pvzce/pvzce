package com.pvzce.client.gui.screens;

import com.pvzce.api.util.Identifier;
import com.pvzce.client.PvzceClient;
import com.pvzce.client.gui.GuiLang;
import com.pvzce.client.gui.Navigation;
import com.pvzce.client.gui.Screen;
import com.pvzce.client.gui.components.Button;
import com.pvzce.client.gui.layout.GuiLayout;
import com.pvzce.client.gui.layout.ListPaging;
import com.pvzce.client.input.ScrollRegion;
import com.pvzce.common.network.packet.LevelListS2C;

import java.util.ArrayList;
import java.util.List;

/**
 * The levels inside one collection: 白天草坪's ten chapters, 变异's four tiers and their tutorial.
 *
 * <p>Opened by picking a collection's row in the level list, and it is the same list one level
 * down: the same rows, drawn by the same {@link LevelRowRenderer}, with the same locks, saves and
 * trophies on them. What changes is where the rows come from - the collection's member list rather
 * than a theme and a category - and what "back" means: this screen is pushed on the stack, so
 * leaving it reveals the list it came from, still on the same page and still on the same selection.
 *
 * <p>It reads the collection and its members out of the client's own level list every frame. That
 * list is the same snapshot the list screen draws, so a level that is unlocked while this screen is
 * open - bought, or opened by a patch - is unlocked here on the next tick, with no second copy of
 * the state to keep in step.
 */
public final class LevelCollectionScreen extends Screen {
    private static final Identifier BACKGROUND =
            Identifier.withDefaultNamespace("textures/gui/screen/level/challenge_background");
    private static final Identifier ARROW =
            Identifier.withDefaultNamespace("textures/gui/screen/level/zombatar_next_button");
    private static final Identifier ARROW_HIGHLIGHT =
            Identifier.withDefaultNamespace("textures/gui/screen/level/zombatar_next_button_highlight");
    private static final Identifier ARROW_DISABLED =
            Identifier.withDefaultNamespace("textures/gui/screen/level/zombatar_next_button_disabled");

    /** The collection this screen is showing; the id is the whole of what it was opened with. */
    private final String collectionId;
    private LevelListS2C.LevelInfo collection;
    private LevelPage.Rows rows = LevelPage.Rows.empty();

    private int selectedRow = -1;
    /**
     * The selected level's id, which is what the selection actually is.
     *
     * <p>The row index is only its position in a list that a refresh can shrink, exactly as on the
     * level list: a slot that outlived the list it came from would make the button act on whichever
     * level happened to land there.
     */
    private String selectedLevelId = "";
    private ListPaging paging = ListPaging.of(0, 1);
    private int rowHeight = 48;
    private int rowGap = 6;
    private int gridX;
    private int gridWidth;
    private int gridRenderTop;
    private int gridRenderBottom;
    private int visibleRows;
    private int titleReserve;
    private int actionY;
    private int actionHeight;
    private int backY;
    private int backHeight;
    private Button nextButton;
    private String lastSignature = "";

    public LevelCollectionScreen(PvzceClient client, String collectionId) {
        super(client);
        this.collectionId = collectionId == null ? "" : collectionId;
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
        int guiW = client.guiWidth();
        int guiH = client.guiHeight();
        titleReserve = Math.max(44, Math.min(72, guiH / 5));
        int bottomHeight = GuiLayout.fitHeight(guiH, 40, 1, titleReserve, 0);
        backHeight = bottomHeight;
        backY = 4;
        actionY = backY + backHeight + 10;
        actionHeight = GuiLayout.fitHeight(guiH, 44, 1, titleReserve, actionY);

        updateRows(client.levelList());
        updateLayout();

        int quadWidth = Math.min(170, (guiW - 16 - 8 * 3) / 4);
        int startX = centerX(quadWidth * 4 + 8 * 3);
        nextButton = new Button(startX, actionY, quadWidth, actionHeight, "下一步", this::openSetup)
                .style(Button.Style.SEED_CHOOSER);
        addWidget(nextButton);
        addWidget(new Button(startX + (quadWidth + 8) * 3, actionY, quadWidth, actionHeight,
                GuiLang.raw("pvzce.back", "返回"), this::requestClose).style(Button.Style.SEED_CHOOSER));
        updateActionButtons();
    }

    @Override
    public void tick() {
        if (updateRows(client.levelList())) {
            updateLayout();
        }
    }

    /**
     * Re-reads the collection and its members, and says whether anything changed.
     *
     * <p>One signature over both, because they are one thing: the members are rows of the list and
     * the collection is a summary of them, so a refresh that changed a member's lock changed the
     * box's progress line as well.
     */
    private boolean updateRows(List<LevelListS2C.LevelInfo> levels) {
        LevelListS2C.LevelInfo found = LevelPage.collection(levels, collectionId);
        LevelPage.Rows next = LevelPage.membersOf(levels, found);
        String signature = String.valueOf(found) + next;
        if (signature.equals(lastSignature)) {
            return false;
        }
        lastSignature = signature;
        collection = found;
        rows = next;
        selectedRow = LevelSelectScreen.indexOfLevel(rows, selectedLevelId);
        if (selectedRow < 0) {
            selectedLevelId = "";
        }
        paging = ListPaging.of(rows.size(), Math.max(1, visibleRows))
                .withFirstRow(paging.firstRow());
        return true;
    }

    /**
     * The list area, and nothing else: there is no theme column and no category row here.
     *
     * <p>The collection is already the answer to both questions - it says which page it is on by
     * being opened from it - so the room those two cost goes to the rows.
     */
    private void updateLayout() {
        if (actionHeight <= 0) {
            return;
        }
        int guiW = client.guiWidth();
        int guiH = client.guiHeight();
        int margin = 12;
        int arrowGap = 10;
        int arrowWidth = 40;
        int arrowHeight = 46;

        int listTop = guiH - titleReserve;
        int listBottom = actionY + actionHeight + 24;
        int listHeight = Math.max(60, listTop - listBottom);
        int maxRowHeight = Math.min(64, Math.max(34, (guiH - titleReserve) / 7));
        rowHeight = maxRowHeight;
        visibleRows = Math.max(1, (listHeight + rowGap) / (rowHeight + rowGap));
        int actualHeight = visibleRows * (rowHeight + rowGap) - rowGap;

        gridX = margin + arrowWidth + arrowGap;
        gridWidth = Math.max(120, guiW - margin * 2 - (arrowWidth + arrowGap) * 2);
        gridRenderTop = listTop - Math.max(0, (listHeight - actualHeight) / 2);
        gridRenderBottom = gridRenderTop - actualHeight;
        // The arrows are laid out from the grid rather than stored: this screen has one list and
        // nothing else that could move them, so there is no second place for them to be wrong.
        paging = ListPaging.of(rows.size(), Math.max(1, visibleRows))
                .withFirstRow(paging.firstRow());
    }

    /** The arrow's rect, derived from the grid - see {@link #updateLayout}. */
    private int arrowX(boolean left) {
        int arrowWidth = 40;
        return left ? Math.max(8, gridX - 10 - arrowWidth) : client.guiWidth() - 12 - arrowWidth;
    }

    private int arrowY() {
        return (gridRenderTop + gridRenderBottom) / 2 - 23;
    }

    private LevelListS2C.LevelInfo selected() {
        return selectedRow >= 0 && selectedRow < rows.size() ? rows.get(selectedRow) : null;
    }

    private void selectRow(int index) {
        if (index < 0 || index >= rows.size()) {
            selectedRow = -1;
            selectedLevelId = "";
        } else {
            selectedRow = index;
            selectedLevelId = rows.get(index).id();
        }
        updateActionButtons();
    }

    /**
     * Enters the selected level, exactly as the level list would.
     *
     * <p>Through {@code client.enterLevelFromMenu}, which is the whole entry decision: whether the
     * level has a run waiting, whether it asks which side the player wants, and whether it needs the
     * seed chooser at all. A second path here would be a second answer to all three.
     */
    private void openSetup() {
        LevelListS2C.LevelInfo selected = selected();
        if (selected == null || selected.isLocked()) {
            return;
        }
        client.enterLevelFromMenu(selected);
    }

    private void updateActionButtons() {
        LevelListS2C.LevelInfo selected = selected();
        if (nextButton == null) {
            return;
        }
        boolean locked = selected != null && selected.isLocked();
        nextButton.setLabel(locked ? "尚未解锁"
                : (selected != null && selected.hasRunningSave() ? "继续游戏" : "下一步"));
        nextButton.setActive(selected != null && !locked);
    }

    @Override
    protected void onMouseClicked(double guiX, double guiY, int button) {
        if (inside(guiX, guiY, arrowX(true), arrowY(), 40, 46)) {
            paging = paging.turn(-1);
            return;
        }
        if (inside(guiX, guiY, arrowX(false), arrowY(), 40, 46)) {
            paging = paging.turn(1);
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
                    if (previous == index) {
                        openSetup();
                    }
                }
            }
        }
    }

    @Override
    protected void onMouseScrolled(double guiX, double guiY, double amount) {
        if (guiX >= gridX && guiX < gridX + gridWidth
                && guiY >= gridRenderBottom && guiY <= gridRenderTop) {
            paging = paging.turn(amount > 0 ? -1 : 1);
        }
    }

    @Override
    protected ScrollRegion onScrollRegionAt(double guiX, double guiY) {
        if (guiX < gridX || guiX >= gridX + gridWidth
                || guiY < gridRenderBottom || guiY > gridRenderTop
                || (!paging.hasNext() && !paging.hasPrevious())) {
            return null;
        }
        return ScrollRegion.dragsContent(ScrollRegion.Axis.VERTICAL, rowHeight + rowGap);
    }

    private static boolean inside(double x, double y, int rx, int ry, int rw, int rh) {
        return x >= rx && x < rx + rw && y >= ry && y < ry + rh;
    }

    @Override
    public void render() {
        client.beginGuiView();
        renderBackground(0.1F, 0.2F, 0.12F);
        String title = LevelPage.collectionLabel(collectionId);
        float scale = Math.min(2.2F, client.guiHeight() / 100F);
        client.fonts().button().draw(title,
                (client.guiWidth() - client.fonts().button().width(title, scale)) / 2F,
                client.guiHeight() - client.fonts().body().lineHeight(scale) - 8,
                scale, 1F, 1F, 1F, 1F);

        if (rows.isEmpty()) {
            // Two different situations, two different sentences: a box the client has not been told
            // about yet is loading, and one with nothing drawable in it is the pack's problem.
            String empty = collection == null
                    ? GuiLang.raw("pvzce.loading", "载入中…")
                    : GuiLang.raw("pvzce.level_empty_page", "这个分类下还没有关卡");
            client.fonts().body().draw(empty,
                    (client.guiWidth() - client.fonts().body().width(empty, 1F)) / 2F,
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
        int lastRow = Math.min(rows.size(), firstRow + visibleRows);
        LevelRowRenderer renderer =
                new LevelRowRenderer(client, LevelRowRenderer.clearedIndex(client.levelList()));
        for (int row = firstRow; row < lastRow; row++) {
            float cardY = gridRenderTop - rowHeight - (row - firstRow) * (rowHeight + rowGap);
            renderer.render(rows.get(row), gridX, cardY, gridWidth, rowHeight, row == selectedRow);
        }
        drawArrow(true, paging.hasPrevious());
        drawArrow(false, paging.hasNext());
        String pageText = paging.pageNumber() + "/" + paging.pageCount();
        client.fonts().body().draw(pageText,
                (client.guiWidth() - client.fonts().body().width(pageText, 0.9F)) / 2F,
                gridRenderBottom - 18, 0.9F, 1F, 1F, 1F, 1F);
    }

    private void drawArrow(boolean left, boolean enabled) {
        Identifier texture = !enabled ? ARROW_DISABLED : ARROW;
        float u0 = left ? 1F : 0F;
        float u1 = left ? 0F : 1F;
        client.drawTextureRegion(texture, u0, 0F, u1, 1F, arrowX(left), arrowY(), 40, 46, 0.3F,
                1F, 1F, 1F, enabled ? 1F : 0.7F);
    }

    /**
     * Back to the list this was opened from.
     *
     * <p>{@code POP}, unconditionally: the screen is only ever pushed by the level list, so there is
     * always something under it. The level list's own back target has a second case - it can be
     * installed as the root - and this screen has none, which is why it does not repeat that logic.
     */
    @Override
    public Navigation backTarget() {
        return Navigation.POP;
    }

    @Override
    public void requestClose() {
        client.navigateBack();
    }

    /** The collection on screen, for diagnostics and tests. */
    String collectionIdForTest() {
        return collectionId;
    }

    /** The row ids on screen, in order, for diagnostics and tests. */
    List<String> rowIdsForTest() {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            ids.add(rows.get(i).id());
        }
        return ids;
    }
}
