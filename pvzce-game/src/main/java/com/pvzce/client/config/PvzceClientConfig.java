package com.pvzce.client.config;

import com.moandjiezana.toml.Toml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Client-side config (volume + video), persisted as
 * {@code config/pvzce-client.toml}.
 */
public final class PvzceClientConfig {
    public static final float DEFAULT_MASTER = 1F;
    public static final float DEFAULT_MUSIC = 0.7F;
    public static final float DEFAULT_SFX = 0.8F;

    public static final int MIN_MAX_FPS = 10;
    public static final int DEFAULT_MAX_FPS = 120;
    public static final int UNLIMITED_FPS = 260;
    public static final boolean DEFAULT_VSYNC = true;
    public static final boolean DEFAULT_FULLSCREEN = false;
    public static final int DEFAULT_WIDTH = 1280;
    public static final int DEFAULT_HEIGHT = 720;
    public static final int AUTO_GUI_SCALE = 0;
    public static final int MAX_MANUAL_GUI_SCALE = 4;
    public static final boolean DEFAULT_SHADERS_ENABLED = true;
    public static final int DEFAULT_WATER_QUALITY = WaterQuality.HIGH;

    /**
     * How much of the water shader runs.
     *
     * <p>A quality tier only switches terms off in the fragment shader and lowers
     * how much noise it samples; it never changes the geometry or the number of
     * draw calls, so the tiers cannot disagree about where the water is. The
     * {@code shaders_enabled} master switch is separate: turning it off replaces
     * the whole pass with a baked frame, while a low water tier still runs the
     * shader.
     */
    public static final class WaterQuality {
        public static final int LOW = 0;
        public static final int MEDIUM = 1;
        public static final int HIGH = 2;
        public static final int COUNT = 3;

        private WaterQuality() {
        }

        public static int clamp(int value) {
            return Math.max(LOW, Math.min(HIGH, value));
        }

        public static String name(int value) {
            return switch (clamp(value)) {
                case LOW -> "低";
                case MEDIUM -> "中";
                default -> "高";
            };
        }
    }

    private float masterVolume = DEFAULT_MASTER;
    private float musicVolume = DEFAULT_MUSIC;
    private float sfxVolume = DEFAULT_SFX;
    private int maxFps = DEFAULT_MAX_FPS;
    private boolean vsync = DEFAULT_VSYNC;
    private boolean fullscreen = DEFAULT_FULLSCREEN;
    private int width = DEFAULT_WIDTH;
    private int height = DEFAULT_HEIGHT;
    private int guiScale = AUTO_GUI_SCALE;
    private boolean shadersEnabled = DEFAULT_SHADERS_ENABLED;
    private int waterQuality = DEFAULT_WATER_QUALITY;
    private Path file;

    public static PvzceClientConfig load(Path gameDir) {
        PvzceClientConfig config = new PvzceClientConfig();
        config.file = gameDir.resolve("config/pvzce-client.toml");
        try {
            if (Files.isRegularFile(config.file)) {
                Toml toml = new Toml().read(config.file.toFile());
                config.masterVolume = clamp(getFloat(toml, "master_volume", DEFAULT_MASTER));
                config.musicVolume = clamp(getFloat(toml, "music_volume", DEFAULT_MUSIC));
                config.sfxVolume = clamp(getFloat(toml, "sfx_volume", DEFAULT_SFX));
                config.maxFps = clampFps(getInt(toml, "max_fps", DEFAULT_MAX_FPS));
                config.vsync = getBoolean(toml, "vsync", DEFAULT_VSYNC);
                config.fullscreen = getBoolean(toml, "fullscreen", DEFAULT_FULLSCREEN);
                config.width = Math.max(320, getInt(toml, "window_width", DEFAULT_WIDTH));
                config.height = Math.max(240, getInt(toml, "window_height", DEFAULT_HEIGHT));
                config.guiScale = Math.max(AUTO_GUI_SCALE,
                        Math.min(MAX_MANUAL_GUI_SCALE, getInt(toml, "gui_scale", AUTO_GUI_SCALE)));
                config.shadersEnabled = getBoolean(toml, "shaders_enabled", DEFAULT_SHADERS_ENABLED);
                config.waterQuality = WaterQuality.clamp(
                        getInt(toml, "water_quality", DEFAULT_WATER_QUALITY));
            } else {
                config.save();
            }
        } catch (RuntimeException e) {
            System.err.println("Failed to read config: " + e.getMessage());
        }
        return config;
    }

    private static float getFloat(Toml toml, String key, float fallback) {
        Double value = toml.getDouble(key);
        return value == null ? fallback : value.floatValue();
    }

    private static int getInt(Toml toml, String key, int fallback) {
        Long value = toml.getLong(key);
        return value == null ? fallback : value.intValue();
    }

    private static boolean getBoolean(Toml toml, String key, boolean fallback) {
        Boolean value = toml.getBoolean(key);
        return value == null ? fallback : value;
    }

    private static float clamp(float value) {
        return com.pvzce.common.util.MathUtil.clamp01(value);
    }

    private static int clampFps(int value) {
        return Math.max(MIN_MAX_FPS, Math.min(UNLIMITED_FPS, value));
    }

    public void save() {
        if (file == null) {
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            String content = "master_volume = " + masterVolume
                    + "\nmusic_volume = " + musicVolume
                    + "\nsfx_volume = " + sfxVolume
                    + "\nmax_fps = " + maxFps
                    + "\nvsync = " + vsync
                    + "\nfullscreen = " + fullscreen
                    + "\nwindow_width = " + width
                    + "\nwindow_height = " + height
                    + "\ngui_scale = " + guiScale
                    + "\nshaders_enabled = " + shadersEnabled
                    + "\nwater_quality = " + waterQuality + "\n";
            Files.writeString(file, content);
        } catch (IOException e) {
            System.err.println("Failed to write config: " + e.getMessage());
        }
    }

    public float masterVolume() {
        return masterVolume;
    }

    public float musicVolume() {
        return musicVolume;
    }

    public float sfxVolume() {
        return sfxVolume;
    }

    public void setMasterVolume(float masterVolume) {
        this.masterVolume = clamp(masterVolume);
    }

    public void setMusicVolume(float musicVolume) {
        this.musicVolume = clamp(musicVolume);
    }

    public void setSfxVolume(float sfxVolume) {
        this.sfxVolume = clamp(sfxVolume);
    }

    public int maxFps() {
        return maxFps;
    }

    public void setMaxFps(int maxFps) {
        this.maxFps = clampFps(maxFps);
    }

    public boolean vsync() {
        return vsync;
    }

    public void setVsync(boolean vsync) {
        this.vsync = vsync;
    }

    public boolean fullscreen() {
        return fullscreen;
    }

    public void setFullscreen(boolean fullscreen) {
        this.fullscreen = fullscreen;
    }

    public int windowWidth() {
        return width;
    }

    public int windowHeight() {
        return height;
    }

    public void setWindowSize(int width, int height) {
        this.width = Math.max(320, width);
        this.height = Math.max(240, height);
    }

    public int guiScale() {
        return guiScale;
    }

    public void setGuiScale(int guiScale) {
        this.guiScale = Math.max(AUTO_GUI_SCALE, Math.min(MAX_MANUAL_GUI_SCALE, guiScale));
    }

    public boolean shadersEnabled() {
        return shadersEnabled;
    }

    public void setShadersEnabled(boolean shadersEnabled) {
        this.shadersEnabled = shadersEnabled;
    }

    public int waterQuality() {
        return waterQuality;
    }

    public void setWaterQuality(int waterQuality) {
        this.waterQuality = WaterQuality.clamp(waterQuality);
    }
}
