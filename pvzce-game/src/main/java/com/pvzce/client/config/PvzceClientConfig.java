package com.pvzce.client.config;

import com.pvzce.common.util.MathUtil;
import com.pvzce.common.util.WorldPaths;
import com.moandjiezana.toml.Toml;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Client-side config (volume + video), persisted as
 * {@code config/pvzce-client.toml}.
 */
public final class PvzceClientConfig {
    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger("PVZCE/Config");
    public static final float DEFAULT_MASTER = 1F;
    public static final float DEFAULT_MUSIC = 0.7F;
    public static final float DEFAULT_SFX = 0.8F;

    public static final int MIN_MAX_FPS = 10;
    public static final int DEFAULT_MAX_FPS = 120;
    public static final int UNLIMITED_FPS = 260;
    public static final boolean DEFAULT_VSYNC = true;
    public static final boolean DEFAULT_FULLSCREEN = false;
    /**
     * Whether this client asks for the X11 backend when the session could give it either one.
     *
     * <p>Temporarily on, and it is a switch rather than a rewrite: on a Wayland session the native
     * Wayland backend is the only one where touch works (GLFW's Wayland backend is the one with a
     * {@code wl_touch} device, and the game reads it itself - see {@code client.input.wayland}),
     * but it also comes with no window decorations at all, because libdecor's GTK plugin cannot
     * start inside a JVM and GNOME offers no server-side decorations. X11/XWayland gives the
     * desktop's own title bar and move/resize for free - at the price of touch. Flip this to
     * {@code false} (or run {@code -Dpvzce.platform=wayland}) to go back.
     */
    public static final boolean DEFAULT_PREFER_X11 = true;
    public static final int DEFAULT_WIDTH = 1280;
    public static final int DEFAULT_HEIGHT = 720;
    public static final int AUTO_GUI_SCALE = 0;
    public static final int MAX_MANUAL_GUI_SCALE = 4;
    public static final boolean DEFAULT_SHADERS_ENABLED = true;
    public static final int DEFAULT_WATER_QUALITY = WaterQuality.HIGH;
    /**
     * Whether entering a level plays its opening conversation.
     *
     * <p>A client preference rather than a level's: a player replaying a level they have read
     * three times does not want to click through it again, and the level author has no way to
     * know that. Switching it off skips the overlay entirely - it is not "hide the text", the
     * conversation simply does not start, so nothing has to be clicked and no level tick is
     * spent paused under it.
     */
    public static final boolean DEFAULT_STORY_ENABLED = true;
    /**
     * Who plays when the game has not been told yet.
     *
     * <p>A world is a player's save, so this is also which save the menu opens. It is remembered
     * across restarts ({@link #lastWorld()}), and the default is the one
     * {@link WorldPaths#sanitize} gives an unnamed world, so "no world written yet" and "the
     * player the picker creates by default" are the same name.
     */
    public static final String DEFAULT_WORLD = WorldPaths.DEFAULT_WORLD;

    /**
     * The interface language, as a locale file name ({@code zh_cn}, {@code en_us}, ...).
     *
     * <p>The built-in locale, which is also what the first-run page offers first: the game is
     * authored in Chinese and every key exists there, so a language the player did not ask for is
     * the one thing a first impression must not get wrong.
     */
    public static final String DEFAULT_LANGUAGE = com.pvzce.client.gui.GuiLang.DEFAULT_LOCALE;

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
    private boolean preferX11 = DEFAULT_PREFER_X11;
    private int width = DEFAULT_WIDTH;
    private int height = DEFAULT_HEIGHT;
    private int guiScale = AUTO_GUI_SCALE;
    private boolean shadersEnabled = DEFAULT_SHADERS_ENABLED;
    private int waterQuality = DEFAULT_WATER_QUALITY;
    private boolean storyEnabled = DEFAULT_STORY_ENABLED;
    private String lastWorld = DEFAULT_WORLD;
    /** The interface language; see {@link #DEFAULT_LANGUAGE}. */
    private String language = DEFAULT_LANGUAGE;
    /**
     * Whether the first-run page has been answered.
     *
     * <p><b>A config file that has every other key but not this one means "already played".</b>
     * The file only exists because an earlier build wrote it, and that build had no first-run page
     * - so a missing key in a file that exists is an upgrade, while a missing *file* is a new
     * player (and is the only case that writes the defaults out). Getting this backwards would put
     * an "choose your language" page in front of every existing player once.
     */
    private boolean onboarded;
    /**
     * The player's key bindings, by action name.
     *
     * <p>Stored as GLFW key codes: they are what the poll loop compares against, and the names a
     * player reads are derived from them for display. A file written by an older build simply has
     * fewer entries, and {@code KeyBindings.from} falls back per action.
     */
    private com.pvzce.client.input.KeyBindings keyBindings =
            com.pvzce.client.input.KeyBindings.defaults();
    private Path file;

    public static PvzceClientConfig load(Path gameDir) {
        PvzceClientConfig config = new PvzceClientConfig();
        config.file = gameDir.resolve("config/pvzce-client.toml");
        try {
            if (Files.isRegularFile(config.file)) {
                Toml toml = new Toml().read(config.file.toFile());
                config.masterVolume = MathUtil.clamp01(getFloat(toml, "master_volume", DEFAULT_MASTER));
                config.musicVolume = MathUtil.clamp01(getFloat(toml, "music_volume", DEFAULT_MUSIC));
                config.sfxVolume = MathUtil.clamp01(getFloat(toml, "sfx_volume", DEFAULT_SFX));
                config.maxFps = clampFps(getInt(toml, "max_fps", DEFAULT_MAX_FPS));
                config.vsync = getBoolean(toml, "vsync", DEFAULT_VSYNC);
                config.fullscreen = getBoolean(toml, "fullscreen", DEFAULT_FULLSCREEN);
                config.preferX11 = getBoolean(toml, "prefer_x11", DEFAULT_PREFER_X11);
                config.width = Math.max(320, getInt(toml, "window_width", DEFAULT_WIDTH));
                config.height = Math.max(240, getInt(toml, "window_height", DEFAULT_HEIGHT));
                config.guiScale = Math.max(AUTO_GUI_SCALE,
                        Math.min(MAX_MANUAL_GUI_SCALE, getInt(toml, "gui_scale", AUTO_GUI_SCALE)));
                config.shadersEnabled = getBoolean(toml, "shaders_enabled", DEFAULT_SHADERS_ENABLED);
                config.waterQuality = WaterQuality.clamp(
                        getInt(toml, "water_quality", DEFAULT_WATER_QUALITY));
                config.storyEnabled = getBoolean(toml, "story", DEFAULT_STORY_ENABLED);
                config.lastWorld = WorldPaths.sanitize(getString(toml, "last_world", DEFAULT_WORLD));
                config.language = getString(toml, "language", DEFAULT_LANGUAGE);
                config.onboarded = getBoolean(toml, "onboarded", true);
                config.keyBindings = com.pvzce.client.input.KeyBindings.from(readKeys(toml));
            } else {
                config.save();
            }
        } catch (RuntimeException e) {
            LOGGER.warn("Failed to read the config; using the defaults.", e);
        }
        return config;
    }

    /**
     * The {@code [keys]} table, as action name to GLFW code.
     *
     * <p>Read as a table rather than as flat keys because the file's top half is a fixed list of
     * settings and this half grows with every action - one more flat key per action would have made
     * the flat section the bulk of the file.
     */
    private static java.util.Map<String, Integer> readKeys(Toml toml) {
        java.util.Map<String, Integer> keys = new java.util.LinkedHashMap<>();
        Toml section = toml.getTable("keys");
        if (section == null) {
            return keys;
        }
        for (com.pvzce.client.input.KeyBindings.Action action
                : com.pvzce.client.input.KeyBindings.Action.values()) {
            Long value = section.getLong(action.key());
            if (value != null) {
                keys.put(action.key(), value.intValue());
            }
        }
        return keys;
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

    private static String getString(Toml toml, String key, String fallback) {
        String value = toml.getString(key);
        return value == null ? fallback : value;
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
                    + "\nprefer_x11 = " + preferX11
                    + "\nwindow_width = " + width
                    + "\nwindow_height = " + height
                    + "\ngui_scale = " + guiScale
                    + "\nshaders_enabled = " + shadersEnabled
                    + "\nwater_quality = " + waterQuality
                    + "\nstory = " + storyEnabled
                    + "\nlast_world = \"" + lastWorld + "\""
                    + "\nlanguage = \"" + language + "\""
                    + "\nonboarded = " + onboarded + "\n"
                    + "\n[keys]\n"
                    + keyBinds();
            Files.writeString(file, content);
        } catch (IOException e) {
            LOGGER.error("Failed to write the config", e);
        }
    }

    /** The {@code [keys]} section's body: one {@code action = code} line per action. */
    private String keyBinds() {
        StringBuilder body = new StringBuilder();
        for (java.util.Map.Entry<String, Integer> entry : keyBindings.asMap().entrySet()) {
            body.append(entry.getKey()).append(" = ").append(entry.getValue()).append('\n');
        }
        return body.toString();
    }

    /** The player's key bindings; never null. */
    public com.pvzce.client.input.KeyBindings keyBindings() {
        return keyBindings;
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
        this.masterVolume = MathUtil.clamp01(masterVolume);
    }

    public void setMusicVolume(float musicVolume) {
        this.musicVolume = MathUtil.clamp01(musicVolume);
    }

    public void setSfxVolume(float sfxVolume) {
        this.sfxVolume = MathUtil.clamp01(sfxVolume);
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

    /** Whether to ask for X11 when both backends are available; see {@link #DEFAULT_PREFER_X11}. */
    public boolean preferX11() {
        return preferX11;
    }

    public void setPreferX11(boolean preferX11) {
        this.preferX11 = preferX11;
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

    /** Whether entering a level plays its opening conversation; see {@link #DEFAULT_STORY_ENABLED}. */
    public boolean storyEnabled() {
        return storyEnabled;
    }

    public void setStoryEnabled(boolean storyEnabled) {
        this.storyEnabled = storyEnabled;
    }

    /** The interface language's locale name; never blank. */
    public String language() {
        return language;
    }

    /**
     * Sets the interface language.
     *
     * <p>Applied by the client, not here: reading the file is this class's job, swapping the
     * strings the whole interface is drawn from is the client's (see
     * {@code PvzceClient.setLanguage}).
     */
    public void setLanguage(String language) {
        this.language = language == null || language.isBlank() ? DEFAULT_LANGUAGE : language.trim();
    }

    /**
     * Whether the first-run page has been answered.
     *
     * <p>False for a fresh install; true for a config file written before this setting existed -
     * see the field for why those are two different questions.
     */
    public boolean onboarded() {
        return onboarded;
    }

    public void setOnboarded(boolean onboarded) {
        this.onboarded = onboarded;
    }

    /**
     * The world the game was last playing as, so a restart does not ask again.
     *
     * <p>Not a preference the player sets - it is written whenever the player switches world
     * ({@code PvzceClient#setCurrentWorld}), and the menu opens on it. It is sanitised like
     * every other world name, because this value is used as a directory name by both sides and
     * a remembered name that no directory can have would send the menu to a world that does not
     * exist.
     */
    public String lastWorld() {
        return lastWorld;
    }

    public void setLastWorld(String lastWorld) {
        this.lastWorld = WorldPaths.sanitize(lastWorld);
    }
}
