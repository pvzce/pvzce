package com.pvzce.client.gui.screens;

import com.pvzce.client.PvzceClient;
import com.pvzce.client.config.PvzceClientConfig;
import com.pvzce.client.config.PvzceClientConfig.ShaderEffect;
import com.pvzce.client.gui.GuiLang;
import com.pvzce.client.gui.Screen;
import com.pvzce.client.gui.components.Button;
import com.pvzce.client.gui.layout.GuiLayout;
import com.pvzce.client.renderer.RenderSystem;

/** Independent lighting controls; edits apply on the next world draw and persist immediately. */
public final class LightingSettingsScreen extends Screen {
    private int titleY, noteY;
    private float titleScale;

    public LightingSettingsScreen(PvzceClient client) { super(client); }

    @Override
    protected void init() {
        int width = Math.min(820, client.guiWidth() - 32);
        int x = centerX(width);
        int reserve = Math.max(50, Math.min(76, client.guiHeight() / 5));
        int row = GuiLayout.fitHeight(client.guiHeight(), 52, 8, reserve, 8);
        int gap = GuiLayout.gapFor(row), half = (width - gap) / 2;
        int y = client.guiHeight() - reserve - row;
        titleScale = Math.min(1.8F, Math.max(1F, client.guiHeight() / 240F));
        titleY = client.guiHeight() - Math.round(client.fonts().button().lineHeight(titleScale)) - 10;
        noteY = y + row + 6;
        var config = client.config();
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
        addWidget(new Button(x, y, half, row, text("reset", "恢复光影默认值"), () -> {
            config.resetLighting();
            changed();
        }));
        addWidget(new Button(x + half + gap, y, half, row, GuiLang.raw("pvzce.video.done", "完成"), this::requestClose));
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
                && (effect != ShaderEffect.SOFT_SHADOWS || shadows)
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
        if (!renderBlurredBackdrop(0.10F, 0.11F, 0.14F, 0.62F)) renderBackground(0.08F, 0.1F, 0.12F);
        String title = text("title", "光影设置");
        client.fonts().button().draw(title,
                (client.guiWidth() - client.fonts().button().width(title, titleScale)) / 2F, titleY, titleScale, 1, 1, 1, 1);
        String note = client.level().initialized() && client.level().shadersDisabled()
                ? text("level_disabled", "当前关卡禁用光影，设置会用于其它关卡")
                : text("saved", "即时生效 · 自动保存；低水面质量会限制动态效果");
        client.fonts().body().draw(note, (client.guiWidth() - client.fonts().body().width(note, 0.8F)) / 2F,
                noteY, 0.8F, 0.8F, 0.85F, 0.9F, 1F);
        for (var widget : widgets) widget.render(client);
    }
}
