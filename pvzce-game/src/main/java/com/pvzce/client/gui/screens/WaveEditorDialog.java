package com.pvzce.client.gui.screens;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.pvzce.api.util.Identifier;
import com.pvzce.client.PvzceClient;
import com.pvzce.client.gui.components.AbstractSelectionList;
import com.pvzce.client.gui.components.Button;
import com.pvzce.client.gui.components.Dialog;
import com.pvzce.client.gui.components.EditBox;
import com.pvzce.common.core.BuiltInRegistries;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Full wave editor used by {@link EditorScreen}. The dialog edits a shared
 * {@link Config} in place; the caller serializes it on save.
 */
public final class WaveEditorDialog extends Dialog {
    public static final class Config {
        public float intervalEndMultiplier = 1F;
        public final List<WaveModel> waves = new ArrayList<>();

        /** Parses the wave-related fields of a level JSON root. */
        public static Config fromJson(JsonObject root) {
            Config config = new Config();
            if (root.has("wave_interval_end_multiplier")) {
                config.intervalEndMultiplier = root.get("wave_interval_end_multiplier").getAsFloat();
            }
            if (!root.has("waves")) {
                return config;
            }
            for (JsonElement element : root.getAsJsonArray("waves")) {
                JsonObject waveJson = element.getAsJsonObject();
                WaveModel wave = new WaveModel();
                wave.type = waveJson.has("type") ? waveJson.get("type").getAsString() : "small";
                wave.delay = waveJson.has("delay") ? waveJson.get("delay").getAsInt() : 600;
                wave.warningTicks = waveJson.has("warning_ticks")
                        ? waveJson.get("warning_ticks").getAsInt() : 600;
                if (waveJson.has("entries")) {
                    for (JsonElement entryElement : waveJson.getAsJsonArray("entries")) {
                        JsonObject entryJson = entryElement.getAsJsonObject();
                        if (!entryJson.has("id")) {
                            continue;
                        }
                        wave.entries.add(new EntryModel(
                                entryJson.get("id").getAsString(),
                                entryJson.has("count") ? Math.max(1, entryJson.get("count").getAsInt()) : 1));
                    }
                }
                config.waves.add(wave);
            }
            return config;
        }

        /** Serializes this config as the {@code waves} / multiplier part of a level JSON. */
        public JsonObject toJson() {
            JsonObject root = new JsonObject();
            root.addProperty("wave_interval_end_multiplier", intervalEndMultiplier);
            JsonArray wavesJson = new JsonArray();
            for (WaveModel wave : waves) {
                JsonObject waveJson = new JsonObject();
                waveJson.addProperty("type", wave.type);
                waveJson.addProperty("delay", wave.delay);
                waveJson.addProperty("warning_ticks", wave.warningTicks);
                JsonArray entries = new JsonArray();
                for (EntryModel entry : wave.entries) {
                    JsonObject entryJson = new JsonObject();
                    entryJson.addProperty("id", entry.id);
                    entryJson.addProperty("count", entry.count);
                    entries.add(entryJson);
                }
                waveJson.add("entries", entries);
                wavesJson.add(waveJson);
            }
            root.add("waves", wavesJson);
            return root;
        }

        public void replaceWith(Config other) {
            intervalEndMultiplier = other.intervalEndMultiplier;
            waves.clear();
            waves.addAll(other.waves);
        }
    }

    public static final class WaveModel {
        public String type = "small";
        public int delay = 600;
        public int warningTicks = 600;
        public final List<EntryModel> entries = new ArrayList<>();
    }

    public static final class EntryModel {
        public String id;
        public int count;

        public EntryModel(String id, int count) {
            this.id = id;
            this.count = count;
        }
    }

    private final PvzceClient client;
    private final Config config;
    private AbstractSelectionList<WaveModel> waveList;
    private AbstractSelectionList<EntryModel> entryList;
    private AbstractSelectionList<String> zombieList;
    private Button typeButton;
    private EditBox delayBox;
    private EditBox warningBox;
    private EditBox multiplierBox;
    private EditBox countBox;
    private WaveModel currentModel;
    private EntryModel lastSelectedEntry;
    private int row2Y;
    private int row3Y;
    private int listTopY;

    public WaveEditorDialog(PvzceClient client, int x, int y, int width, int height, Config config, Runnable onClose) {
        super(x, y, width, height, "波次配置");
        this.client = client;
        this.config = config;
        this.onClose(onClose);
        rebuild();
    }

    /** Shows the dialog and rebuilds its widgets for the current GUI size. */
    public void open() {
        setVisible(true);
        rebuild();
    }

    @Override
    public void close() {
        commitFields(currentModel);
        super.close();
    }

    public void tick() {
        if (!visible) {
            return;
        }
        commitFields(currentModel);
        WaveModel selectedWave = waveList == null ? null : waveList.selected();
        if (selectedWave != currentModel) {
            currentModel = selectedWave;
            lastSelectedEntry = null;
            refreshEditor();
        }
        if (typeButton != null && currentModel != null) {
            typeButton.setLabel(typeLabel(currentModel.type));
        }
        EntryModel selectedEntry = entryList == null ? null : entryList.selected();
        if (selectedEntry != lastSelectedEntry) {
            lastSelectedEntry = selectedEntry;
            updateCountBox();
        }
    }

    private void rebuild() {
        clearChildren();
        currentModel = null;
        lastSelectedEntry = null;

        int pad = 12;
        int bottom = y + 12;
        int top = y + height - 42;
        int leftWidth = Math.max(128, Math.min(180, width / 4));
        int waveListX = x + pad;
        int editX = waveListX + leftWidth + pad;
        int editWidth = Math.max(160, x + width - pad - editX);
        row2Y = top - 34;
        row3Y = top - 68;
        listTopY = top - 100;
        int listBottom = bottom + 84;
        int listHeight = Math.max(30, listTopY - listBottom);

        waveList = new AbstractSelectionList<>(waveListX, listBottom, leftWidth, listHeight, 24,
                (renderClient, wave, lx, ly) -> renderClient.font().draw(
                        waveSummary(wave), lx, ly + 5, 0.75F, 1F, 1F, 1F, 1F));
        addChild(waveList);

        int waveButtonY = bottom + 4;
        int waveButtonWidth = Math.max(24, (leftWidth - 9) / 4);
        addChild(new Button(waveListX, waveButtonY, waveButtonWidth, 28, "新增", this::addWave));
        addChild(new Button(waveListX + waveButtonWidth + 3, waveButtonY, waveButtonWidth, 28, "删除", this::removeWave));
        addChild(new Button(waveListX + (waveButtonWidth + 3) * 2, waveButtonY, waveButtonWidth, 28, "上移",
                () -> moveWave(-1)));
        addChild(new Button(waveListX + (waveButtonWidth + 3) * 3, waveButtonY, waveButtonWidth, 28, "下移",
                () -> moveWave(1)));

        int typeWidth = Math.min(150, Math.max(90, editWidth / 3));
        int finishWidth = Math.min(90, Math.max(60, editWidth / 4));
        typeButton = new Button(editX, top, typeWidth, 28, "类型: small", this::cycleType);
        addChild(typeButton);
        addChild(new Button(editX + editWidth - finishWidth, top, finishWidth, 28, "完成", this::close));

        int boxWidth = Math.min(72, Math.max(48, (editWidth - 110) / 2));
        delayBox = new EditBox(editX + 40, row2Y, boxWidth, 28, () -> commitFields(currentModel));
        int warningX = Math.min(editX + editWidth - boxWidth, editX + 40 + boxWidth + 48);
        warningBox = new EditBox(warningX, row2Y, boxWidth, 28, () -> commitFields(currentModel));
        multiplierBox = new EditBox(editX + 40, row3Y, boxWidth, 28, () -> commitFields(currentModel));
        addChild(delayBox);
        addChild(warningBox);
        addChild(multiplierBox);

        int listWidth = Math.max(50, (editWidth - 12) / 2);
        entryList = new AbstractSelectionList<>(editX, listBottom, listWidth, listHeight, 22,
                (renderClient, entry, lx, ly) -> renderClient.font().draw(
                        shortId(entry.id) + " x" + entry.count, lx, ly + 4, 0.7F, 1F, 1F, 1F, 1F));
        zombieList = new AbstractSelectionList<>(editX + listWidth + 12, listBottom,
                Math.max(50, editWidth - listWidth - 12), listHeight, 22,
                (renderClient, id, lx, ly) -> renderClient.font().draw(shortId(id), lx, ly + 4,
                        0.7F, 0.9F, 0.95F, 0.9F, 1F));
        addChild(entryList);
        addChild(zombieList);

        int entryButtonY = bottom + 4;
        int entryButtonWidth = Math.max(40, (editWidth - 6) / 2);
        addChild(new Button(editX, entryButtonY, entryButtonWidth, 28, "添加选中", this::addEntry));
        addChild(new Button(editX + entryButtonWidth + 6, entryButtonY, entryButtonWidth, 28, "移除选中",
                this::removeEntry));

        int countButtonY = bottom + 36;
        int countWidth = 56;
        int countChangeWidth = Math.max(40, (editWidth - countWidth - 8) / 2);
        countBox = new EditBox(editX, countButtonY, countWidth, 28, () -> commitFields(currentModel));
        countBox.setValueChangedListener(this::applyCountBox);
        addChild(countBox);
        addChild(new Button(editX + countWidth + 4, countButtonY, countChangeWidth, 28, "数量-",
                () -> changeCount(-1)));
        addChild(new Button(editX + countWidth + 8 + countChangeWidth, countButtonY, countChangeWidth, 28, "数量+",
                () -> changeCount(1)));

        List<String> zombies = BuiltInRegistries.ZOMBIES.keySet().stream()
                .map(Identifier::toString)
                .sorted()
                .toList();
        zombieList.setEntries(zombies);
        multiplierBox.setValue(formatFloat(config.intervalEndMultiplier), false);
        refreshWaveList();
    }

    @Override
    public void render(PvzceClient renderClient) {
        super.render(renderClient);
        if (!visible) {
            return;
        }
        float labelScale = 0.75F;
        drawLabel(renderClient, "波次列表", waveList.x(), listTopY + 4, labelScale);
        drawLabel(renderClient, "僵尸条目", entryList.x(), listTopY + 4, labelScale);
        drawLabel(renderClient, "可选僵尸", zombieList.x(), listTopY + 4, labelScale);
        drawLabel(renderClient, "间隔", delayBox.x() - 34, row2Y + 8, labelScale);
        drawLabel(renderClient, "预警", warningBox.x() - 34, row2Y + 8, labelScale);
        drawLabel(renderClient, "倍率", multiplierBox.x() - 34, row3Y + 8, labelScale);
        drawLabel(renderClient, "数量", countBox.x(), countBox.y() + countBox.height() + 2, labelScale);
        if (currentModel == null) {
            drawLabel(renderClient, "暂无波次，点击左侧“新增”创建",
                    entryList.x() + 4, entryList.y() + entryList.height() / 2F, 0.8F);
        }
    }

    private static void drawLabel(PvzceClient client, String text, float x, float y, float scale) {
        client.font().draw(text, x, y, scale, 1F, 1F, 1F, 1F);
    }

    private void refreshWaveList() {
        currentModel = ListEditorSupport.refresh(waveList, new ArrayList<>(config.waves), currentModel);
        lastSelectedEntry = null;
        refreshEditor();
    }

    private void refreshEditor() {
        boolean hasModel = currentModel != null;
        if (delayBox != null) {
            delayBox.setActive(hasModel);
            warningBox.setActive(hasModel);
            multiplierBox.setActive(true);
            if (hasModel) {
                delayBox.setValue(String.valueOf(currentModel.delay), false);
                warningBox.setValue(String.valueOf(currentModel.warningTicks), false);
                typeButton.setLabel(typeLabel(currentModel.type));
            } else {
                delayBox.setValue("", false);
                warningBox.setValue("", false);
                typeButton.setLabel("类型: -");
            }
        }

        EntryModel selected = entryList == null ? null : entryList.selected();
        if (entryList != null) {
            entryList.setEntries(hasModel ? new ArrayList<>(currentModel.entries) : List.of());
            if (hasModel && selected != null) {
                int index = currentModel.entries.indexOf(selected);
                if (index >= 0) {
                    entryList.select(index);
                }
            }
        }
        lastSelectedEntry = entryList == null ? null : entryList.selected();
        updateCountBox();
    }

    private void updateCountBox() {
        if (countBox == null) {
            return;
        }
        EntryModel selected = entryList == null ? null : entryList.selected();
        countBox.setValue(selected == null ? "1" : String.valueOf(selected.count), false);
    }

    private void applyCountBox() {
        EntryModel selected = entryList == null ? null : entryList.selected();
        if (selected == null || currentModel == null) {
            return;
        }
        selected.count = parseInt(countBox.value(), selected.count, 1, 9999);
        refreshEntryListKeepSelection(selected);
    }

    private void refreshEntryListKeepSelection(EntryModel selected) {
        if (entryList == null || currentModel == null) {
            return;
        }
        entryList.setEntries(new ArrayList<>(currentModel.entries));
        int index = currentModel.entries.indexOf(selected);
        if (index >= 0) {
            entryList.select(index);
        }
    }

    private void selectEntry(EntryModel entry) {
        if (entryList == null || currentModel == null) {
            return;
        }
        int index = currentModel.entries.indexOf(entry);
        if (index >= 0) {
            entryList.select(index);
        }
        lastSelectedEntry = entry;
        updateCountBox();
    }

    private void commitFields(WaveModel model) {
        if (model != null) {
            model.delay = parseInt(delayBox == null ? "" : delayBox.value(), 600, 1, 1_000_000);
            model.warningTicks = parseInt(warningBox == null ? "" : warningBox.value(), 600, 0, 1_000_000);
        }
        if (multiplierBox != null) {
            config.intervalEndMultiplier = parseFloat(multiplierBox.value(), 1F, 0.05F, 10F);
        }
    }

    private void cycleType() {
        if (currentModel == null) {
            return;
        }
        currentModel.type = switch (currentModel.type) {
            case "huge" -> "final";
            case "final" -> "small";
            default -> "huge";
        };
        typeButton.setLabel(typeLabel(currentModel.type));
        refreshWaveList();
    }

    private void addWave() {
        commitFields(currentModel);
        WaveModel wave = new WaveModel();
        config.waves.add(wave);
        currentModel = wave;
        refreshWaveList();
        waveList.select(config.waves.size() - 1);
        currentModel = waveList.selected();
        refreshEditor();
    }

    private void removeWave() {
        WaveModel selected = waveList.selected();
        if (selected == null) {
            return;
        }
        int index = config.waves.indexOf(selected);
        config.waves.remove(selected);
        currentModel = null;
        refreshWaveList();
        if (!config.waves.isEmpty()) {
            waveList.select(Math.max(0, Math.min(config.waves.size() - 1, index)));
            currentModel = waveList.selected();
            refreshEditor();
        }
    }

    private void moveWave(int delta) {
        WaveModel selected = waveList.selected();
        if (selected == null) {
            return;
        }
        if (!ListEditorSupport.move(config.waves, selected, delta)) {
            return;
        }
        // Remove-and-insert (shared with the music editor) instead of the swap this
        // used to do: a swap moves the item two rows when the neighbour is filtered out.
        refreshWaveList();
        waveList.select(config.waves.indexOf(selected));
        currentModel = selected;
        refreshEditor();
    }

    private void addEntry() {
        if (currentModel == null || zombieList == null || zombieList.selected() == null) {
            return;
        }
        String id = zombieList.selected();
        int amount = parseInt(countBox == null ? "1" : countBox.value(), 1, 1, 9999);
        EntryModel target = null;
        for (EntryModel entry : currentModel.entries) {
            if (entry.id.equals(id)) {
                entry.count += amount;
                target = entry;
                break;
            }
        }
        if (target == null) {
            target = new EntryModel(id, amount);
            currentModel.entries.add(target);
        }
        refreshEditor();
        selectEntry(target);
    }

    private void removeEntry() {
        EntryModel selected = entryList == null ? null : entryList.selected();
        if (currentModel == null || selected == null) {
            return;
        }
        int removedIndex = currentModel.entries.indexOf(selected);
        currentModel.entries.remove(selected);
        refreshEditor();
        if (!currentModel.entries.isEmpty()) {
            int fallbackIndex = Math.min(currentModel.entries.size() - 1, Math.max(0, removedIndex));
            selectEntry(currentModel.entries.get(fallbackIndex));
        }
    }

    private void changeCount(int delta) {
        if (currentModel == null || entryList == null) {
            return;
        }
        EntryModel entry = entryList.selected();
        if (entry == null && !currentModel.entries.isEmpty()) {
            entry = currentModel.entries.get(currentModel.entries.size() - 1);
            entryList.select(currentModel.entries.size() - 1);
        }
        if (entry == null) {
            return;
        }
        entry.count = Math.max(1, entry.count + delta);
        refreshEntryListKeepSelection(entry);
        lastSelectedEntry = entry;
        updateCountBox();
    }

    private static String typeLabel(String type) {
        return "类型: " + ("final".equals(type) ? "final" : type);
    }

    private static String waveSummary(WaveModel wave) {
        return wave.type + "  delay=" + wave.delay;
    }

    private static String shortId(String id) {
        return com.pvzce.client.gui.GuiText.shortId(id);
    }

    private static int parseInt(String value, int fallback, int min, int max) {
        try {
            int parsed = Integer.parseInt(value.trim());
            return Math.max(min, Math.min(max, parsed));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static float parseFloat(String text, float fallback, float min, float max) {
        return com.pvzce.client.gui.GuiText.parseFloat(text, fallback, min, max);
    }

    private static String formatFloat(float value) {
        return com.pvzce.client.gui.GuiText.formatFloat(value);
    }
}
