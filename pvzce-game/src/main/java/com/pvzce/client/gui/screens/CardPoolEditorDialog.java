package com.pvzce.client.gui.screens;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.pvzce.client.PvzceClient;
import com.pvzce.client.gui.components.AbstractSelectionList;
import com.pvzce.client.gui.components.Button;
import com.pvzce.client.gui.components.Dialog;
import com.pvzce.client.gui.components.EditBox;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Editor dialog for a level's seed pool order and {@code max_seed_slots}. */
public final class CardPoolEditorDialog extends Dialog {
    /** Hard cap matching the level editor and the wire format. */
    public static final int MAX_SEED_SLOTS_LIMIT = com.pvzce.common.PvzceConstants.MAX_SEED_SLOTS;
    /** The text that means "write no max_seed_slots and follow the backpack". */
    public static final String FOLLOW_BACKPACK_LABEL = "背包";


    public static final class Config {
        /**
         * The level's own slot count, or {@link com.pvzce.api.content.LevelDef#UNSET_MAX_SEED_SLOTS}
         * for "whatever the player's backpack holds".
         *
         * <p>The default is "unset" rather than a number: a level that never mentions slots
         * is the ordinary case, and picking 6 for it here would silently overrule a
         * backpack the player had upgraded.
         */
        public int maxSeedSlots = com.pvzce.api.content.LevelDef.UNSET_MAX_SEED_SLOTS;
        /** The player's pickable cards, in bar order. */
        public final List<String> pool = new ArrayList<>();
        public final List<String> available = new ArrayList<>();

        public static Config fromJson(JsonObject root) {
            Config config = new Config();
            if (root.has("max_seed_slots")) {
                config.maxSeedSlots = Math.max(1, root.get("max_seed_slots").getAsInt());
            }
            if (root.has("slots") && root.get("slots").isJsonArray()) {
                for (JsonElement element : root.getAsJsonArray("slots")) {
                    if (element.isJsonPrimitive()) {
                        config.pool.add(element.getAsString());
                    }
                }
            }
            return config;
        }

        public void replaceWith(Config other) {
            maxSeedSlots = other.maxSeedSlots;
            pool.clear();
            pool.addAll(other.pool);
            available.clear();
            available.addAll(other.available);
        }
    }

    private final PvzceClient client;
    private final Config config;
    private AbstractSelectionList<String> poolList;
    private AbstractSelectionList<String> availableList;
    private EditBox maxBox;

    public CardPoolEditorDialog(PvzceClient client, int x, int y, int width, int height,
                                Config config, Runnable onClose) {
        super(x, y, width, height, "卡池与选卡上限");
        this.client = client;
        this.config = config;
        this.onClose(onClose);
        rebuild();
    }

    public void open() {
        setVisible(true);
        rebuild();
    }

    @Override
    public void close() {
        commitFields();
        super.close();
    }

    public void tick() {
        if (!visible) {
            return;
        }
        commitFields();
    }

    private void rebuild() {
        clearChildren();
        int pad = 12;
        int bottom = y + 12;
        int top = y + height - 42;
        int columnWidth = Math.max(150, (width - pad * 3) / 2);
        int leftX = x + pad;
        int rightX = leftX + columnWidth + pad;
        int listTop = top - 34;
        int listHeight = Math.max(60, listTop - (bottom + 86));

        poolList = new AbstractSelectionList<>(leftX, bottom + 80, columnWidth, listHeight, 24,
                (renderClient, id, lx, ly) ->
                        renderClient.font().draw(id, lx, ly + 4, 0.72F, 1F, 1F, 1F, 1F));
        poolList.setEntries(config.pool);
        addChild(poolList);

        int buttonWidth = Math.max(30, (columnWidth - 9) / 4);
        addChild(new Button(leftX, bottom + 2, buttonWidth, 26, "移除", this::removeSelected));
        addChild(new Button(leftX + buttonWidth + 3, bottom + 2, buttonWidth, 26, "上移",
                () -> moveSelected(-1)));
        addChild(new Button(leftX + (buttonWidth + 3) * 2, bottom + 2, buttonWidth, 26, "下移",
                () -> moveSelected(1)));
        addChild(new Button(leftX + (buttonWidth + 3) * 3, bottom + 2, buttonWidth, 26, "全加",
                this::addAll));

        availableList = new AbstractSelectionList<>(rightX, bottom + 80, columnWidth, listHeight, 24,
                (renderClient, id, lx, ly) ->
                        renderClient.font().draw(id, lx, ly + 4, 0.72F, 0.9F, 0.95F, 0.9F, 1F));
        availableList.setEntries(config.available);
        addChild(availableList);
        addChild(new Button(rightX, bottom + 2, Math.max(60, columnWidth / 2), 26, "添加选中",
                this::addSelected));

        int doneWidth = Math.min(100, Math.max(70, columnWidth / 2));
        addChild(new Button(x + width - pad - doneWidth, top - 2, doneWidth, 28, "完成", this::close));

        int maxWidth = 74;
        maxBox = new EditBox(rightX + 112, top - 2, maxWidth, 28, this::commitFields);
        maxBox.setValue(config.maxSeedSlots < 0
                ? FOLLOW_BACKPACK_LABEL : String.valueOf(config.maxSeedSlots), false);
        addChild(maxBox);
    }

    @Override
    public void render(PvzceClient renderClient) {
        super.render(renderClient);
        if (!visible) {
            return;
        }
        drawLabel(renderClient, "当前卡池（从上到下=卡槽顺序）", poolList.x(), poolList.y() + poolList.height() + 8);
        drawLabel(renderClient, "全部可选卡", availableList.x(), availableList.y() + availableList.height() + 8);
        drawLabel(renderClient, "最大选卡数（填“背包”跟随背包）", maxBox.x() - 200, maxBox.y() + 8);
        if (config.pool.isEmpty()) {
            drawLabel(renderClient, "卡池为空：玩家进入关卡时将没有卡槽", poolList.x() + 6,
                    poolList.y() + poolList.height() / 2F);
        }
    }

    private static void drawLabel(PvzceClient client, String text, float x, float y) {
        client.font().draw(text, x, y, 0.75F, 1F, 1F, 1F, 1F);
    }

    private void commitFields() {
        if (maxBox == null) {
            return;
        }
        String typed = maxBox.value() == null ? "" : maxBox.value().trim();
        // Blank or the word itself means "follow the backpack", which is the same state as
        // never having written the key: the field is removed on save.
        if (typed.isEmpty() || FOLLOW_BACKPACK_LABEL.equals(typed) || "-1".equals(typed)) {
            config.maxSeedSlots = com.pvzce.api.content.LevelDef.UNSET_MAX_SEED_SLOTS;
            return;
        }
        config.maxSeedSlots = com.pvzce.client.gui.GuiText.parseInt(
                typed, Math.max(1, config.maxSeedSlots), 1, MAX_SEED_SLOTS_LIMIT);
    }

    private void addSelected() {
        String id = availableList.selected();
        if (id == null || config.pool.contains(id)) {
            return;
        }
        config.pool.add(id);
        refreshLists(id);
    }

    private void addAll() {
        Set<String> existing = new LinkedHashSet<>(config.pool);
        for (String id : config.available) {
            if (existing.add(id)) {
                config.pool.add(id);
            }
        }
        refreshLists(config.pool.isEmpty() ? null : config.pool.get(config.pool.size() - 1));
    }

    private void removeSelected() {
        String card = poolList.selected();
        String next = ListEditorSupport.remove(config.pool, card);
        refreshLists(next);
    }

    private void moveSelected(int delta) {
        String card = poolList.selected();
        if (ListEditorSupport.move(config.pool, card, delta)) {
            refreshLists(card);
        }
    }

    /**
     * Rebuilds both lists; the pool keeps {@code keep} selected when it is still there.
     *
     * <p>Selection is by item rather than by row: the two card-pool editors used to disagree
     * here (this one selected a row number, the editor page kept the index it had), so the same
     * button left the highlight in a different place depending on where you pressed it.
     */
    private void refreshLists(String keep) {
        ListEditorSupport.refresh(poolList, new ArrayList<>(config.pool), keep);
        availableList.setEntries(new ArrayList<>(config.available));
    }
}
