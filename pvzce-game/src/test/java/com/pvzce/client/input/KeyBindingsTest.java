package com.pvzce.client.input;

import com.pvzce.client.config.PvzceClientConfig;
import com.pvzce.testutil.TestDirs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The key table: what ships, what binding does to the action that had the key, and whether a
 * choice survives a restart.
 *
 * <p>The default table itself is the contract with the player - F11 fullscreen, T chat, F10 health,
 * 1..4 the tools - so it is asserted by name rather than by counting entries. The two behaviours
 * worth pinning are the ones a player will meet: binding a key takes it away from whoever had it
 * (two actions on one key means one of them never fires), and ESC does not become a binding.
 */
class KeyBindingsTest {
    @Test
    void theShippedTableIsWhatTheGameDocuments() {
        KeyBindings keys = KeyBindings.defaults();
        assertEquals(GLFW.GLFW_KEY_F11, keys.code(KeyBindings.Action.FULLSCREEN));
        assertEquals(GLFW.GLFW_KEY_F3, keys.code(KeyBindings.Action.DEBUG_OVERLAY));
        assertEquals(GLFW.GLFW_KEY_F2, keys.code(KeyBindings.Action.SCREENSHOT));
        assertEquals(GLFW.GLFW_KEY_F10, keys.code(KeyBindings.Action.HEALTH_BARS));
        assertEquals(GLFW.GLFW_KEY_T, keys.code(KeyBindings.Action.CHAT));
        assertEquals(GLFW.GLFW_KEY_SLASH, keys.code(KeyBindings.Action.COMMAND));
        assertEquals(GLFW.GLFW_KEY_1, keys.code(KeyBindings.Action.TOOL_SHOVEL));
        assertEquals(GLFW.GLFW_KEY_2, keys.code(KeyBindings.Action.TOOL_GLOVE));
        assertEquals(GLFW.GLFW_KEY_3, keys.code(KeyBindings.Action.TOOL_HAMMER));
        assertEquals(GLFW.GLFW_KEY_4, keys.code(KeyBindings.Action.TOOL_WATERING_CAN));
        assertEquals(GLFW.GLFW_KEY_UNKNOWN, keys.code(KeyBindings.Action.TOOL_VASE),
                "four digits were asked for and there are five tools; the fifth is deliberately free");
        assertFalse(keys.isModified());
    }

    @Test
    void noTwoActionsShipOnTheSameKey() {
        KeyBindings keys = KeyBindings.defaults();
        for (KeyBindings.Action action : KeyBindings.Action.values()) {
            int code = keys.code(action);
            if (code == GLFW.GLFW_KEY_UNKNOWN) {
                continue;
            }
            assertEquals(action, keys.actionFor(code),
                    "two actions on one key means one of them can never fire");
        }
    }

    @Test
    void bindingTakesTheKeyAwayFromWhoeverHadIt() {
        KeyBindings keys = KeyBindings.defaults();
        KeyBindings.Action displaced = keys.bind(KeyBindings.Action.TOOL_VASE, GLFW.GLFW_KEY_F3);
        assertEquals(KeyBindings.Action.DEBUG_OVERLAY, displaced, "and says who lost it");
        assertEquals(GLFW.GLFW_KEY_F3, keys.code(KeyBindings.Action.TOOL_VASE));
        assertEquals(GLFW.GLFW_KEY_UNKNOWN, keys.code(KeyBindings.Action.DEBUG_OVERLAY),
                "the action that lost the key is left unbound rather than moved somewhere else");
        assertEquals(KeyBindings.Action.TOOL_VASE, keys.actionFor(GLFW.GLFW_KEY_F3));
        assertTrue(keys.isModified());
    }

    @Test
    void rebindingAnActionToItsOwnKeyIsNotAChange() {
        KeyBindings keys = KeyBindings.defaults();
        assertNull(keys.bind(KeyBindings.Action.CHAT, GLFW.GLFW_KEY_T));
        assertEquals(GLFW.GLFW_KEY_T, keys.code(KeyBindings.Action.CHAT));
        assertFalse(keys.isModified());
    }

    @Test
    void resettingPutsEveryActionBack() {
        KeyBindings keys = KeyBindings.defaults();
        keys.bind(KeyBindings.Action.CHAT, GLFW.GLFW_KEY_F1);
        keys.unbind(KeyBindings.Action.SCREENSHOT);
        assertTrue(keys.isModified());
        keys.resetToDefaults();
        assertFalse(keys.isModified());
        assertEquals(GLFW.GLFW_KEY_T, keys.code(KeyBindings.Action.CHAT));
        assertEquals(GLFW.GLFW_KEY_F2, keys.code(KeyBindings.Action.SCREENSHOT));
    }

    @Test
    void aFileThatNamesAnActionThisBuildDoesNotHaveKeepsTheRest() {
        // What a config written by a newer build looks like to an older one, and the reason the
        // reader works per action rather than replacing the table wholesale.
        KeyBindings keys = KeyBindings.from(Map.of("chat", GLFW.GLFW_KEY_F1,
                "teleport", GLFW.GLFW_KEY_F5));
        assertEquals(GLFW.GLFW_KEY_F1, keys.code(KeyBindings.Action.CHAT));
        assertEquals(GLFW.GLFW_KEY_F10, keys.code(KeyBindings.Action.HEALTH_BARS),
                "an action the file does not mention keeps the key it ships with");
    }

    @Test
    void aBindingSurvivesARestart(@TempDir Path gameDir) {
        PvzceClientConfig config = PvzceClientConfig.load(gameDir);
        config.keyBindings().bind(KeyBindings.Action.TOOL_VASE, GLFW.GLFW_KEY_5);
        config.keyBindings().bind(KeyBindings.Action.SCREENSHOT, GLFW.GLFW_KEY_F8);
        config.setGuiScale(3);
        config.save();

        PvzceClientConfig reloaded = PvzceClientConfig.load(gameDir);
        assertEquals(GLFW.GLFW_KEY_5, reloaded.keyBindings().code(KeyBindings.Action.TOOL_VASE));
        assertEquals(GLFW.GLFW_KEY_F8, reloaded.keyBindings().code(KeyBindings.Action.SCREENSHOT));
        assertEquals(3, reloaded.guiScale(),
                "and the flat settings above the [keys] table still read the same");
    }

    @Test
    void aConfigWrittenBeforeTheKeyTableExistedLoadsTheDefaults(@TempDir Path gameDir)
            throws Exception {
        Files.createDirectories(gameDir.resolve("config"));
        Files.writeString(gameDir.resolve("config/pvzce-client.toml"),
                "master_volume = 0.5\nmax_fps = 60\n");
        PvzceClientConfig config = PvzceClientConfig.load(gameDir);
        assertEquals(0.5F, config.masterVolume(), 0.0001F);
        assertFalse(config.keyBindings().isModified(),
                "no [keys] table means the shipped table, not an empty one");
    }

    @Test
    void everyActionHasAReadableKeyName() {
        for (KeyBindings.Action action : KeyBindings.Action.values()) {
            if (action.defaultCode() == GLFW.GLFW_KEY_UNKNOWN) {
                continue;
            }
            String name = KeyBindings.keyName(action.defaultCode());
            assertNotNull(name);
            assertFalse(name.isBlank(), action + " would be drawn as an empty row");
            assertFalse(name.startsWith("键 "),
                    action + " ships on a key the name table does not know, so the settings page"
                            + " would show a number where a player expects a name");
        }
        assertEquals("", KeyBindings.keyName(GLFW.GLFW_KEY_UNKNOWN));
        assertEquals("F11", KeyBindings.keyName(GLFW.GLFW_KEY_F11));
        assertEquals("/", KeyBindings.keyName(GLFW.GLFW_KEY_SLASH));
    }
}
