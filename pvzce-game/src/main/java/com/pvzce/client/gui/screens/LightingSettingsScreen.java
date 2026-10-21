package com.pvzce.client.gui.screens;

import com.pvzce.client.PvzceClient;
import com.pvzce.client.config.PvzceClientConfig;
import com.pvzce.client.config.PvzceClientConfig.ShaderEffect;
import com.pvzce.client.gui.GuiLang;
import com.pvzce.client.gui.Screen;
import com.pvzce.client.gui.components.Button;
import com.pvzce.client.gui.components.Slider;
import com.pvzce.client.config.PvzceClientConfig.LightingParameter;
import java.util.ArrayList;
import java.util.List;
import com.pvzce.client.renderer.RenderSystem;

/** Independent lighting controls; edits apply on the next world draw and persist immediately. */
public final class LightingSettingsScreen extends Screen {
    private int titleY, noteY;
    private float titleScale;
    private int page;
    private final List<Runnable> labels = new ArrayList<>();

    public LightingSettingsScreen(PvzceClient client) { super(client); }

    @Override
    protected void init() {
        int width = Math.min(820, client.guiWidth() - 32);
        int x = centerX(width);
        labels.clear();
        int gap = 4;
        titleScale = Math.min(1.8F, Math.max(1F, client.guiHeight() / 240F));
        titleY = client.guiHeight() - Math.round(client.fonts().button().ascent(titleScale)) - 8;
        noteY = titleY - 12 - Math.round(client.fonts().body().ascent(0.8F));
        int reserve = client.guiHeight() - noteY + 8;
        int row = Math.max(12, Math.min(46, (client.guiHeight() - reserve - 36) / 9 - gap));
        int half = (width - gap) / 2;
        int y = client.guiHeight() - reserve - row;
        String[] tabs = {"基础效果", "光效细节", "明暗曝光", "色彩 HSL"};
        int tabWidth = (width - gap * 3) / 4;
        for (int i = 0; i < tabs.length; i++) {
            final int tab = i;
            addWidget(new Button(x + i * (tabWidth + gap), y, tabWidth, row,
                    (page == i ? "• " : "") + GuiLang.raw("pvzce.lighting.tab." + i, tabs[i]), () -> {
                        page = tab; onResize();
                    }));
        }
        y -= row + gap;
        var config = client.config();
        if (page > 0) {
            if (page == 1) {
                effect(x, y, half, row, ShaderEffect.MSAA, "4× MSAA 抗锯齿");
                effect(x + half + gap, y, half, row, ShaderEffect.SUN_SHAFTS, "日光散射（丁达尔）");
                y -= row + gap;
            }
            LightingParameter[] parameters = switch (page) {
                case 1 -> new LightingParameter[]{LightingParameter.SHADOW_SOFTNESS, LightingParameter.BLOOM_STRENGTH,
                        LightingParameter.BLOOM_THRESHOLD, LightingParameter.SPECULAR, LightingParameter.SHAFT_STRENGTH};
                case 2 -> new LightingParameter[]{LightingParameter.EXPOSURE, LightingParameter.BRIGHTNESS, LightingParameter.GAMMA,
                        LightingParameter.CONTRAST, LightingParameter.HIGHLIGHTS, LightingParameter.SHADOWS};
                default -> new LightingParameter[]{LightingParameter.VIBRANCE, LightingParameter.SATURATION,
                        LightingParameter.HUE, LightingParameter.HSL_SATURATION, LightingParameter.LIGHTNESS};
            };
            for (var parameter : parameters) {
                parameter(x, y, width, row, parameter);
                y -= row + gap;
            }
            if (page == 2) {
                effect(x, y, width, row, ShaderEffect.ACES, "ACES 色调映射");
                y -= row + gap;
            }
            footer(x, y, half, row, gap);
            return;
        }
        addWidget(new Button(x, y, width, row, text("enabled", "光影总开关") + "：" + onOff(config.shadersEnabled()), () -> {
            config.setShadersEnabled(!config.shadersEnabled());
            changed();
        }));
        y -= row + gap;
        addWidget(new Button(x, y, half, row, text("shadow_quality", "实时阴影") + "："
                + quality(config.shadowQuality()), () -> {
            config.setShadowQuality(config.shadowQuality() == PvzceClientConfig.ShadowQuality.HIGH
                    ? PvzceClientConfig.ShadowQuality.OFF : config.shadowQuality() + 1);
            changed();
        }));
        effect(x + half + gap, y, half, row, ShaderEffect.SOFT_SHADOWS, "阴影柔化");
        y -= row + gap;
        effect(x, y, half, row, ShaderEffect.DAY_NIGHT, "昼夜色调");
        effect(x + half + gap, y, half, row, ShaderEffect.LOCAL_LIGHTS, "局部光源");
        y -= row + gap;
        effect(x, y, half, row, ShaderEffect.LOCAL_SHADOWS, "局部光源阴影");
        addWidget(new Button(x + half + gap, y, half, row, text("water_quality", "水面质量") + "："
                + quality(config.waterQuality()), () -> {
            config.setWaterQuality((config.waterQuality() + 1) % PvzceClientConfig.WaterQuality.COUNT);
            changed();
        }));
        y -= row + gap;
        effect(x, y, half, row, ShaderEffect.MATERIAL_LIGHTING, "材质起伏与接触阴影");
        effect(x + half + gap, y, half, row, ShaderEffect.BLOOM, "发光泛光");
        y -= row + gap;
        effect(x, y, half, row, ShaderEffect.WATER_WAVES, "水面波动");
        effect(x + half + gap, y, half, row, ShaderEffect.WATER_CAUSTICS, "水下焦散");
        y -= row + gap;
        effect(x, y, half, row, ShaderEffect.WATER_REFLECTIONS, "水面反射与高光");
        effect(x + half + gap, y, half, row, ShaderEffect.WATER_RIPPLES, "交互涟漪");
        y -= row + gap;
        footer(x, y, half, row, gap);
    }

    private void footer(int x, int y, int half, int row, int gap) {
        addWidget(new Button(x, y, half, row, text("reset", "恢复光影默认值"), () -> {
            client.config().resetLighting(); changed();
        }));
        addWidget(new Button(x + half + gap, y, half, row, GuiLang.raw("pvzce.video.done", "完成"), this::requestClose));
    }

    private void parameter(int x, int y, int width, int row, LightingParameter parameter) {
        int labelWidth = width / 2;
        Slider slider = new Slider(x + labelWidth, y, width - labelWidth, row,
                parameter.min(), parameter.max(), client.config().lighting(parameter),
                value -> client.config().setLighting(parameter, value.value())).onCommit(() -> client.config().save());
        slider.setActive(client.config().shadersEnabled());
        addWidget(slider);
        labels.add(() -> {
            String label = GuiLang.raw("pvzce.lighting.parameter." + parameter.name().toLowerCase(java.util.Locale.ROOT), parameter.label())
                    + "：" + String.format(java.util.Locale.ROOT, "%.2f", slider.value());
            float scale = Math.min(1F, (labelWidth - 8) / Math.max(1F, client.fonts().body().width(label, 1F)));
            client.fonts().body().draw(label, x, y + (row + client.fonts().body().lineHeight(scale)) / 2F
                            - client.fonts().body().ascent(scale),
                    scale, 0.9F, 0.92F, 0.95F, 1F);
        });
    }

    private void effect(int x, int y, int width, int height, ShaderEffect effect, String fallback) {
        var config = client.config();
        Button button = new Button(x, y, width, height, text(effect.key(), fallback) + "："
                + onOff(config.effectEnabled(effect)), () -> {
            config.setEffectEnabled(effect, !config.effectEnabled(effect));
            changed();
        });
        addWidget(button);
        boolean shadows = config.shadowQuality() != PvzceClientConfig.ShadowQuality.OFF;
        button.setActive(config.shadersEnabled()
                && (effect != ShaderEffect.SOFT_SHADOWS && effect != ShaderEffect.SUN_SHAFTS || shadows)
                && (effect != ShaderEffect.LOCAL_SHADOWS || shadows && config.effectEnabled(ShaderEffect.LOCAL_LIGHTS))
                && (!effect.name().startsWith("WATER_") || config.waterQuality() > PvzceClientConfig.WaterQuality.LOW));
    }

    private void changed() {
        client.config().save();
        RenderSystem.setShaderEffectsEnabled(client.shadersEnabled());
        onResize();
    }

    private static String text(String key, String fallback) { return GuiLang.raw("pvzce.lighting." + key, fallback); }
    private static String onOff(boolean on) { return GuiLang.raw(on ? "pvzce.on" : "pvzce.off", on ? "开" : "关"); }
    private static String quality(int quality) {
        return quality < 0 ? onOff(false) : GuiLang.raw("pvzce.water." + quality, PvzceClientConfig.WaterQuality.name(quality));
    }

    @Override public boolean blurredBackdrop() { return true; }

    @Override
    public void render() {
        client.beginGuiView();
        if (!renderBlurredBackdrop(0.10F, 0.11F, 0.14F, 0.62F)) {
            renderBackground(0.08F, 0.1F, 0.12F);
            client.drawSolid(0, 0, client.guiWidth(), client.guiHeight(), 0, 0.04F, 0.06F, 0.08F, 0.72F);
        }
        String title = text("title", "光影设置");
        client.fonts().button().draw(title,
                (client.guiWidth() - client.fonts().button().width(title, titleScale)) / 2F, titleY, titleScale, 1, 1, 1, 1);
        String note = client.level().initialized() && client.level().shadersDisabled()
                ? text("level_disabled", "当前关卡禁用光影，设置会用于其它关卡")
                : text("saved", "即时生效 · 自动保存；低水面质量会限制动态效果");
        client.fonts().body().draw(note, (client.guiWidth() - client.fonts().body().width(note, 0.8F)) / 2F,
                noteY, 0.8F, 0.8F, 0.85F, 0.9F, 1F);
        for (var widget : widgets) widget.render(client);
        for (var label : labels) label.run();
    }
}
