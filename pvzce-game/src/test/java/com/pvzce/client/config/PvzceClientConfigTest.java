package com.pvzce.client.config;

import com.pvzce.common.util.WorldPaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
/** M5: TOML volume config round-trip. */
class PvzceClientConfigTest {

    /** A fresh directory per test; JUnit deletes it, and prints it when a test fails. */
    @TempDir
    Path dir;
    @Test
    void configRoundTripsAndClamps() throws Exception {
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

    /**
     * The language round-trips, and an unset one is the language the game is authored in.
     */
    @Test
    void theInterfaceLanguageRoundTrips() throws Exception {
        PvzceClientConfig config = PvzceClientConfig.load(dir);
        assertEquals(PvzceClientConfig.DEFAULT_LANGUAGE, config.language(),
                "a fresh install is Chinese, which is also what a language the player did not ask "
                        + "for must never override");

        config.setLanguage("en_us");
        config.save();
        assertEquals("en_us", PvzceClientConfig.load(dir).language());

        // A blank or missing value falls back rather than writing a locale nothing provides.
        config.setLanguage("  ");
        assertEquals(PvzceClientConfig.DEFAULT_LANGUAGE, config.language());
    }

    /**
     * A missing config file is a new player; a config file without the key is an existing one.
     *
     * <p>The two questions are different, and only the first should see the first-run page: the
     * key is absent from every file an earlier build wrote, so treating "absent" as "new" would put
     * a language chooser in front of every existing player exactly once. The file's own existence
     * is the discriminator, and this is the test that keeps it that way.
     */
    @Test
    void onlyAFreshConfigCountsAsAFirstRun() throws Exception {
        PvzceClientConfig fresh = PvzceClientConfig.load(dir);
        assertFalse(fresh.onboarded(), "no config file at all means nobody has answered the page");

        fresh.setOnboarded(true);
        fresh.save();
        assertTrue(PvzceClientConfig.load(dir).onboarded(), "and the answer is remembered");

        // An upgrade: the file is there, the key is not.
        Path file = dir.resolve("config/pvzce-client.toml");
        String withoutKey = Files.readString(file).lines()
                .filter(line -> !line.startsWith("onboarded"))
                .reduce("", (a, b) -> a + b + "\n");
        Files.writeString(file, withoutKey);
        assertTrue(PvzceClientConfig.load(dir).onboarded(),
                "a config written before the page existed is a player who has already played");
    }

    @Test
    void videoDefaultsAndRoundTripAreMcLike() throws Exception {
        PvzceClientConfig config = PvzceClientConfig.load(dir);
        assertEquals(PvzceClientConfig.DEFAULT_MAX_FPS, config.maxFps());
        assertEquals(PvzceClientConfig.DEFAULT_VSYNC, config.vsync());
        assertEquals(PvzceClientConfig.DEFAULT_WIDTH, config.windowWidth());
        assertEquals(PvzceClientConfig.DEFAULT_HEIGHT, config.windowHeight());
        assertEquals(PvzceClientConfig.AUTO_GUI_SCALE, config.guiScale());
        assertEquals(PvzceClientConfig.DEFAULT_SHADERS_ENABLED, config.shadersEnabled());
        assertTrue(PvzceClientConfig.DEFAULT_STORY_ENABLED, "剧情 plays until the player says otherwise");
        assertTrue(config.storyEnabled(), "which is also what an unwritten config means");

        config.setMaxFps(240);
        config.setVsync(false);
        config.setFullscreen(true);
        config.setWindowSize(1920, 1080);
        config.setGuiScale(2);
        config.setShadersEnabled(false);
        config.setStoryEnabled(false);
        config.save();

        PvzceClientConfig loaded = PvzceClientConfig.load(dir);
        assertEquals(240, loaded.maxFps());
        assertEquals(false, loaded.vsync());
        assertEquals(true, loaded.fullscreen());
        assertEquals(1920, loaded.windowWidth());
        assertEquals(1080, loaded.windowHeight());
        assertEquals(2, loaded.guiScale());
        assertEquals(false, loaded.shadersEnabled());
        assertEquals(false, loaded.storyEnabled(), "the 剧情 switch survives a restart");

        loaded.setMaxFps(9999);
        assertEquals(PvzceClientConfig.UNLIMITED_FPS, loaded.maxFps());
    }

    /**
     * Who is playing survives a restart, which is the only reason the key exists.
     *
     * <p>A world name is a directory name, so the value is sanitised on the way in: the name in
     * the file and the directory the menu then opens cannot disagree.
     */
    @Test
    void rememberedWorldRoundTripsAndIsSanitised() throws Exception {
        PvzceClientConfig config = PvzceClientConfig.load(dir);
        assertEquals(PvzceClientConfig.DEFAULT_WORLD, config.lastWorld(),
                "an unwritten config plays as the default player");

        config.setLastWorld("my save");
        config.save();

        PvzceClientConfig loaded = PvzceClientConfig.load(dir);
        assertEquals("my_save", loaded.lastWorld(), "the same rule the save directory uses");
        assertEquals(WorldPaths.worldDir(dir, "my save").getFileName().toString(), loaded.lastWorld());
    }
}
