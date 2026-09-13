package com.pvzce.client.gui.config;

import com.pvzce.client.PvzceClient;
import com.pvzce.client.gui.GuiText;
import com.pvzce.client.gui.Screen;
import com.pvzce.client.gui.components.Button;
import com.pvzce.client.gui.components.Slider;

import java.util.ArrayList;
import java.util.List;

/**
 * Cloth-Config-shaped screen built from a ConfigBuilder.
 *
 * <p>Layout is computed once into a row list that both {@link #init()} (which places
 * the sliders) and {@link #render()} (which draws the labels) walk. They used to
 * run two independent copies of the same loop with different arithmetic - one
 * accounted for a category header row and the other did not - so every label was
 * painted one header row below its slider, and the error grew by another row for
 * each extra category.
 */
public final class ConfigScreen extends Screen {
    private final String title;
    private final List<ConfigCategory> categories;
    private final Runnable savingRunnable;
    private final List<FloatConfigEntry> floatEntries = new ArrayList<>();
    private final List<Row> rows = new ArrayList<>();
    private int rowGap = 48;

    /** One laid-out line: either a category header or a single setting. */
    private record Row(ConfigCategory category, ConfigEntry<?> entry, int y, boolean header) {
    }

    public ConfigScreen(PvzceClient client, String title, List<ConfigCategory> categories, Runnable savingRunnable) {
        super(client);
        this.title = title;
        this.categories = categories;
        this.savingRunnable = savingRunnable;
    }

    @Override
    protected void init() {
        layoutRows();
        int x = centerX(contentWidth());
        for (Row row : rows) {
            if (row.header() || !(row.entry() instanceof FloatConfigEntry floatEntry)) {
                continue;
            }
            floatEntries.add(floatEntry);
            Slider slider = new Slider(x + 120, row.y(), Math.max(40, contentWidth() - 230), 28,
                    floatEntry.min(), floatEntry.max(), floatEntry.value(), changed -> {
                floatEntry.setValue(changed.value());
            });
            // Persisting is a commit action, not a per-frame one.
            slider.onCommit(savingRunnable);
            addWidget(slider);
        }
        int doneHeight = Math.min(56, Math.max(40, client.guiHeight() / 5));
        addWidget(new Button(centerX(160), 8, 160, doneHeight, "完成", this::requestClose));
    }

    /** Walks the categories exactly once; both callers read the result. */
    private void layoutRows() {
        rows.clear();
        floatEntries.clear();
        int guiH = client.guiHeight();
        int entryCount = categories.stream().mapToInt(c -> c.entries().size()).sum();
        rowGap = Math.max(34, Math.min(64, (guiH - 96) / Math.max(1, entryCount)));
        int topY = guiH - 34;
        int y = topY - 34;
        for (ConfigCategory category : categories) {
            rows.add(new Row(category, null, y, true));
            y -= 26;
            for (ConfigEntry<?> entry : category.entries()) {
                rows.add(new Row(category, entry, y, false));
                y -= rowGap;
            }
            y -= 26;
        }
    }

    private int contentWidth() {
        return Math.min(520, client.guiWidth() - 16);
    }

    public float valueOf(String id) {
        for (FloatConfigEntry entry : floatEntries) {
            if (entry.id().equals(id)) {
                return entry.value();
            }
        }
        return 0F;
    }

    @Override
    public void render() {
        client.beginGuiView();
        renderBackground(0.08F, 0.1F, 0.12F);
        if (rows.isEmpty()) {
            // Defensive: render may run before init on the very first frame.
            layoutRows();
        }
        float scale = Math.min(1.8F, client.guiHeight() / 120F);
        client.font().draw(title, (client.guiWidth() - client.font().width(title, scale)) / 2F,
                client.guiHeight() - client.font().lineHeight(scale) - 4, scale, 1, 1, 1, 1);
        int x = centerX(contentWidth());
        for (Row row : rows) {
            if (row.header()) {
                client.font().draw(row.category().id(), x + 10, row.y() + 14, 0.9F, 0.6F, 0.8F, 0.6F, 1F);
                continue;
            }
            client.font().draw(row.entry().label(), x + 10, row.y() + 10, 0.9F, 0.9F, 0.9F, 0.9F, 1F);
            if (row.entry() instanceof FloatConfigEntry floatEntry) {
                client.font().draw(GuiText.formatPercent(floatEntry.value()),
                        x + Math.max(320, client.guiWidth() - 90), row.y() + 10, 0.8F, 0.7F, 0.8F, 0.9F, 1F);
            }
        }
        for (var widget : widgets) {
            widget.render(client);
        }
    }
}
