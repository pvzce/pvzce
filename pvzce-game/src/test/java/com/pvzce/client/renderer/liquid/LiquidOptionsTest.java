package com.pvzce.client.renderer.liquid;

import com.pvzce.client.config.PvzceClientConfig;
import com.pvzce.client.config.PvzceClientConfig.ShaderEffect;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class LiquidOptionsTest {
    @TempDir Path directory;

    @Test
    void independentSwitchesRemoveOnlyTheirFeaturesAndRespectTheQualityCeiling() {
        var config = PvzceClientConfig.load(directory);
        int original = LiquidRenderer.featuresFor(config);
        config.setEffectEnabled(ShaderEffect.WATER_CAUSTICS, false);
        int withoutCaustics = LiquidRenderer.featuresFor(config);
        assertEquals(0, withoutCaustics & (LiquidShader.FEATURE_CAUSTICS | LiquidShader.FEATURE_CAUSTIC_SHEET));
        assertEquals(original & LiquidShader.FEATURE_WAVES, withoutCaustics & LiquidShader.FEATURE_WAVES);
        config.setEffectEnabled(ShaderEffect.WATER_WAVES, false);
        assertNotEquals(0, LiquidRenderer.featuresFor(config) & LiquidShader.FEATURE_RIPPLES);
        config.setEffectEnabled(ShaderEffect.WATER_REFLECTIONS, false);
        config.setEffectEnabled(ShaderEffect.WATER_RIPPLES, false);
        assertEquals(LiquidShader.FEATURE_SHORE, LiquidRenderer.featuresFor(config));
        config.resetLighting();
        config.setWaterQuality(PvzceClientConfig.WaterQuality.LOW);
        assertEquals(LiquidShader.FEATURE_SHORE, LiquidRenderer.featuresFor(config));
        config.setWaterQuality(PvzceClientConfig.WaterQuality.MEDIUM);
        assertEquals(0, LiquidRenderer.featuresFor(config) & (LiquidShader.FEATURE_CAUSTICS | LiquidShader.FEATURE_FRESNEL));
    }
}
