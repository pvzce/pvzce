package com.pvzce.client.gui.screens;

import com.google.gson.JsonArray;
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
    public static final int MAX_SEED_SLOTS_LIMIT = 12;


    public static final class Config {
        public int maxSeedSlots = 6;
        public final List<String> pool = new ArrayList<>();
        public final List<String> available = new ArrayList<>();

        public static Config fromJson(JsonObject root) {
            Config config = new Config();
            if (root.has("max_seed_slots")) {
                config.maxSeedSlots = Math.max(0, root.get("max_seed_slots").getAsInt());
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
        maxBox.setValue(String.valueOf(config.maxSeedSlots), false);
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
        drawLabel(renderClient, "最大选卡数", maxBox.x() - 76, maxBox.y() + 8);
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
        config.maxSeedSlots = com.pvzce.client.gui.GuiText.parseInt(
                maxBox.value(), config.maxSeedSlots, 0, MAX_SEED_SLOTS_LIMIT);
    }

    private void addSelected() {
        String id = availableList.selected();
        if (id == null || config.pool.contains(id)) {
            return;
        }
        config.pool.add(id);
        refreshLists(config.pool.size() - 1);
    }

    private void addAll() {
        Set<String> existing = new LinkedHashSet<>(config.pool);
        for (String id : config.available) {
            if (existing.add(id)) {
                config.pool.add(id);
            }
        }
        refreshLists(config.pool.size() - 1);
    }

    private void removeSelected() {
        int index = poolList.selectedIndex();
        if (index < 0 || index >= config.pool.size()) {
            return;
        }
        config.pool.remove(index);
        refreshLists(Math.min(index, config.pool.size() - 1));
    }

    private void moveSelected(int delta) {
        int index = poolList.selectedIndex();
        int target = index + delta;
        if (index < 0 || target < 0 || target >= config.pool.size()) {
            return;
        }
        String value = config.pool.remove(index);
        config.pool.add(target, value);
        refreshLists(target);
    }

    private void refreshLists(int selectedIndex) {
        poolList.setEntries(new ArrayList<>(config.pool));
        if (selectedIndex >= 0 && selectedIndex < config.pool.size()) {
            poolList.select(selectedIndex);
        }
        availableList.setEntries(new ArrayList<>(config.available));
    }
}
