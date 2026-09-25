package com.pvzce.client.gui.editor.pages;

import com.pvzce.api.util.Identifier;
import com.pvzce.client.PvzceClient;
import com.pvzce.client.gui.GuiLang;
import com.pvzce.client.gui.GuiText;
import com.pvzce.client.gui.components.AbstractSelectionList;
import com.pvzce.client.gui.components.Button;
import com.pvzce.client.gui.components.EditBox;
import com.pvzce.client.gui.components.PaletteList;
import com.pvzce.client.gui.editor.EditorContext;
import com.pvzce.client.gui.screens.ListEditorSupport;
import com.pvzce.client.gui.editor.EditorPage;
import com.pvzce.client.gui.editor.LevelFileWriter;
import com.pvzce.client.gui.screens.WaveEditorModel;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.util.MathUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * The wave page: the schedule as a table of waves on the left, the selected wave's parameters
 * and composition on the right.
 *
 * <p>Moved off {@code EditorScreen} whole. The screen used to own the wave model, every widget
 * on this page and the three switch arms that built, drew and ticked it; the page owns them
 * now, so the table, the detail column and the summary line are one object's job and the
 * {@code waves} block of the file has one writer. Nothing here reads screen state: the
 * geometry comes from {@link EditorContext} and the widgets are owned through it.
 *
 * <p>The detail boxes are committed as the first step of {@link #writeTo}, not only when Enter
 * is pressed: the 保存 button is handled before the frame's tick, and the model - not the box -
 * is what {@link LevelFileWriter#waves} reads, so a delay typed and never confirmed would
 * otherwise be written from the value the wave had before.
 */
public final class WavePage implements EditorPage {
    /** Column x offsets, shared with the full-screen wave editor's table. */
    private static final float[] WAVE_COLUMNS = {0.04F, 0.15F, 0.31F, 0.47F, 0.63F};

    private final WaveEditorModel.Config waveConfig = new WaveEditorModel.Config();
    private AbstractSelectionList<WaveEditorModel.WaveModel> waveList;
    private Button waveTypeButton;
    private EditBox waveDelayBox;
    private EditBox waveWarningBox;
    /** Ticks between this wave's zombies; empty means the engine's default. */
    private EditBox waveIntervalBox;
    private EditBox waveCountBox;
    private PaletteList waveEntryList;
    private PaletteList waveZombieList;
    /** The wave the detail panel was last built for; selection-change detection. */
    private WaveEditorModel.WaveModel lastWaveShown;

    @Override
    public String id() {
        return "wave";
    }

    @Override
    public String label() {
        return GuiLang.raw("pvzce.editor.page.wave", "wave");
    }

    @Override
    public int order() {
        return 40;
    }

    /**
     * False: this page is two columns of its own, and a page without a palette gets the whole
     * content band instead of the centre column it would read badly squeezed into.
     */
    @Override
    public boolean hasPalette() {
        return false;
    }

    /** The draft is the loaded level JSON here; the page reads it once, before any build. */
    @Override
    public void readFrom(EditorContext context) {
        waveConfig.replaceWith(WaveEditorModel.Config.fromJson(context.draft().json()));
    }

    @Override
    public void writeTo(EditorContext context) {
        commitWaveFields();
        LevelFileWriter.waves(context.draft(), waveConfig);
    }

    @Override
    public void build(EditorContext context) {
        EditorContext.Rect area = context.fullContent();
        int x = area.x();
        int y = area.y();
        int w = area.width();
        int h = area.height();
        int rowH = MathUtil.clamp(h / 16, 26, 34);
        int pad = 8;
        int gap = 10;

        // Left: the wave table. Right: the selected wave's parameters and composition.
        int tableW = (int) (w * 0.58F);
        int detailX = x + tableW + gap;
        int detailW = w - tableW - gap;

        int tableTop = y + h - rowH - pad - 20;
        int tableH = Math.max(60, tableTop - (y + rowH + pad + 20));
        waveList = context.own(new AbstractSelectionList<WaveEditorModel.WaveModel>(x, y + rowH + pad + 20,
                tableW, tableH, MathUtil.clamp(tableH / 8, 30, 44), (renderClient, wave, rx, ry) -> {
        }));
        waveList.setEntryRenderer(this::renderWaveRow);
        waveList.setEntries(new ArrayList<>(waveConfig.waves));

        // Wave list actions, under the table.
        int bw = Math.max(70, tableW / 6 - 6);
        int wy = y + pad;
        context.own(new Button(x, wy, bw, rowH, "新增波次", () -> addWave(context)));
        context.own(new Button(x + bw + 5, wy, bw, rowH, "删除波次", () -> removeWave(context)));
        context.own(new Button(x + (bw + 5) * 2, wy, bw, rowH, "上移", () -> moveWave(context, -1)));
        context.own(new Button(x + (bw + 5) * 3, wy, bw, rowH, "下移", () -> moveWave(context, 1)));
        context.own(new Button(x + (bw + 5) * 4, wy, bw, rowH, "自动填充", () -> fillDefaultWaves(context)));
        context.own(new Button(x + (bw + 5) * 5, wy, Math.max(70, tableW - (bw + 5) * 5), rowH,
                "末波倍率 +", () -> adjustWaveMultiplier(0.05F)));
        context.own(new Button(x + (bw + 5) * 5, wy + rowH + 4, Math.max(70, tableW - (bw + 5) * 5), rowH,
                "末波倍率 -", () -> adjustWaveMultiplier(-0.05F)));

        // Detail: type / delay / warning, then composition.
        int fieldW = Math.max(60, (detailW - pad * 2 - gap) / 2);
        int top = y + h - rowH - pad;
        waveTypeButton = context.own(new Button(detailX, top, fieldW, rowH, "类型：小波",
                () -> cycleWaveType(context)));
        waveDelayBox = context.own(new EditBox(detailX + fieldW + gap, top, fieldW, rowH,
                this::commitWaveFields));
        waveWarningBox = context.own(new EditBox(detailX, top - rowH - 6, fieldW, rowH,
                this::commitWaveFields));
        waveCountBox = context.own(new EditBox(detailX + fieldW + gap, top - rowH - 6, fieldW, rowH,
                () -> applyWaveCount(context)));
        waveCountBox.setValueChangedListener(() -> applyWaveCount(context));
        // The release interval belongs to the wave, so it sits with the wave's other
        // settings rather than with the level's.
        waveIntervalBox = context.own(new EditBox(detailX, top - (rowH + 6) * 2, fieldW, rowH,
                this::commitWaveFields));

        int listTop = top - (rowH + 6) * 3 - 20;
        int listBottom = y + rowH + pad + 22;
        int listH = Math.max(50, listTop - listBottom);
        int listW = Math.max(80, (detailW - pad * 2 - gap) / 2);
        waveEntryList = context.own(new PaletteList(detailX, listBottom, listW, listH,
                MathUtil.clamp(listH / 5, 26, 34), PaletteList.Kind.ENTITY));
        waveZombieList = context.own(new PaletteList(detailX + listW + gap, listBottom, listW, listH,
                MathUtil.clamp(listH / 5, 26, 34), PaletteList.Kind.ENTITY));
        waveZombieList.setItems(BuiltInRegistries.ZOMBIES.keySet().stream()
                .sorted()
                .map(id -> PaletteList.Item.of(context.client(), PaletteList.Kind.ENTITY, "zombie", id, null))
                .toList());

        int actionW = Math.max(64, (listW - 6) / 2);
        int ay = y + pad;
        context.own(new Button(detailX, ay, actionW, rowH, "加入组成", () -> addWaveEntry(context)));
        context.own(new Button(detailX + actionW + 6, ay, actionW, rowH, "移出组成",
                () -> removeWaveEntry(context)));

        refreshWaveDetail(context);
    }

    private void renderWaveRow(PvzceClient renderClient, WaveEditorModel.WaveModel wave, int x, int y) {
        int index = waveConfig.waves.indexOf(wave);
        int rowH = waveList == null ? 30 : waveList.entryHeight();
        int tableW = waveList == null ? 200 : waveList.width();
        float[] colour = WaveEditorModel.typeColour(wave.type);
        renderClient.fonts().body().draw(String.valueOf(index + 1), x, y + rowH / 2F + 1F, 0.8F,
                colour[0], colour[1], colour[2], 1F);
        renderClient.fonts().body().draw(WaveEditorModel.typeName(wave.type), x + tableW * WAVE_COLUMNS[1],
                y + rowH / 2F + 1F, 0.78F, colour[0], colour[1], colour[2], 1F);
        renderClient.fonts().body().draw("间隔 " + wave.delay, x + tableW * WAVE_COLUMNS[2],
                y + rowH / 2F + 1F, 0.72F, 0.9F, 0.9F, 0.9F, 1F);
        renderClient.fonts().body().draw(wave.spawnInterval > 0 ? "出怪 " + wave.spawnInterval : "出怪 默认",
                x + tableW * WAVE_COLUMNS[3], y + rowH / 2F + 1F, 0.72F, 0.9F, 0.9F, 0.9F, 1F);
        renderClient.fonts().body().draw(wave.summary(), x + tableW * WAVE_COLUMNS[4],
                y + rowH / 2F + 3F, 0.7F, 0.95F, 0.95F, 0.95F, 1F);
        renderClient.fonts().body().draw("共 " + wave.total() + " 只", x + tableW * WAVE_COLUMNS[4],
                y + rowH / 2F - 11F, 0.62F, 0.7F, 0.78F, 0.8F, 1F);
    }

    @Override
    public void render(EditorContext context) {
        int total = 0;
        for (int i = 0; i < waveConfig.waves.size(); i++) {
            total += waveConfig.totalZombies(i);
        }
        // One summary line in the strip above the table, not three lines stacked
        // inside it - they used to be drawn over the first table rows.
        EditorContext.Rect area = context.fullContent();
        float summaryY = area.y() + area.height() + 4F;
        context.client().fonts().body().draw("波次 " + waveConfig.waves.size()
                        + "　僵尸总数 " + total
                        + "　末波倍率 " + GuiText.formatFloat(waveConfig.intervalEndMultiplier),
                area.x() + 4, summaryY, 0.74F, 1F, 1F, 1F, 1F);
        if (waveConfig.waves.isEmpty()) {
            context.client().fonts().body().draw("还没有波次：点「自动填充」生成骨架，或「新增波次」逐条添加",
                    area.x() + 4, area.y() + area.height() / 2F, 0.85F, 1F, 0.85F, 0.5F, 1F);
        }
        if (waveDelayBox != null) {
            context.client().fonts().body().draw("间隔 tick", waveDelayBox.x(),
                    waveDelayBox.y() + waveDelayBox.height() + 3F, 0.68F, 0.9F, 0.9F, 0.9F, 1F);
            context.client().fonts().body().draw("预警 tick", waveWarningBox.x(),
                    waveWarningBox.y() + waveWarningBox.height() + 3F, 0.68F, 0.9F, 0.9F, 0.9F, 1F);
            context.client().fonts().body().draw("出怪间隔 tick（空 = 默认）", waveIntervalBox.x(),
                    waveIntervalBox.y() + waveIntervalBox.height() + 3F,
                    0.68F, 0.9F, 0.9F, 0.9F, 1F);
            context.client().fonts().body().draw("组成（选中后用右侧数量框改）", waveEntryList.x(),
                    waveEntryList.y() + waveEntryList.height() + 4F, 0.7F, 0.9F, 0.9F, 0.9F, 1F);
            context.client().fonts().body().draw("可选僵尸", waveZombieList.x(),
                    waveZombieList.y() + waveZombieList.height() + 4F, 0.7F, 0.9F, 0.9F, 0.9F, 1F);
            context.client().fonts().body().draw("数量", waveCountBox.x(),
                    waveCountBox.y() + waveCountBox.height() + 3F, 0.68F, 0.9F, 0.9F, 0.9F, 1F);
        }
    }

    /** Keeps the detail panel on the table's selection; runs every frame. */
    @Override
    public void tick(EditorContext context) {
        if (waveList != null) {
            // The detail panel follows the table's selection; comparing against the wave
            // it was last built for is what makes this cheap enough to run every frame.
            WaveEditorModel.WaveModel selected = waveList.selected();
            if (selected != lastWaveShown) {
                lastWaveShown = selected;
                refreshWaveDetail(context);
            }
        }
    }

    private void adjustWaveMultiplier(float delta) {
        waveConfig.intervalEndMultiplier = Math.max(0.05F,
                Math.min(10F, waveConfig.intervalEndMultiplier + delta));
    }

    private WaveEditorModel.WaveModel currentWave() {
        return waveList == null ? null : waveList.selected();
    }

    /** Pushes the selected wave's values into the detail widgets. */
    private void refreshWaveDetail(EditorContext context) {
        WaveEditorModel.WaveModel wave = currentWave();
        boolean has = wave != null;
        if (waveTypeButton != null) {
            waveTypeButton.setActive(has);
            waveDelayBox.setActive(has);
            waveWarningBox.setActive(has);
            waveIntervalBox.setActive(has);
            waveTypeButton.setLabel(wave == null ? "类型：-" : "类型：" + WaveEditorModel.typeName(wave.type));
            // Only overwrite a field the author is not typing into.
            if (wave != null && !waveDelayBox.isFocused()) {
                waveDelayBox.setValue(String.valueOf(wave.delay), false);
            }
            if (wave != null && waveIntervalBox != null && !waveIntervalBox.isFocused()) {
                waveIntervalBox.setValue(wave.spawnInterval > 0
                        ? String.valueOf(wave.spawnInterval) : "", false);
            }
            if (wave != null && !waveWarningBox.isFocused()) {
                waveWarningBox.setValue(String.valueOf(wave.warningTicks), false);
            }
            if (wave == null) {
                waveDelayBox.setValue("", false);
                waveWarningBox.setValue("", false);
            }
        }
        if (waveEntryList != null) {
            waveEntryList.setItems(has
                    ? wave.entries.stream()
                            .map(entry -> PaletteList.Item.of(context.client(), PaletteList.Kind.ENTITY, "zombie",
                                    Identifier.tryParse(entry.id), "×" + entry.count))
                            .toList()
                    : List.of());
        }
        if (waveCountBox != null) {
            waveCountBox.setValue(currentWaveEntry() == null ? "1"
                    : String.valueOf(currentWaveEntry().count), false);
        }
    }

    private WaveEditorModel.EntryModel currentWaveEntry() {
        if (waveEntryList == null || currentWave() == null) {
            return null;
        }
        int index = waveEntryList.selectedIndex();
        return index >= 0 && index < currentWave().entries.size() ? currentWave().entries.get(index) : null;
    }

    /**
     * Pushes the detail boxes into the selected wave.
     *
     * <p>Runs on Enter and as the first step of {@link #writeTo}. The boxes only exist once the
     * page has been built, so a page that was never opened has nothing to commit and leaves the
     * model the level was loaded with alone.
     */
    private void commitWaveFields() {
        if (waveDelayBox == null) {
            return;
        }
        WaveEditorModel.WaveModel wave = currentWave();
        if (wave == null) {
            return;
        }
        wave.delay = GuiText.parseInt(waveDelayBox.value(), wave.delay, 1, 1_000_000);
        wave.warningTicks = GuiText.parseInt(waveWarningBox.value(), wave.warningTicks, 0, 1_000_000);
        // Empty stays empty: writing today's default into the file would freeze it.
        wave.spawnInterval = waveIntervalBox.value().isBlank()
                ? 0
                : GuiText.parseInt(waveIntervalBox.value(), wave.spawnInterval, 1, 100_000);
    }

    private void applyWaveCount(EditorContext context) {
        WaveEditorModel.EntryModel entry = currentWaveEntry();
        if (entry == null) {
            return;
        }
        entry.count = GuiText.parseInt(waveCountBox.value(), entry.count, 1, 9999);
        refreshWaveDetail(context);
        // The entry is the same object; only its row may have moved.
        waveEntryList.select(currentWave().entries.indexOf(entry));
    }

    private void cycleWaveType(EditorContext context) {
        WaveEditorModel.WaveModel wave = currentWave();
        if (wave == null) {
            return;
        }
        wave.type = switch (wave.type) {
            case "huge" -> "final";
            case "final" -> "small";
            default -> "huge";
        };
        ListEditorSupport.refresh(waveList, new ArrayList<>(waveConfig.waves), wave);
        refreshWaveDetail(context);
    }

    private void addWave(EditorContext context) {
        WaveEditorModel.WaveModel wave = new WaveEditorModel.WaveModel();
        waveConfig.waves.add(wave);
        waveList.setEntries(new ArrayList<>(waveConfig.waves));
        waveList.select(waveConfig.waves.size() - 1);
        refreshWaveDetail(context);
    }

    /**
     * Removes the selected wave.
     *
     * <p>Used to have a branch for "nothing selected": it deleted the <em>last</em> wave, so a
     * click that landed on no row would silently drop an entry the author never chose. The list
     * always has a selection once it has rows, so the branch only ever fired on a state that
     * should not exist - and now nothing happens instead of something surprising.
     */
    private void removeWave(EditorContext context) {
        WaveEditorModel.WaveModel wave = waveList == null ? null : waveList.selected();
        WaveEditorModel.WaveModel next = ListEditorSupport.remove(waveConfig.waves, wave);
        waveList.setEntries(new ArrayList<>(waveConfig.waves));
        if (next != null) {
            waveList.select(waveConfig.waves.indexOf(next));
        }
        refreshWaveDetail(context);
    }

    private void moveWave(EditorContext context, int delta) {
        WaveEditorModel.WaveModel wave = waveList == null ? null : waveList.selected();
        if (!ListEditorSupport.move(waveConfig.waves, wave, delta)) {
            return;
        }
        waveList.setEntries(new ArrayList<>(waveConfig.waves));
        waveList.select(waveConfig.waves.indexOf(wave));
        refreshWaveDetail(context);
    }

    /**
     * A starting wave list: four waves of one zombie each, which the author then edits.
     *
     * <p>An empty level has no waves at all and the game treats that as "no zombies", so
     * a new level is unplayable until the author builds a schedule by hand. The sizes
     * here mirror the demo level's shape rather than being arbitrary.
     */
    private void fillDefaultWaves(EditorContext context) {
        if (!waveConfig.waves.isEmpty()) {
            return;
        }
        String[][] plan = {{"small", "600", "1"}, {"small", "1200", "2"}, {"huge", "1200", "3"},
                {"final", "1200", "3"}};
        for (String[] spec : plan) {
            WaveEditorModel.WaveModel wave = new WaveEditorModel.WaveModel();
            wave.type = spec[0];
            wave.delay = Integer.parseInt(spec[1]);
            wave.warningTicks = "small".equals(spec[0]) ? 0 : 600;
            wave.entries.add(new WaveEditorModel.EntryModel("pvzce:basic_zombie",
                    Integer.parseInt(spec[2])));
            waveConfig.waves.add(wave);
        }
        waveList.setEntries(new ArrayList<>(waveConfig.waves));
        waveList.select(0);
        refreshWaveDetail(context);
    }

    private void addWaveEntry(EditorContext context) {
        WaveEditorModel.WaveModel wave = currentWave();
        if (wave == null || waveZombieList == null) {
            return;
        }
        Identifier id = waveZombieList.selectedId();
        if (id == null) {
            return;
        }
        int amount = GuiText.parseInt(waveCountBox == null ? "1" : waveCountBox.value(), 1, 1, 9999);
        WaveEditorModel.EntryModel target = null;
        for (WaveEditorModel.EntryModel entry : wave.entries) {
            if (entry.id.equals(id.toString())) {
                entry.count += amount;
                target = entry;
                break;
            }
        }
        if (target == null) {
            target = new WaveEditorModel.EntryModel(id.toString(), amount);
            wave.entries.add(target);
        }
        refreshWaveDetail(context);
        int index = wave.entries.indexOf(target);
        if (index >= 0) {
            waveEntryList.select(index);
        }
    }

    private void removeWaveEntry(EditorContext context) {
        WaveEditorModel.WaveModel wave = currentWave();
        WaveEditorModel.EntryModel entry = currentWaveEntry();
        if (wave == null || entry == null) {
            return;
        }
        int removed = wave.entries.indexOf(entry);
        wave.entries.remove(entry);
        refreshWaveDetail(context);
        if (!wave.entries.isEmpty()) {
            waveEntryList.select(Math.min(Math.max(removed, 0), wave.entries.size() - 1));
            waveEntryList.selected();
        }
    }

}
