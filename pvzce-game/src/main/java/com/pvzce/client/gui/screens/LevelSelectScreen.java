package com.pvzce.client.gui.screens;

import com.pvzce.api.util.Identifier;
import com.pvzce.client.PvzceClient;
import com.pvzce.client.gui.Screen;
import com.pvzce.client.gui.components.Button;
import com.pvzce.client.gui.layout.GuiLayout;
import com.pvzce.common.network.packet.LevelListS2C;
import com.pvzce.common.network.packet.RequestLevelListC2S;

import java.util.ArrayList;
import java.util.List;

/** Dynamic LEVELS-registry-driven level select screen (Almanac-style card grid). */
public final class LevelSelectScreen extends Screen {
    private static final Identifier BACKGROUND = Identifier.withDefaultNamespace("textures/gui/screen/level/challenge_background");
    private static final Identifier ARROW = Identifier.withDefaultNamespace("textures/gui/screen/level/zombatar_next_button");
    private static final Identifier ARROW_HIGHLIGHT =
            Identifier.withDefaultNamespace("textures/gui/screen/level/zombatar_next_button_highlight");
    private static final Identifier ARROW_DISABLED =
            Identifier.withDefaultNamespace("textures/gui/screen/level/zombatar_next_button_disabled");

    private final List<LevelListS2C.LevelInfo> levels = new ArrayList<>();
    private int selectedIndex = -1;
    private int scrollRows;
    private int totalRows;
    private int columns;
    private int cardWidth;
    private int cardHeight;
    private int cardGap = 10;
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
    private Button nextButton;
    private String lastSignature = "";

    public LevelSelectScreen(PvzceClient client) {
        super(client);
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

        int guiW = client.guiWidth();
        int guiH = client.guiHeight();
        titleReserve = Math.max(44, Math.min(72, guiH / 5));
        int bottomGap = 10;
        int customWidth = Math.min(270, guiW - 16);
        int actionWidth = Math.min(200, (guiW - 16 - bottomGap) / 2);
        int bottomHeight = GuiLayout.fitHeight(guiH, 44, 1, titleReserve, 0);
        actionY = bottomHeight + bottomGap;
        actionHeight = GuiLayout.fitHeight(guiH, 44, 1, titleReserve, actionY);

        updateLayout();

        int startX = centerX(actionWidth * 2 + bottomGap);
        nextButton = new Button(startX, actionY, actionWidth, actionHeight, "下一步", this::openSetup)
                .style(Button.Style.SEED_CHOOSER);
        nextButton.setActive(selectedIndex >= 0);
        addWidget(nextButton);
        addWidget(new Button(startX + actionWidth + bottomGap, actionY, actionWidth, actionHeight, "返回",
                client::closeScreen).style(Button.Style.SEED_CHOOSER));

        addWidget(new Button(centerX(customWidth), 4, customWidth, bottomHeight, "自定义关卡",
                () -> client.openEditor("custom_level")).style(Button.Style.SEED_CHOOSER));
        updateLevels(client.levelList());
    }

    /**
     * Recomputes card size, columns and arrow positions. Larger screens get
     * larger cards and, when there are enough levels, up to four visible rows;
     * the grid is vertically centered inside the available area.
     */
    private void updateLayout() {
        if (actionHeight <= 0) {
            return;
        }
        int guiW = client.guiWidth();
        int guiH = client.guiHeight();
        int arrowGap = 10;
        int gridAreaX = 12 + arrowWidth + arrowGap;
        int gridAreaWidth = Math.max(120, guiW - gridAreaX - arrowWidth - arrowGap - 12);
        int gridTop = guiH - titleReserve - 4;
        int pageIndicatorHeight = 24;
        int gridBottom = actionY + actionHeight + pageIndicatorHeight;
        int gridHeight = Math.max(60, gridTop - gridBottom);

        columns = Math.max(1, Math.min(4, Math.max(1,
                (gridAreaWidth + cardGap) / (220 + cardGap))));
        totalRows = levels.isEmpty() ? 1 : (levels.size() + columns - 1) / columns;

        int maxCardWidth = Math.min(320, Math.max(160,
                (gridAreaWidth - (columns - 1) * cardGap) / columns));
        int chosenRows = 1;
        int chosenHeight = Math.min(280, Math.max(100, gridHeight));
        for (int rows = Math.min(4, totalRows); rows >= 1; rows--) {
            int rowHeight = (gridHeight - (rows - 1) * cardGap) / rows;
            if (rowHeight >= 100) {
                chosenRows = rows;
                chosenHeight = Math.min(rowHeight, 280);
                break;
            }
        }
        if (gridHeight < 100) {
            chosenHeight = Math.max(60, gridHeight);
        }
        visibleRows = chosenRows;
        cardWidth = maxCardWidth;
        cardHeight = chosenHeight;

        int contentWidth = columns * cardWidth + (columns - 1) * cardGap;
        gridX = gridAreaX + Math.max(0, (gridAreaWidth - contentWidth) / 2);
        gridWidth = contentWidth;
        int actualHeight = visibleRows * (cardHeight + cardGap) - cardGap;
        gridRenderTop = gridTop - Math.max(0, (gridHeight - actualHeight) / 2);
        gridRenderBottom = gridRenderTop - actualHeight;

        int arrowCenterY = (gridRenderTop + gridRenderBottom) / 2 - arrowHeight / 2;
        prevArrowX = Math.max(8, gridAreaX - arrowGap - arrowWidth);
        prevArrowY = arrowCenterY;
        nextArrowX = guiW - 12 - arrowWidth;
        nextArrowY = arrowCenterY;

        scrollRows = Math.max(0, Math.min(scrollRows, maxScrollRows()));
    }

    @Override
    public void tick() {
        updateLevels(client.levelList());
    }

    private void updateLevels(List<LevelListS2C.LevelInfo> current) {
        String signature = current.toString();
        if (signature.equals(lastSignature)) {
            return;
        }
        lastSignature = signature;
        levels.clear();
        levels.addAll(current);
        if (selectedIndex >= levels.size()) {
            selectedIndex = -1;
        }
        updateLayout();
        scrollRows = Math.max(0, Math.min(scrollRows, maxScrollRows()));
        if (nextButton != null) {
            nextButton.setActive(selectedIndex >= 0);
        }
    }

    private int maxScrollRows() {
        return Math.max(0, totalRows - visibleRows);
    }

    @Override
    public void mouseClicked(double mouseX, double mouseY, int button) {
        double guiX = client.guiMouseX(mouseX);
        double guiY = client.guiMouseY(mouseY);
        if (inside(guiX, guiY, prevArrowX, prevArrowY, arrowWidth, arrowHeight)) {
            scrollRows = Math.max(0, scrollRows - Math.max(1, visibleRows));
            return;
        }
        if (inside(guiX, guiY, nextArrowX, nextArrowY, arrowWidth, arrowHeight)) {
            scrollRows = Math.min(maxScrollRows(), scrollRows + Math.max(1, visibleRows));
            return;
        }
        if (guiX >= gridX && guiX < gridX + gridWidth
                && guiY >= gridRenderBottom && guiY <= gridRenderTop) {
            int column = (int) ((guiX - gridX) / (cardWidth + cardGap));
            int row = (int) ((gridRenderTop - guiY) / (cardHeight + cardGap));
            if (column >= 0 && column < columns && row >= 0 && row < visibleRows) {
                int index = (scrollRows + row) * columns + column;
                if (index >= 0 && index < levels.size()) {
                    selectedIndex = index;
                    if (nextButton != null) {
                        nextButton.setActive(true);
                    }
                }
            }
            return;
        }
        super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        double guiX = client.guiMouseX(mouseX);
        double guiY = client.guiMouseY(mouseY);
        prevHover = inside(guiX, guiY, prevArrowX, prevArrowY, arrowWidth, arrowHeight);
        nextHover = inside(guiX, guiY, nextArrowX, nextArrowY, arrowWidth, arrowHeight);
        super.mouseMoved(mouseX, mouseY);
    }

    @Override
    public void mouseScrolled(double mouseX, double mouseY, double amount) {
        double guiX = client.guiMouseX(mouseX);
        double guiY = client.guiMouseY(mouseY);
        if (guiX >= gridX && guiX < gridX + gridWidth
                && guiY >= gridRenderBottom && guiY <= gridRenderTop) {
            int delta = amount > 0 ? -1 : 1;
            scrollRows = Math.max(0, Math.min(maxScrollRows(), scrollRows + delta));
            return;
        }
        super.mouseScrolled(mouseX, mouseY, amount);
    }

    private static boolean inside(double x, double y, int rx, int ry, int rw, int rh) {
        return x >= rx && x < rx + rw && y >= ry && y < ry + rh;
    }

    private void openSetup() {
        if (selectedIndex < 0 || selectedIndex >= levels.size()) {
            return;
        }
        client.openScreen(new LevelSetupScreen(client, levels.get(selectedIndex)));
    }

    @Override
    public void render() {
        client.beginGuiView();
        renderBackground(0.1F, 0.2F, 0.12F);
        String title = "选择关卡";
        float scale = Math.min(2.2F, client.guiHeight() / 100F);
        client.font().draw(title, (client.guiWidth() - client.font().width(title, scale)) / 2F,
                client.guiHeight() - client.font().lineHeight(scale) - 8, scale, 1F, 1F, 1F, 1F);

        if (levels.isEmpty()) {
            String loading = "载入中…";
            client.font().draw(loading, (client.guiWidth() - client.font().width(loading, 1F)) / 2F,
                    client.guiHeight() / 2F, 1F, 0.9F, 0.9F, 0.9F, 1F);
        } else {
            renderCards();
        }
        for (var widget : widgets) {
            widget.render(client);
        }
    }

    private void renderCards() {
        int firstRow = scrollRows;
        int lastRow = Math.min(totalRows, firstRow + visibleRows);
        for (int row = firstRow; row < lastRow; row++) {
            for (int column = 0; column < columns; column++) {
                int index = row * columns + column;
                if (index >= levels.size()) {
                    break;
                }
                LevelListS2C.LevelInfo level = levels.get(index);
                float cardX = gridX + column * (cardWidth + cardGap);
                float cardY = gridRenderTop - cardHeight - (row - firstRow) * (cardHeight + cardGap);
                boolean selected = index == selectedIndex;

                client.drawSolid(cardX - 2, cardY - 2, cardWidth + 4, cardHeight + 4, 0.1F,
                        0F, 0F, 0F, 0.35F);
                client.drawSolid(cardX, cardY, cardWidth, cardHeight, 0.1F,
                        0.22F, 0.14F, 0.06F, 0.88F);
                if (selected) {
                    client.drawSolid(cardX - 3, cardY - 3, cardWidth + 6, cardHeight + 6, 0.2F,
                            1F, 0.9F, 0.2F, 0.75F);
                }

                float iconSize = Math.max(24F, Math.min(cardHeight - 20F, cardWidth * 0.42F));
                float iconX = cardX + 10F;
                float iconY = cardY + (cardHeight - iconSize) / 2F;
                client.drawTexture(iconTexture(level.icon()), iconX, iconY, iconSize, iconSize,
                        0.2F, 1F, 1F, 1F, 1F);

                float textX = iconX + iconSize + 10F;
                float availableWidth = Math.max(30F, cardX + cardWidth - 10F - textX);
                String name = level.name().isEmpty() ? level.id() : level.name();
                float nameScale = 1.1F;
                while (nameScale > 0.55F && client.font().width(name, nameScale) > availableWidth) {
                    nameScale -= 0.05F;
                }
                String status = statusLabel(level.status());
                float nameY = cardY + cardHeight / 2F - client.font().lineHeight(nameScale) / 2F;
                if (!status.isEmpty()) {
                    nameY += 6F;
                }
                client.font().draw(name, textX, nameY, nameScale, 1F, 1F, 1F, 1F);
                if (!status.isEmpty()) {
                    client.font().draw(status, textX, cardY + cardHeight / 2F - 14F,
                            0.7F, 1F, 0.9F, 0.5F, 1F);
                }
            }
        }

        boolean hasPrevious = scrollRows > 0;
        boolean hasNext = scrollRows < maxScrollRows();
        drawArrow(true, prevArrowX, prevArrowY, hasPrevious, prevHover);
        drawArrow(false, nextArrowX, nextArrowY, hasNext, nextHover);

        int totalPages = Math.max(1, (totalRows + Math.max(1, visibleRows) - 1) / Math.max(1, visibleRows));
        int page = Math.max(1, Math.min(totalPages, scrollRows / Math.max(1, visibleRows) + 1));
        String pageText = page + "/" + totalPages;
        client.font().draw(pageText, (client.guiWidth() - client.font().width(pageText, 0.9F)) / 2F,
                actionY + actionHeight + 4, 0.9F, 1F, 1F, 1F, 1F);
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

    /** Shared with the level setup screen so the same state cannot read differently. */
    static String statusLabel(String status) {
        return com.pvzce.client.gui.GuiStatusText.label(status);
    }

}
