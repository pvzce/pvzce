package com.pvzce.client.input;

import com.pvzce.client.config.PvzceClientConfig;
import com.pvzce.testutil.TestDirs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
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
        // The rhythm lanes: the six playable columns under the hands, left to right.
        assertEquals(GLFW.GLFW_KEY_S, keys.code(KeyBindings.Action.RHYTHM_COL_0));
        assertEquals(GLFW.GLFW_KEY_D, keys.code(KeyBindings.Action.RHYTHM_COL_1));
        assertEquals(GLFW.GLFW_KEY_F, keys.code(KeyBindings.Action.RHYTHM_COL_2));
        assertEquals(GLFW.GLFW_KEY_J, keys.code(KeyBindings.Action.RHYTHM_COL_3));
        assertEquals(GLFW.GLFW_KEY_K, keys.code(KeyBindings.Action.RHYTHM_COL_4));
        assertEquals(GLFW.GLFW_KEY_L, keys.code(KeyBindings.Action.RHYTHM_COL_5));
        // And everything nothing plays ships unbound: the row lanes (once D F G H J) and the
        // seventh to ninth columns (once U I O P). One key is one action, and the shipped charts
        // are six columns, so a hand-written chart that wants more visits the settings page.
        for (KeyBindings.Action idle : new KeyBindings.Action[] {KeyBindings.Action.RHYTHM_ROW_0,
                KeyBindings.Action.RHYTHM_ROW_1, KeyBindings.Action.RHYTHM_ROW_2,
                KeyBindings.Action.RHYTHM_ROW_3, KeyBindings.Action.RHYTHM_ROW_4,
                KeyBindings.Action.RHYTHM_COL_6, KeyBindings.Action.RHYTHM_COL_7,
                KeyBindings.Action.RHYTHM_COL_8}) {
            assertEquals(GLFW.GLFW_KEY_UNKNOWN, keys.code(idle),
                    idle.key() + " plays a lane no shipped chart has");
        }
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

    /**
     * A file written by the build whose lanes were {@code Q W E R Y} loads as the keys the game
     * ships now.
     *
     * <p>The report this answers was "I opened the game and it still shows Q W E R Y": the file was
     * not wrong, it was old - it named every action, and every action was at its old default. An
     * old default is not a choice, so the lanes move with the table; anything the player actually
     * picked is kept, including a lane rebound onto a key this build never shipped.
     */
    @Test
    void aFileWrittenWithTheOldLaneDefaultsLoadsTheShippedLanes() {
        Map<String, Integer> oldFile = new LinkedHashMap<>();
        oldFile.put("rhythm_col_0", GLFW.GLFW_KEY_Q);
        oldFile.put("rhythm_col_1", GLFW.GLFW_KEY_W);
        oldFile.put("rhythm_col_2", GLFW.GLFW_KEY_E);
        oldFile.put("rhythm_col_3", GLFW.GLFW_KEY_R);
        oldFile.put("rhythm_col_4", GLFW.GLFW_KEY_Y);
        oldFile.put("rhythm_col_5", GLFW.GLFW_KEY_U);
        oldFile.put("rhythm_row_0", GLFW.GLFW_KEY_D);
        oldFile.put("rhythm_row_4", GLFW.GLFW_KEY_J);
        oldFile.put("chat", GLFW.GLFW_KEY_F1);

        KeyBindings keys = KeyBindings.from(oldFile);
        assertEquals(GLFW.GLFW_KEY_S, keys.code(KeyBindings.Action.RHYTHM_COL_0));
        assertEquals(GLFW.GLFW_KEY_L, keys.code(KeyBindings.Action.RHYTHM_COL_5),
                "including the sixth, which that build called U");
        assertEquals(GLFW.GLFW_KEY_UNKNOWN, keys.code(KeyBindings.Action.RHYTHM_ROW_0),
                "and the row lanes come back unbound, because the columns took their letters");
        assertEquals(GLFW.GLFW_KEY_UNKNOWN, keys.code(KeyBindings.Action.RHYTHM_ROW_4));
        assertEquals(GLFW.GLFW_KEY_F1, keys.code(KeyBindings.Action.CHAT),
                "while a binding the player did choose is kept");

        // A lane the player moved somewhere of their own is not an old default either.
        assertEquals(GLFW.GLFW_KEY_Z,
                KeyBindings.from(Map.of("rhythm_col_0", GLFW.GLFW_KEY_Z))
                        .code(KeyBindings.Action.RHYTHM_COL_0));
    }

    /** And the file stops freezing the shipped table in the first place. */
    @Test
    void theConfigWritesOnlyTheBindingsThePlayerChose(@TempDir Path gameDir) throws Exception {
        PvzceClientConfig config = PvzceClientConfig.load(gameDir);
        config.save();
        String untouched = Files.readString(gameDir.resolve("config/pvzce-client.toml"));
        assertFalse(untouched.contains("rhythm_col_0"),
                "an untouched action is not written down: " + untouched);
        assertFalse(untouched.contains("chat ="), "including the ones the game ships with");

        config.keyBindings().bind(KeyBindings.Action.RHYTHM_COL_3, GLFW.GLFW_KEY_7);
        config.save();
        String chosen = Files.readString(gameDir.resolve("config/pvzce-client.toml"));
        assertTrue(chosen.contains("rhythm_col_3 = 55"), "a choice is: " + chosen);
        assertEquals(GLFW.GLFW_KEY_7,
                PvzceClientConfig.load(gameDir).keyBindings().code(KeyBindings.Action.RHYTHM_COL_3));
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
