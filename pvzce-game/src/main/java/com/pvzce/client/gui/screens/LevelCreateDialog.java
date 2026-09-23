package com.pvzce.client.gui.screens;

import com.pvzce.api.util.Identifier;
import com.pvzce.api.util.LevelGrouping;
import com.pvzce.client.PvzceClient;
import com.pvzce.client.gui.GuiLang;
import com.pvzce.client.gui.components.Button;
import com.pvzce.client.gui.components.Dialog;
import com.pvzce.client.gui.components.EditBox;
import com.pvzce.client.gui.components.Slider;
import com.pvzce.common.network.packet.LevelListS2C;
import com.pvzce.common.network.packet.LevelTabsS2C;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Asks for a new level's identity before the editor opens.
 *
 * <p>The editor used to be entered with a hard-coded id and a single text box that
 * decided the file name, the level id and the display name at once. The id then became
 * free text spelling the whole path, which is how a level ends up outside the theme the
 * author was looking at.
 *
 * <p>Now the group is picked, not typed: a theme row and a category row - the same two
 * levels the select screen navigates by - produce
 * {@code pvzce:<theme>/<category>/<name>} for whatever name is typed. Only the
 * unclassified page keeps a free-form id, because that bucket is defined by <em>not</em>
 * having a theme in the id. A typed {@code namespace:} still wins, so a level can be made
 * in another namespace; it then spells its own path, the group rows only owning the
 * default namespace's names.
 *
 * <p>The display name and the id are separate fields, and the id is read-only afterwards
 * - a level id is what saves, card pools and other levels refer to, so renaming it behind
 * the author's back would break those references. The editor can still move a level
 * between groups, which is an explicit rename rather than a side effect of editing a name.
 */
public final class LevelCreateDialog extends Dialog {
    /**
     * What the editor needs to start: which level, which page, and how big the board is.
     *
     * <p>The namespace is its own component rather than being folded into {@link #id()}: the
     * id is the resolved {@code namespace:path}, and the caller that only wants to know
     * "which level" reads it there, while a caller that wants to file the level (the editor's
     * save path) can read the namespace without parsing the id back apart.
     */
    public record Request(Identifier id, String namespace, String name, int width, int height,
                          Identifier theme, Identifier category) {
    }

    /** Board size presets, as (columns, rows). */
    private static final int[][] SIZE_PRESETS = {{9, 5}, {9, 6}, {5, 5}, {11, 6}, {12, 8}};

    private static final String DEFAULT_NAMESPACE = "pvzce";
    private static final String UNCATEGORIZED_KEY = "pvzce.level_uncategorized";
    private static final String UNCATEGORIZED_TEXT = "未分类";

    private final PvzceClient client;
    private final Consumer<Request> onConfirm;
    private final EditBox namespaceBox;
    private final EditBox namePathBox;
    private final EditBox nameBox;
    private final Slider sizeSlider;

    /** The tab table as received, kept for rebuilding the category row on a theme change. */
    private final List<LevelTabsS2C.Tab> tabs;
    private final List<String> themeIds;
    private final List<String> categoryIds = new ArrayList<>();
    private final List<GroupChoice> themeButtons = new ArrayList<>();
    private final List<GroupChoice> categoryButtons = new ArrayList<>();
    private int selectedTheme;
    private int selectedCategory;
    /** Where the category row lives, so a theme change can rebuild it in place. */
    private final int groupX;
    private final int groupWidth;
    private final int groupHeight;
    private final int themeRowY;
    private final int categoryRowY;
    private String error = "";

    /** One button in a group row: what it selects. */
    private record GroupChoice(Button button, String id) {
    }

    private LevelCreateDialog(PvzceClient client, int x, int y, int width, int height,
                              String suggestedPath, List<LevelTabsS2C.Tab> tabs,
                              String initialTheme, String initialCategory,
                              Consumer<Request> onConfirm) {
        super(x, y, width, height, GuiLang.raw("pvzce.editor.new_level", "新建关卡"));
        this.client = client;
        this.onConfirm = onConfirm;
        this.tabs = tabs == null ? List.of() : List.copyOf(tabs);
        titleScale(Math.min(1.3F, height / 320F));
        closeOnEscape(true);

        int pad = 20;
        int fieldWidth = width - pad * 2;
        int rowH = Math.max(24, Math.min(28, height / 16));
        int gap = 8;
        int buttonY = y + 14;
        int sizeY = buttonY + rowH + gap;
        int nameY = sizeY + rowH + 18;
        int pathY = nameY + rowH + 18;
        int namespaceY = pathY + rowH + 18;
        groupX = x + pad;
        groupWidth = fieldWidth;
        groupHeight = rowH;
        themeRowY = namespaceY + rowH + 15;
        categoryRowY = themeRowY + rowH + 15;

        nameBox = new EditBox(x + pad, nameY, fieldWidth, rowH, this::confirm);
        addChild(nameBox);

        // The id is two fields: a namespace and the rest of the path. A level does not have
        // to live in "pvzce" - a data pack or mod files its levels under its own namespace,
        // and forcing the built-in one made that impossible without hand-editing the file.
        namePathBox = new EditBox(x + pad, pathY, pathFieldWidth(fieldWidth), rowH, this::confirm);
        addChild(namePathBox);
        namePathBox.setValue(suggestedPath, false);
        namePathBox.setBordered(true);
        namePathBox.setValueChangedListener(this::validate);

        namespaceBox = new EditBox(x + pad + pathFieldWidth(fieldWidth) + 8, pathY,
                namespaceFieldWidth(fieldWidth), rowH, this::confirm);
        addChild(namespaceBox);
        namespaceBox.setValue(DEFAULT_NAMESPACE, false);
        namespaceBox.setBordered(true);
        namespaceBox.setValueChangedListener(this::validate);

        sizeSlider = new Slider(x + pad, sizeY, fieldWidth, rowH, 0,
                SIZE_PRESETS.length - 1, 0, slider -> {
        });
        addChild(sizeSlider);

        themeIds = themeChoices(this.tabs);
        selectedTheme = Math.max(0, indexOf(themeIds, initialTheme));
        buildThemeRow();

        categoryIds.clear();
        categoryIds.addAll(categoryChoices(this.tabs, selectedThemeId()));
        selectedCategory = Math.max(0, indexOf(categoryIds, initialCategory));
        buildCategoryRow();

        int buttonWidth = Math.min(180, (fieldWidth - 12) / 2);
        addButton(new Button(x + pad, buttonY, buttonWidth, rowH + 4,
                GuiLang.raw("pvzce.confirm", "确认"), this::confirm));
        addButton(new Button(x + pad + buttonWidth + 12, buttonY, buttonWidth, rowH + 4,
                GuiLang.raw("pvzce.cancel", "取消"), this::close).style(Button.Style.SEED_CHOOSER));

        refreshGroupState();
        validate();
    }

    public static LevelCreateDialog create(PvzceClient client, String initialTheme, String initialCategory,
                                           Consumer<Request> onConfirm) {
        int width = Math.min(560, client.guiWidth() - 24);
        int height = Math.min(470, client.guiHeight() - 24);
        List<LevelTabsS2C.Tab> tabs = client.levelTabs();
        String theme = initialTheme == null ? LevelGrouping.UNCATEGORIZED.toString() : initialTheme;
        String category = initialCategory == null ? LevelGrouping.UNCATEGORIZED.toString() : initialCategory;
        return new LevelCreateDialog(client, (client.guiWidth() - width) / 2,
                (client.guiHeight() - height) / 2, width, height,
                suggestPath(client, theme, category), tabs, theme, category, onConfirm);
    }

    // ------------------------------------------------------------------
    // Group rows
    // ------------------------------------------------------------------

    /**
     * The themes on offer: the server's tab table plus the unclassified bucket, which is
     * always last so it reads as the escape hatch it is.
     */
    private static List<String> themeChoices(List<LevelTabsS2C.Tab> tabs) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (LevelTabsS2C.Tab tab : tabs) {
            if (tab != null && !LevelGrouping.isUncategorized(Identifier.tryParse(tab.theme()))) {
                ids.add(tab.theme());
            }
        }
        ids.add(LevelGrouping.UNCATEGORIZED.toString());
        return List.copyOf(ids);
    }

    /**
     * The categories the chosen theme offers.
     *
     * <p>Read off the tab table rather than a registry: the table is exactly the set of
     * pages the select screen can open, so a level cannot be created into a category that
     * has no page to appear on.
     */
    private static List<String> categoryChoices(List<LevelTabsS2C.Tab> tabs, Identifier theme) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        if (LevelGrouping.isUncategorized(theme)) {
            ids.add(LevelGrouping.UNCATEGORIZED.toString());
        } else {
            for (LevelTabsS2C.Tab tab : tabs) {
                if (tab != null && theme.toString().equals(tab.theme())) {
                    ids.add(tab.category());
                }
            }
            if (ids.isEmpty()) {
                ids.add(LevelGrouping.UNCATEGORIZED.toString());
            }
        }
        return List.copyOf(ids);
    }

    private void buildThemeRow() {
        buildRow(themeButtons, themeIds, themeRowY, this::switchTheme);
    }

    private void buildCategoryRow() {
        buildRow(categoryButtons, categoryIds, categoryRowY, this::switchCategory);
    }

    private void buildRow(List<GroupChoice> target, List<String> ids, int y, IntConsumer onPick) {
        int gap = 4;
        int buttonWidth = Math.max(56, (groupWidth - gap * (ids.size() - 1)) / Math.max(1, ids.size()));
        for (int i = 0; i < ids.size(); i++) {
            String id = ids.get(i);
            int index = i;
            Button button = new Button(groupX + i * (buttonWidth + gap), y, buttonWidth, groupHeight,
                    LevelPage.label(id, UNCATEGORIZED_KEY, UNCATEGORIZED_TEXT),
                    () -> onPick.accept(index));
            button.style(Button.Style.SEED_CHOOSER);
            addChild(button);
            target.add(new GroupChoice(button, id));
        }
    }

    private void switchTheme(int index) {
        if (index == selectedTheme || index < 0 || index >= themeIds.size()) {
            return;
        }
        selectedTheme = index;
        // The category list belongs to the theme, so its row is rebuilt with the new one.
        for (GroupChoice choice : categoryButtons) {
            removeChild(choice.button());
        }
        categoryButtons.clear();
        categoryIds.clear();
        categoryIds.addAll(categoryChoices(tabs, selectedThemeId()));
        selectedCategory = 0;
        buildCategoryRow();
        refreshGroupState();
        validate();
    }

    private void switchCategory(int index) {
        if (index == selectedCategory || index < 0 || index >= categoryIds.size()) {
            return;
        }
        selectedCategory = index;
        refreshGroupState();
        validate();
    }

    private void refreshGroupState() {
        for (int i = 0; i < themeButtons.size(); i++) {
            themeButtons.get(i).button().setActive(i != selectedTheme);
        }
        for (int i = 0; i < categoryButtons.size(); i++) {
            categoryButtons.get(i).button().setActive(i != selectedCategory);
        }
    }

    private Identifier selectedThemeId() {
        return parseOrUncategorized(themeIds.isEmpty() ? null : themeIds.get(clamped(selectedTheme, themeIds)));
    }

    private Identifier selectedCategoryId() {
        return parseOrUncategorized(categoryIds.isEmpty() ? null
                : categoryIds.get(clamped(selectedCategory, categoryIds)));
    }

    private static int clamped(int index, List<String> ids) {
        return Math.max(0, Math.min(index, ids.size() - 1));
    }

    private static Identifier parseOrUncategorized(String id) {
        Identifier parsed = Identifier.tryParse(id);
        return parsed == null ? LevelGrouping.UNCATEGORIZED : parsed;
    }

    private static int indexOf(List<String> ids, String wanted) {
        return wanted == null ? 0 : Math.max(0, ids.indexOf(wanted));
    }

    // ------------------------------------------------------------------
    // Id / validation
    // ------------------------------------------------------------------

    /**
     * A free name under the open page.
     *
     * <p>Starts at {@code my_level} and counts up, so two new levels cannot silently target
     * the same file - which is exactly how the old fixed-id editor lost the previous level.
     * Counted against the whole list, so the suggestion is free in <em>any</em> namespace:
     * the namespace field starts at the default one, not at whatever is free.
     */
    private static String suggestPath(PvzceClient client, String theme, String category) {
        Identifier themeId = parseOrUncategorized(theme);
        Identifier categoryId = parseOrUncategorized(category);
        for (int i = 1; i < 1000; i++) {
            String leaf = i == 1 ? "my_level" : "my_level_" + i;
            Identifier candidate = LevelGrouping.levelId(DEFAULT_NAMESPACE, themeId, categoryId, leaf);
            if (candidate != null && !isTaken(client, candidate.toString())) {
                return leaf;
            }
        }
        return "my_level";
    }

    /** The field widths of the id row; the namespace gets the smaller half. */
    private static int pathFieldWidth(int fieldWidth) {
        return Math.max(120, fieldWidth * 58 / 100);
    }

    private static int namespaceFieldWidth(int fieldWidth) {
        return Math.max(80, fieldWidth - pathFieldWidth(fieldWidth) - 8);
    }

    /**
     * The namespace the level is created in.
     *
     * <p>Typed, defaulting to {@code pvzce}: namespaces are not a fixed list - a data pack
     * brings its own - so the field is free text with the same character rules as an
     * identifier's namespace, validated rather than restricted to a menu.
     */
    private String namespace() {
        String raw = namespaceBox.value() == null ? "" : namespaceBox.value().trim();
        return raw.isEmpty() ? DEFAULT_NAMESPACE : raw;
    }

    /** The typed namespace when it is usable, else {@code null}; empty counts as the default. */
    private String resolvedNamespace() {
        String raw = namespaceBox.value() == null ? "" : namespaceBox.value().trim();
        return Identifier.isValidNamespace(raw.isEmpty() ? DEFAULT_NAMESPACE : raw)
                ? (raw.isEmpty() ? DEFAULT_NAMESPACE : raw) : null;
    }

    private int[] selectedSize() {
        int index = Math.max(0, Math.min(SIZE_PRESETS.length - 1, Math.round(sizeSlider.value())));
        return SIZE_PRESETS[index];
    }

    /**
     * The id the typed name resolves to, or {@code null} when it is unusable.
     *
     * <p>Round-tripping is the only honest check: {@code Identifier} allows ':' inside a
     * path even though it cannot be parsed back from {@code a:b:c}.
     */
    private Identifier resolvedId() {
        String raw = namePathBox.value() == null ? "" : namePathBox.value().trim();
        if (raw.isBlank()) {
            return null;
        }
        Identifier parsed = raw.indexOf(':') >= 0
                ? Identifier.tryParse(raw)
                : LevelGrouping.levelId(namespace(), selectedThemeId(), selectedCategoryId(), raw);
        return parsed == null ? null : Identifier.tryParse(parsed.toString());
    }

    /** True when the level list already has this id; the client's list is the server's. */
    private boolean isTaken(String id) {
        return isTaken(client, id);
    }

    private static boolean isTaken(PvzceClient client, String id) {
        if (client == null) {
            return false;
        }
        for (LevelListS2C.LevelInfo info : client.levelList()) {
            if (id.equals(info.id())) {
                return true;
            }
        }
        return false;
    }

    private void validate() {
        Identifier id = resolvedId();
        if (resolvedNamespace() == null) {
            error = "命名空间只能包含小写字母、数字与 _ - .";
        } else if (id == null) {
            error = namePathBox.value() == null || namePathBox.value().isBlank()
                    ? "请输入关卡 ID"
                    : "ID 只能包含小写字母、数字与 _ - . /";
        } else if (isTaken(id.toString())) {
            error = "该 ID 已被占用：" + id;
        } else {
            error = "";
        }
    }

    private void confirm() {
        Identifier id = resolvedId();
        if (id == null || !error.isEmpty()) {
            return;
        }
        int[] size = selectedSize();
        String name = nameBox.value() == null || nameBox.value().isBlank()
                ? GuiLang.raw("pvzce.editor.untitled", "未命名关卡")
                : nameBox.value().trim();
        close();
        onConfirm.accept(new Request(id, namespace(), name, size[0], size[1],
                selectedThemeId(), selectedCategoryId()));
    }

    @Override
    public void render(PvzceClient renderClient) {
        super.render(renderClient);
        if (!visible) {
            return;
        }
        int pad = 20;
        float labelScale = 0.72F;
        renderClient.fonts().body().draw(GuiLang.raw("pvzce.editor.level_name", "关卡名称"),
                x + pad, nameBox.y() + nameBox.height() + 4F, labelScale, 1F, 1F, 1F, 1F);

        int[] size = selectedSize();
        renderClient.fonts().body().draw(GuiLang.raw("pvzce.editor.board_size", "棋盘尺寸") + "：" + size[0] + " × " + size[1],
                x + pad, sizeSlider.y() + sizeSlider.height() + 4F, labelScale, 1F, 1F, 1F, 1F);

        float pathLabelY = namePathBox.y() + namePathBox.height() + 4F;
        renderClient.fonts().body().draw(GuiLang.raw("pvzce.editor.level_file", "关卡 ID"),
                x + pad, pathLabelY, labelScale, 1F, 1F, 1F, 1F);
        renderClient.fonts().body().draw(GuiLang.raw("pvzce.editor.namespace", "命名空间"),
                namespaceBox.x(), pathLabelY, labelScale, 1F, 1F, 1F, 1F);
        Identifier id = resolvedId();
        if (id != null) {
            renderClient.fonts().body().draw(id.toString(), x + pad + 76, pathLabelY, 0.62F,
                    0.8F, 0.9F, 0.8F, 1F);
        }

        renderClient.fonts().body().draw(GuiLang.raw("pvzce.editor.theme", "主题"),
                x + pad, themeRowY + groupHeight + 3F, labelScale, 1F, 1F, 1F, 1F);
        renderClient.fonts().body().draw(GuiLang.raw("pvzce.editor.category", "类别"),
                x + pad, categoryRowY + groupHeight + 3F, labelScale, 1F, 1F, 1F, 1F);
        renderClient.fonts().body().draw(GuiLang.raw("pvzce.editor.group_hint", "新关卡会放进该分类目录"),
                x + pad + 90, categoryRowY + groupHeight + 3F, 0.6F, 0.75F, 0.8F, 0.8F, 1F);

        if (!error.isEmpty()) {
            renderClient.fonts().body().draw(error, x + pad, categoryRowY + groupHeight + 20F,
                    0.72F, 1F, 0.55F, 0.55F, 1F);
        }
    }
}
