package com.pvzce.client.gui.screens;

import com.pvzce.client.PvzceClient;
import com.pvzce.client.PvzceWindow;
import com.pvzce.client.config.PvzceClientConfig;
import com.pvzce.client.gui.Screen;
import com.pvzce.client.gui.components.Button;
import com.pvzce.client.gui.components.Slider;
import com.pvzce.client.gui.layout.GuiLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * MC VideoSettings-shaped screen: framerate limit, vsync, fullscreen,
 * resolution presets, shader toggle and GUI scale.
 */
public final class VideoSettingsScreen extends Screen {
    private final List<PvzceWindow.Resolution> resolutions = new ArrayList<>();
    private Slider fpsSlider;
    private int titleY;
    private float titleScale;

    public VideoSettingsScreen(PvzceClient client) {
        super(client);
    }

    @Override
    protected void init() {
        int margin = 16;
        int guiH = client.guiHeight();
        int titleReserve = Math.max(30, Math.min(44, guiH / 6));
        // Legacy ~36px rows enlarged by 40%; auto-compresses for 4x UI.
        // Six rows: fps, vsync+fullscreen, resolution, gui-scale+shaders, water quality, done.
        int rowHeight = GuiLayout.fitHeight(guiH, 52, 6, titleReserve, 8);
        int gap = GuiLayout.gapFor(rowHeight);
        int fullWidth = client.guiWidth() - margin * 2;
        int halfWidth = (fullWidth - gap) / 2;
        int x = margin;
        int topY = guiH - titleReserve;
        int y = topY - gap - rowHeight;
        titleScale = Math.min(1.7F, Math.max(1.0F, guiH / 150F));
        titleY = (y + rowHeight) + Math.round(client.fonts().button().lineHeight(titleScale) * 0.35F);

        // Framerate: MC-style 10..260 slider, 260 renders as "unlimited".
        fpsSlider = new Slider(x, y, fullWidth, rowHeight,
                PvzceClientConfig.MIN_MAX_FPS / 10F, PvzceClientConfig.UNLIMITED_FPS / 10F,
                configMaxFps() / 10F, slider -> {
            // The callback receives the slider, so it never has to capture the field
            // it is still being assigned to.
            int fps = Math.round(slider.value()) * 10;
            client.setMaxFps(fps);
        });
        addWidget(fpsSlider);
        y -= rowHeight + gap;

        Button[] vsync = toggleButton(x, y, halfWidth, rowHeight,
                "垂直同步：" + onOff(client.config().vsync()),
                () -> client.setVsync(!client.config().vsync()));
        Button[] fullscreen = toggleButton(x + halfWidth + gap, y, halfWidth, rowHeight,
                "全屏：" + onOff(client.config().fullscreen()),
                () -> client.setFullscreen(!client.config().fullscreen()));
        addWidget(vsync[0]);
        addWidget(fullscreen[0]);
        y -= rowHeight + gap;

        resolutions.clear();
        resolutions.addAll(client.window().availableResolutions());
        Button[] resolution = cycleButton(x, y, fullWidth, rowHeight, resolutionLabel(),
                () -> {
                    PvzceWindow.Resolution next = nextResolution();
                    client.setWindowResolution(next.width(), next.height());
                    refreshLabels();
                });
        addWidget(resolution[0]);
        y -= rowHeight + gap;

        Button[] guiScale = cycleButton(x, y, halfWidth, rowHeight, guiScaleLabel(),
                () -> {
                    client.setGuiScaleSetting(nextGuiScale());
                    refreshLabels();
                });
        Button[] shaders = toggleButton(x + halfWidth + gap, y, halfWidth, rowHeight,
                "着色器：" + onOff(client.config().shadersEnabled()),
                () -> {
                    client.setShadersEnabled(!client.config().shadersEnabled());
                    refreshLabels();
                });
        addWidget(guiScale[0]);
        addWidget(shaders[0]);
        y -= rowHeight + gap;

        Button[] water = cycleButton(x, y, fullWidth, rowHeight, waterQualityLabel(),
                () -> {
                    client.setWaterQuality(nextWaterQuality());
                    refreshLabels();
                });
        addWidget(water[0]);
        y -= rowHeight + gap;

        addWidget(new Button(x, Math.max(4, y), fullWidth,
                rowHeight, "完成", this::requestClose));
    }

    private Button[] toggleButton(int x, int y, int width, int height, String label, Runnable action) {
        Button[] holder = new Button[1];
        holder[0] = new Button(x, y, width, height, label, () -> {
            action.run();
            holder[0].setLabel(currentToggleLabel(holder[0]));
        });
        return holder;
    }

    private Button[] cycleButton(int x, int y, int width, int height, String label, Runnable action) {
        Button[] holder = new Button[1];
        holder[0] = new Button(x, y, width, height, label, () -> {
            action.run();
            holder[0].setLabel(currentCycleLabel(holder[0]));
        });
        return holder;
    }

    private String currentToggleLabel(Button button) {
        if (button.label().startsWith("垂直同步")) {
            return "垂直同步：" + onOff(client.config().vsync());
        }
        if (button.label().startsWith("全屏")) {
            return "全屏：" + onOff(client.config().fullscreen());
        }
        return "着色器：" + onOff(client.config().shadersEnabled());
    }

    private String currentCycleLabel(Button button) {
        if (button.label().startsWith("UI")) {
            return guiScaleLabel();
        }
        if (button.label().startsWith("水面质量")) {
            return waterQualityLabel();
        }
        return resolutionLabel();
    }

    private void refreshLabels() {
        onResize();
    }

    private int configMaxFps() {
        return client.config().maxFps();
    }

    private String resolutionLabel() {
        PvzceWindow.Resolution current = client.window().currentResolution();
        return "分辨率：" + current.label();
    }

    private PvzceWindow.Resolution nextResolution() {
        int index = currentResolutionIndex();
        return resolutions.isEmpty()
                ? client.window().currentResolution()
                : resolutions.get((index + 1) % resolutions.size());
    }

    private int currentResolutionIndex() {
        for (int i = 0; i < resolutions.size(); i++) {
            PvzceWindow.Resolution r = resolutions.get(i);
            if (r.width() == client.window().preferredWidth() && r.height() == client.window().preferredHeight()) {
                return i;
            }
        }
        return 0;
    }

    private String guiScaleLabel() {
        int setting = client.config().guiScale();
        int effective = client.guiScale();
        if (setting == PvzceClientConfig.AUTO_GUI_SCALE) {
            return "UI 大小：自动（" + effective + "x）";
        }
        return "UI 大小：" + effective + "x";
    }

    private String waterQualityLabel() {
        return "水面质量：" + PvzceClientConfig.WaterQuality.name(client.config().waterQuality());
    }

    /** Cycles low -> medium -> high -> low; the tiers control shader terms only. */
    private int nextWaterQuality() {
        int count = PvzceClientConfig.WaterQuality.COUNT;
        return (client.config().waterQuality() + 1) % count;
    }

    private int nextGuiScale() {
        int max = Math.max(1, client.maxAvailableGuiScale());
        int current = client.config().guiScale();
        if (current == PvzceClientConfig.AUTO_GUI_SCALE) {
            return 1;
        }
        int next = current + 1;
        return next > max ? PvzceClientConfig.AUTO_GUI_SCALE : next;
    }

    private static String onOff(boolean value) {
        return value ? "开" : "关";
    }

    @Override
    public boolean blurredBackdrop() {
        return true;
    }

    @Override
    public void render() {
        client.beginGuiView();
        // The frame this page was opened over, blurred: a settings page is a page *over* whatever
        // the player was doing, and the menu art behind it has nothing to do with that.
        if (!renderBlurredBackdrop(0.10F, 0.11F, 0.14F, 0.62F)) {
            renderBackground(0.08F, 0.1F, 0.12F);
        }
        String title = "视频设置";
        client.fonts().button().draw(title, (client.guiWidth() - client.fonts().button().width(title, titleScale)) / 2F,
                titleY, titleScale, 1, 1, 1, 1);
        if (fpsSlider != null) {
            client.fonts().body().draw(fpsLabel(), 16, fpsSlider.y() + fpsSlider.height() + 2, 0.8F,
                    0.9F, 0.9F, 0.9F, 1F);
        }
        for (var widget : widgets) {
            widget.render(client);
        }
    }

    private String fpsLabel() {
        int fps = client.config().maxFps();
        return "帧率：" + (fps >= PvzceClientConfig.UNLIMITED_FPS ? "无限制" : fps + " FPS");
    }
}
