package com.pvzce.client.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** M5: TOML volume config round-trip. */
class PvzceClientConfigTest {
    @Test
    void configRoundTripsAndClamps() throws Exception {
        Path dir = Files.createTempDirectory("pvzce-config");
        PvzceClientConfig config = PvzceClientConfig.load(dir);
        assertEquals(PvzceClientConfig.DEFAULT_MASTER, config.masterVolume(), 0.0001F);

        config.setMasterVolume(0.45F);
        config.setMusicVolume(0.3F);
        config.setSfxVolume(0.8F);
        config.save();

        PvzceClientConfig loaded = PvzceClientConfig.load(dir);
        assertEquals(0.45F, loaded.masterVolume(), 0.0001F);
        assertEquals(0.3F, loaded.musicVolume(), 0.0001F);
        assertEquals(0.8F, loaded.sfxVolume(), 0.0001F);

        loaded.setMasterVolume(99F);
        assertEquals(1F, loaded.masterVolume(), 0.0001F);
    }

    @Test
    void videoDefaultsAndRoundTripAreMcLike() throws Exception {
        Path dir = Files.createTempDirectory("pvzce-config-video");
        PvzceClientConfig config = PvzceClientConfig.load(dir);
        assertEquals(PvzceClientConfig.DEFAULT_MAX_FPS, config.maxFps());
        assertEquals(PvzceClientConfig.DEFAULT_VSYNC, config.vsync());
        assertEquals(PvzceClientConfig.DEFAULT_WIDTH, config.windowWidth());
        assertEquals(PvzceClientConfig.DEFAULT_HEIGHT, config.windowHeight());
        assertEquals(PvzceClientConfig.AUTO_GUI_SCALE, config.guiScale());
        assertEquals(PvzceClientConfig.DEFAULT_SHADERS_ENABLED, config.shadersEnabled());

        config.setMaxFps(240);
        config.setVsync(false);
        config.setFullscreen(true);
        config.setWindowSize(1920, 1080);
        config.setGuiScale(2);
        config.setShadersEnabled(false);
        config.save();

        PvzceClientConfig loaded = PvzceClientConfig.load(dir);
        assertEquals(240, loaded.maxFps());
        assertEquals(false, loaded.vsync());
        assertEquals(true, loaded.fullscreen());
        assertEquals(1920, loaded.windowWidth());
        assertEquals(1080, loaded.windowHeight());
        assertEquals(2, loaded.guiScale());
        assertEquals(false, loaded.shadersEnabled());

        loaded.setMaxFps(9999);
        assertEquals(PvzceClientConfig.UNLIMITED_FPS, loaded.maxFps());
    }
}
