package com.pvzce.client.gui.screens;

import com.pvzce.client.PvzceClient;
import com.pvzce.client.PvzceWindow;
import com.pvzce.client.config.PvzceClientConfig;
import com.pvzce.client.gui.GuiLang;
import com.pvzce.client.gui.Screen;
import com.pvzce.client.gui.components.Button;
import com.pvzce.client.gui.components.Slider;
import com.pvzce.client.gui.layout.GuiLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * MC VideoSettings-shaped screen: framerate limit, vsync, fullscreen, resolution presets, GUI size,
 * language, shader toggle and water quality.
 *
 * <p><b>Every label here is a language key</b>, and the two rows that pair up are the interface's
 * own settings (size and language). The language row is the reason this page stopped hardcoding its
 * text: a player who switched the interface to English on the first-run page and then opened the
 * page that owns that setting would otherwise find it in Chinese - and, worse, the row labels were
 * matched back to their settings by {@code label.startsWith("全屏")}, which a translation breaks
 * silently. Labels are now built from the settings on every rebuild, which is also what the old
 * {@code setLabel}-after-click path was trying (and failing) to do.
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

        addWidget(new Button(x, y, halfWidth, rowHeight,
                toggleLabel("pvzce.video.vsync", "垂直同步", client.config().vsync()),
                () -> {
                    client.setVsync(!client.config().vsync());
                    refreshLabels();
                }));
        addWidget(new Button(x + halfWidth + gap, y, halfWidth, rowHeight,
                toggleLabel("pvzce.video.fullscreen", "全屏", client.config().fullscreen()),
                () -> {
                    client.setFullscreen(!client.config().fullscreen());
                    refreshLabels();
                }));
        y -= rowHeight + gap;

        resolutions.clear();
        resolutions.addAll(client.window().availableResolutions());
        addWidget(new Button(x, y, fullWidth, rowHeight, resolutionLabel(), () -> {
            PvzceWindow.Resolution next = nextResolution();
            client.setWindowResolution(next.width(), next.height());
            refreshLabels();
        }));
        y -= rowHeight + gap;

        // The interface's own two settings, side by side: how big it is drawn and which language
        // it is drawn in.
        addWidget(new Button(x, y, halfWidth, rowHeight, guiScaleLabel(), () -> {
            client.setGuiScaleSetting(nextGuiScale());
            refreshLabels();
        }));
        addWidget(new Button(x + halfWidth + gap, y, halfWidth, rowHeight, languageLabel(), () -> {
            client.setLanguage(nextLanguage());
            refreshLabels();
        }));
        y -= rowHeight + gap;

        addWidget(new Button(x, y, halfWidth, rowHeight,
                toggleLabel("pvzce.video.shaders", "着色器", client.config().shadersEnabled()),
                () -> {
                    client.setShadersEnabled(!client.config().shadersEnabled());
                    refreshLabels();
                }));
        addWidget(new Button(x + halfWidth + gap, y, halfWidth, rowHeight, waterQualityLabel(),
                () -> {
                    client.setWaterQuality(nextWaterQuality());
                    refreshLabels();
                }));
        y -= rowHeight + gap;

        addWidget(new Button(x, Math.max(4, y), fullWidth, rowHeight,
                GuiLang.raw("pvzce.video.done", "完成"), this::requestClose));
    }

    /**
     * Rebuilds the row labels from the settings.
     *
     * <p>A rebuild rather than a {@code setLabel} on each button: every one of these rows changes
     * something the layout depends on (the resolution, the GUI size), so the page is going to be
     * rebuilt anyway - and a label "refresh" that matched buttons by their text is what a
     * translation breaks.
     */
    private void refreshLabels() {
        onResize();
    }

    /** {@code 名称：开/关}, both halves from the language files. */
    private static String toggleLabel(String key, String fallback, boolean value) {
        return GuiLang.raw(key, fallback) + "：" + onOff(value);
    }

    private int configMaxFps() {
        return client.config().maxFps();
    }

    private String resolutionLabel() {
        PvzceWindow.Resolution current = client.window().currentResolution();
        return GuiLang.raw("pvzce.video.resolution", "分辨率") + "：" + current.label();
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
        String name = GuiLang.raw("pvzce.video.gui_scale", "UI 大小");
        int setting = client.config().guiScale();
        int effective = client.guiScale();
        if (setting == PvzceClientConfig.AUTO_GUI_SCALE) {
            return name + "：" + GuiLang.raw("pvzce.onboarding.auto", "自动") + "（" + effective + "x）";
        }
        return name + "：" + effective + "x";
    }

    /** The interface's language, named in its own words. */
    private String languageLabel() {
        return GuiLang.raw("pvzce.video.language", "语言") + "：" + GuiLang.localeName(language());
    }

    /** The locale in force; the config is the one that knows. */
    private String language() {
        return client.config().language();
    }

    /** The next language the pack stack offers, wrapping around. */
    private String nextLanguage() {
        List<String> locales = GuiLang.availableLocales(client.resources());
        if (locales.isEmpty()) {
            return language();
        }
        int index = locales.indexOf(language());
        return locales.get((index + 1) % locales.size());
    }

    private String waterQualityLabel() {
        return GuiLang.raw("pvzce.video.water", "水面质量") + "："
                + GuiLang.raw("pvzce.water." + client.config().waterQuality(),
                        PvzceClientConfig.WaterQuality.name(client.config().waterQuality()));
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
        return GuiLang.raw(value ? "pvzce.on" : "pvzce.off", value ? "开" : "关");
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
        String title = GuiLang.raw("pvzce.video.title", "视频设置");
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
        return GuiLang.raw("pvzce.video.fps", "帧率") + "："
                + (fps >= PvzceClientConfig.UNLIMITED_FPS
                        ? GuiLang.raw("pvzce.video.unlimited", "无限制") : fps + " FPS");
    }
}
