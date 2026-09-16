package com.pvzce.client;

import com.pvzce.client.config.PvzceClientConfig;
import com.pvzce.common.tag.TestContent;
import com.pvzce.testutil.ClientHarness;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The 剧情 switch: what turns a level's opening conversation on and off.
 *
 * <p>Both places that play one (the seed chooser and the in-game overlay of a level entered
 * without one) build it from {@link PvzceClient#levelDialogue}, so this is the one door the
 * switch has to close - and it has to stay closed across a restart, because the reason to
 * close it is that the player has already read the scene.
 */
class StoryToggleTest {
    /** 1-5 ships a conversation, so it is the level that can tell the two answers apart. */
    private static final String LEVEL_WITH_DIALOGUE = "pvzce:yard/adventure/1_5";

    @BeforeAll
    static void loadContent() throws Exception {
        // The dialogue comes from the level definition, which the client reads from the same
        // data packs the server does - so a windowless client needs them loaded to have an
        // answer at all.
        TestContent.loadBuiltInContentAndTags();
    }

    @Test
    void theSwitchStopsTheDialogueAndIsRemembered() throws Exception {
        try (ClientHarness harness = ClientHarness.create("pvzce-story-toggle")) {
            PvzceClient client = harness.client();
            assertTrue(client.storyEnabled(), "剧情 is on by default");
            assertFalse(client.levelDialogue(LEVEL_WITH_DIALOGUE).isEmpty(),
                    "and a level that has a conversation offers it");

            client.setStoryEnabled(false);
            assertTrue(client.levelDialogue(LEVEL_WITH_DIALOGUE).isEmpty(),
                    "switched off, the level hands out no dialogue at all");
            assertTrue(client.levelDialogue("pvzce:yard/adventure/1_4").isEmpty());

            // Persisted, not per-session: the setting is about a scene the player has read.
            Path configFile = harness.gameDir().resolve("config/pvzce-client.toml");
            PvzceClientConfig reloaded = PvzceClientConfig.load(harness.gameDir());
            assertFalse(reloaded.storyEnabled(), "the switch is in the client config");
            assertTrue(Files.isRegularFile(configFile), "which is written to disk");

            client.setStoryEnabled(true);
            assertFalse(client.levelDialogue(LEVEL_WITH_DIALOGUE).isEmpty(), "and back on");
            assertEquals(true, PvzceClientConfig.load(harness.gameDir()).storyEnabled());
        }
    }
}
