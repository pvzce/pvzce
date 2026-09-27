package com.pvzce.client.input;

import org.lwjgl.glfw.GLFW;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What a key does, and which key does it.
 *
 * <p>Every binding in the game used to be a literal {@code GLFW_KEY_*} comparison inside
 * {@code PvzceClient.pollInput}, which meant a player could not change one and a new one had to be
 * threaded through that method by hand. This is the one table: the poll loop asks it "what is this
 * key", the settings page reads and writes it, and the config file is written from it.
 *
 * <p><b>A key belongs to one action.</b> {@link #bind} takes it away from whoever had it, because
 * two actions on one key is not a setting a player can express - the later one would simply never
 * fire. The displaced action is left unbound rather than moved somewhere else: the page that did
 * the binding shows exactly that, and "unbound" is a state the row already draws.
 *
 * <p>Codes are GLFW's, stored as numbers rather than as names: the name a player reads
 * ({@code "F11"}) is for the settings page, and GLFW's own name lookup needs a scancode rather than
 * a keycode, so the names below are a table this class owns. A key it does not know is drawn as
 * {@code "键 305"} rather than as nothing - an unnameable key is still a working binding.
 */
public final class KeyBindings {
    /**
     * Every action a key can be bound to.
     *
     * <p>The declaration order is the order the settings page lists them, and it groups them the
     * way a player thinks about them: the screens, then the panels, then the tools.
     */
    public enum Action {
        /** Windowed / fullscreen. */
        FULLSCREEN("fullscreen", GLFW.GLFW_KEY_F11),
        /** The F3 diagnostic overlay. */
        DEBUG_OVERLAY("debug", GLFW.GLFW_KEY_F3),
        /** Save a PNG of the current frame. */
        SCREENSHOT("screenshot", GLFW.GLFW_KEY_F2),
        /** Health bars over everything that is not at full health. */
        HEALTH_BARS("health", GLFW.GLFW_KEY_F10),
        /** The chat line. */
        CHAT("chat", GLFW.GLFW_KEY_T),
        /** The command line. */
        COMMAND("command", GLFW.GLFW_KEY_SLASH),
        /**
         * The five tools, by identity rather than by card position.
         *
         * <p>"1 is the shovel" and not "1 is the first card": a bar's order is the level's, and a
         * key that meant a different tool in every level would be worse than no key at all. The
         * vase has no default because four digits were asked for and there are five tools; it is
         * reachable from the bar like any other card, and a player who wants a key can set one.
         */
        TOOL_SHOVEL("tool_shovel", GLFW.GLFW_KEY_1),
        TOOL_GLOVE("tool_glove", GLFW.GLFW_KEY_2),
        TOOL_HAMMER("tool_hammer", GLFW.GLFW_KEY_3),
        TOOL_WATERING_CAN("tool_watering_can", GLFW.GLFW_KEY_4),
        /** The vase tool; unbound by default, and the row a player is most likely to fill in. */
        TOOL_VASE("tool_vase", GLFW.GLFW_KEY_UNKNOWN),

        /**
         * The rhythm levels' lanes: six columns on {@code S D F J K L}, and everything else on
         * nothing at all.
         *
         * <p><b>{@code S D F J K L}</b> - the three fingers of each hand on the home row - because
         * the mode is played with the hands at the bottom of the board: a note flies down its own
         * column to the judgement line, and the keys are drawn under it. The shipped tiers use the
         * first six columns of the lawn; the seventh to ninth are ordinary lawn (plantable, edible,
         * no key).
         *
         * <p><b>The lanes nothing plays ship unbound, and that is the point.</b> The row lanes used
         * to be {@code D F G H J}, which is where the column keys live now, and the last three
         * columns used to be {@code U I O P}, which is not a run anybody's hands know. One key
         * belongs to one action (see {@link #bind}), so a table with both would have shipped keys
         * that fire a lane the level does not have. A hand-written chart that wants rows, or more
         * than six columns, binds them on the settings page - which draws an unbound action as
         * "未绑定" rather than hiding it.
         */
        RHYTHM_ROW_0("rhythm_row_0", GLFW.GLFW_KEY_UNKNOWN),
        RHYTHM_ROW_1("rhythm_row_1", GLFW.GLFW_KEY_UNKNOWN),
        RHYTHM_ROW_2("rhythm_row_2", GLFW.GLFW_KEY_UNKNOWN),
        RHYTHM_ROW_3("rhythm_row_3", GLFW.GLFW_KEY_UNKNOWN),
        RHYTHM_ROW_4("rhythm_row_4", GLFW.GLFW_KEY_UNKNOWN),
        RHYTHM_COL_0("rhythm_col_0", GLFW.GLFW_KEY_S),
        RHYTHM_COL_1("rhythm_col_1", GLFW.GLFW_KEY_D),
        RHYTHM_COL_2("rhythm_col_2", GLFW.GLFW_KEY_F),
        RHYTHM_COL_3("rhythm_col_3", GLFW.GLFW_KEY_J),
        RHYTHM_COL_4("rhythm_col_4", GLFW.GLFW_KEY_K),
        RHYTHM_COL_5("rhythm_col_5", GLFW.GLFW_KEY_L),
        RHYTHM_COL_6("rhythm_col_6", GLFW.GLFW_KEY_UNKNOWN),
        RHYTHM_COL_7("rhythm_col_7", GLFW.GLFW_KEY_UNKNOWN),
        RHYTHM_COL_8("rhythm_col_8", GLFW.GLFW_KEY_UNKNOWN);

        private final String key;
        private final int defaultCode;

        Action(String key, int defaultCode) {
            this.key = key;
            this.defaultCode = defaultCode;
        }

        /** The name the config file and the language file use. */
        public String key() {
            return key;
        }

        /** The key this action ships with, or {@link GLFW#GLFW_KEY_UNKNOWN} for none. */
        public int defaultCode() {
            return defaultCode;
        }

        /** True for the five tool actions; what the in-game hotkey path switches on. */
        public boolean isTool() {
            return this == TOOL_SHOVEL || this == TOOL_GLOVE || this == TOOL_HAMMER
                    || this == TOOL_WATERING_CAN || this == TOOL_VASE;
        }

        /**
         * The lane this action plays, or {@code null} for every key that is not a rhythm lane.
         *
         * <p>Answered here rather than parsed off the config key's name by the screen: the name is
         * a config file's business and renaming one would silently unbind a lane.
         */
        public String rhythmLaneKind() {
            String name = name();
            if (name.startsWith("RHYTHM_ROW_")) {
                return "row";
            }
            return name.startsWith("RHYTHM_COL_") ? "col" : null;
        }

        /** The row or column this action plays, or -1 for every key that is not a rhythm lane. */
        public int rhythmLaneIndex() {
            String name = name();
            int at = name.lastIndexOf('_');
            if (!name.startsWith("RHYTHM_")) {
                return -1;
            }
            try {
                return Integer.parseInt(name.substring(at + 1));
            } catch (NumberFormatException e) {
                return -1;
            }
        }

        /** True for the fourteen lane keys. */
        public boolean isRhythmLane() {
            return rhythmLaneKind() != null;
        }

        /**
         * The action that plays one lane, or {@code null} when no key is assigned to it.
         *
         * <p>The inverse of {@link #rhythmLaneKind()} and {@link #rhythmLaneIndex()}, and it exists
         * for the same reason they do: the on-lawn key hints have a lane in hand (the chart's, which
         * is data) and need the letter to draw on it, and spelling the mapping out a second time
         * where the hints are drawn is how the two would come apart.
         */
        public static Action forLane(String kind, int index) {
            for (Action action : values()) {
                if (index == action.rhythmLaneIndex() && kind != null
                        && kind.equals(action.rhythmLaneKind())) {
                    return action;
                }
            }
            return null;
        }

        /** The tool id this action fires, or {@code null} for everything else. */
        public String toolId() {
            return switch (this) {
                case TOOL_SHOVEL -> "pvzce:shovel";
                case TOOL_GLOVE -> "pvzce:glove";
                case TOOL_HAMMER -> "pvzce:hammer";
                case TOOL_WATERING_CAN -> "pvzce:watering_can";
                case TOOL_VASE -> "pvzce:vase";
                default -> null;
            };
        }
    }

    /** One action to one GLFW key code; {@link GLFW#GLFW_KEY_UNKNOWN} means unbound. */
    private final Map<Action, Integer> keys = new EnumMap<>(Action.class);

    private KeyBindings() {
    }

    /** The shipped table. */
    public static KeyBindings defaults() {
        KeyBindings bindings = new KeyBindings();
        bindings.resetToDefaults();
        return bindings;
    }

    /**
     * The defaults an action has shipped with and no longer has, by action.
     *
     * <p>A config file records choices by action name, and a file written by an older build names
     * <em>every</em> action - the untouched ones included, at the value that was the default then.
     * Loading such a file verbatim is how the rhythm lanes kept answering to {@code Q W E R Y} after
     * the keys had moved to {@code S D F J K L}: the file was not wrong, it was old. So a stored
     * value equal to an old default of that action is read as "the player never chose this" and the
     * action gets what it ships with now (see {@code 踩坑清单} 145).
     *
     * <p>Only the actions whose defaults have actually moved are listed. Anything else in a file is
     * a choice and is kept - including a deliberate rebinding onto one of these old values, which is
     * the one case an upgrade cannot be told apart from a decision. That ambiguity is also why the
     * config stopped writing defaults at all (see {@code PvzceClientConfig.keyBinds}): a file
     * written from now on records choices, so this list only ever has to describe the past.
     */
    private static final Map<Action, int[]> SUPERSEDED_DEFAULTS = supersededDefaults();

    private static Map<Action, int[]> supersededDefaults() {
        Map<Action, int[]> old = new EnumMap<>(Action.class);
        // The rhythm lanes, in the order the mode grew: nine columns on Q W E R Y U I O P, then
        // five of them on A S D J K (with U I O P left for a nine-lane chart), then the six columns
        // the shipped levels are played on today.
        old.put(Action.RHYTHM_COL_0, new int[] {GLFW.GLFW_KEY_Q, GLFW.GLFW_KEY_A});
        old.put(Action.RHYTHM_COL_1, new int[] {GLFW.GLFW_KEY_W, GLFW.GLFW_KEY_S});
        old.put(Action.RHYTHM_COL_2, new int[] {GLFW.GLFW_KEY_E, GLFW.GLFW_KEY_D});
        old.put(Action.RHYTHM_COL_3, new int[] {GLFW.GLFW_KEY_R, GLFW.GLFW_KEY_J});
        old.put(Action.RHYTHM_COL_4, new int[] {GLFW.GLFW_KEY_Y, GLFW.GLFW_KEY_K});
        old.put(Action.RHYTHM_COL_5, new int[] {GLFW.GLFW_KEY_U});
        old.put(Action.RHYTHM_COL_6, new int[] {GLFW.GLFW_KEY_I});
        old.put(Action.RHYTHM_COL_7, new int[] {GLFW.GLFW_KEY_O});
        old.put(Action.RHYTHM_COL_8, new int[] {GLFW.GLFW_KEY_P});
        // The row lanes were D F G H J until the column keys took those letters, and they ship
        // unbound now: no shipped chart plays a row.
        old.put(Action.RHYTHM_ROW_0, new int[] {GLFW.GLFW_KEY_D});
        old.put(Action.RHYTHM_ROW_1, new int[] {GLFW.GLFW_KEY_F});
        old.put(Action.RHYTHM_ROW_2, new int[] {GLFW.GLFW_KEY_G});
        old.put(Action.RHYTHM_ROW_3, new int[] {GLFW.GLFW_KEY_H});
        old.put(Action.RHYTHM_ROW_4, new int[] {GLFW.GLFW_KEY_J});
        return Map.copyOf(old);
    }

    /**
     * One stored code as the table should hold it: an old default is not a choice.
     *
     * <p>The whole of the upgrade rule, in one place, so "why did my file's Q become S" has one
     * answer to read.
     */
    private static int asShipped(Action action, int stored) {
        int[] superseded = SUPERSEDED_DEFAULTS.get(action);
        if (superseded != null) {
            for (int candidate : superseded) {
                if (candidate == stored) {
                    return action.defaultCode();
                }
            }
        }
        return stored;
    }

    /**
     * The table a config file describes, falling back per action.
     *
     * <p>Per action rather than wholesale: a file written by an older build has no entry for an
     * action added since, and one unreadable line must not throw away the player's other choices.
     *
     * @param stored the config's own map, by action name
     */
    public static KeyBindings from(Map<String, Integer> stored) {
        KeyBindings bindings = defaults();
        if (stored == null) {
            return bindings;
        }
        for (Map.Entry<String, Integer> entry : stored.entrySet()) {
            Action action = byName(entry.getKey());
            Integer code = entry.getValue();
            if (action != null && code != null) {
                // Set directly rather than through `bind`: a file is a description of the whole
                // table, and clearing another action's key while reading it would make the result
                // depend on the file's line order. The value still goes through `asShipped`, which
                // is the one thing an old file cannot say for itself.
                bindings.keys.put(action, asShipped(action, code));
            }
        }
        return bindings;
    }

    /** The action with this config name, or {@code null}. */
    public static Action byName(String name) {
        if (name == null) {
            return null;
        }
        for (Action action : Action.values()) {
            if (action.key.equals(name)) {
                return action;
            }
        }
        return null;
    }

    /** The key bound to this action, or {@link GLFW#GLFW_KEY_UNKNOWN}. */
    public int code(Action action) {
        Integer code = keys.get(action);
        return code == null ? GLFW.GLFW_KEY_UNKNOWN : code;
    }

    /** True when this action still has the key it ships with. */
    public boolean isDefault(Action action) {
        return code(action) == action.defaultCode;
    }

    /** True when any action at all has been changed; for the settings page's "changed" mark. */
    public boolean isModified() {
        for (Action action : Action.values()) {
            if (!isDefault(action)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Binds a key to an action, taking it away from whoever had it.
     *
     * @return the action that lost the key, or {@code null} when nobody did
     */
    public Action bind(Action action, int code) {
        if (action == null) {
            return null;
        }
        Action displaced = null;
        if (code != GLFW.GLFW_KEY_UNKNOWN) {
            for (Action other : Action.values()) {
                if (other != action && code(other) == code) {
                    keys.put(other, GLFW.GLFW_KEY_UNKNOWN);
                    displaced = other;
                }
            }
        }
        keys.put(action, code);
        return displaced;
    }

    /** Unbinds one action. */
    public void unbind(Action action) {
        keys.put(action, GLFW.GLFW_KEY_UNKNOWN);
    }

    /** Puts every action back to the key it ships with. */
    public void resetToDefaults() {
        keys.clear();
        for (Action action : Action.values()) {
            keys.put(action, action.defaultCode());
        }
    }

    /** The action this key fires, or {@code null} when nothing is bound to it. */
    public Action actionFor(int code) {
        if (code == GLFW.GLFW_KEY_UNKNOWN) {
            return null;
        }
        for (Action action : Action.values()) {
            if (code(action) == code) {
                return action;
            }
        }
        return null;
    }

    /** The table as the config file stores it: one entry per action, unbound ones included. */
    public Map<String, Integer> asMap() {
        Map<String, Integer> stored = new LinkedHashMap<>();
        for (Action action : Action.values()) {
            stored.put(action.key, code(action));
        }
        return stored;
    }

    /** The action as the settings page and the tooltip name it. */
    public static String describe(Action action) {
        return "pvzce.key." + action.key;
    }

    /**
     * A key code as a player reads it.
     *
     * <p>A table rather than {@code glfwGetKeyName}: that call wants a scancode as well as a
     * keycode, and the scancode is only known while the key is being pressed - a settings row has
     * to draw a name for a key nobody is touching.
     */
    public static String keyName(int code) {
        if (code == GLFW.GLFW_KEY_UNKNOWN) {
            return "";
        }
        if (code >= GLFW.GLFW_KEY_F1 && code <= GLFW.GLFW_KEY_F25) {
            return "F" + (code - GLFW.GLFW_KEY_F1 + 1);
        }
        if (code >= GLFW.GLFW_KEY_0 && code <= GLFW.GLFW_KEY_9) {
            return String.valueOf((char) ('0' + code - GLFW.GLFW_KEY_0));
        }
        if (code >= GLFW.GLFW_KEY_A && code <= GLFW.GLFW_KEY_Z) {
            return String.valueOf((char) ('A' + code - GLFW.GLFW_KEY_A));
        }
        if (code >= GLFW.GLFW_KEY_KP_0 && code <= GLFW.GLFW_KEY_KP_9) {
            return "小键盘 " + (code - GLFW.GLFW_KEY_KP_0);
        }
        return switch (code) {
            case GLFW.GLFW_KEY_SPACE -> "空格";
            case GLFW.GLFW_KEY_APOSTROPHE -> "'";
            case GLFW.GLFW_KEY_COMMA -> ",";
            case GLFW.GLFW_KEY_MINUS -> "-";
            case GLFW.GLFW_KEY_PERIOD -> ".";
            case GLFW.GLFW_KEY_SLASH -> "/";
            case GLFW.GLFW_KEY_SEMICOLON -> ";";
            case GLFW.GLFW_KEY_EQUAL -> "=";
            case GLFW.GLFW_KEY_LEFT_BRACKET -> "[";
            case GLFW.GLFW_KEY_BACKSLASH -> "\\";
            case GLFW.GLFW_KEY_RIGHT_BRACKET -> "]";
            case GLFW.GLFW_KEY_GRAVE_ACCENT -> "`";
            case GLFW.GLFW_KEY_ESCAPE -> "Esc";
            case GLFW.GLFW_KEY_ENTER -> "回车";
            case GLFW.GLFW_KEY_TAB -> "Tab";
            case GLFW.GLFW_KEY_BACKSPACE -> "退格";
            case GLFW.GLFW_KEY_INSERT -> "Insert";
            case GLFW.GLFW_KEY_DELETE -> "Delete";
            case GLFW.GLFW_KEY_RIGHT -> "→";
            case GLFW.GLFW_KEY_LEFT -> "←";
            case GLFW.GLFW_KEY_DOWN -> "↓";
            case GLFW.GLFW_KEY_UP -> "↑";
            case GLFW.GLFW_KEY_PAGE_UP -> "PageUp";
            case GLFW.GLFW_KEY_PAGE_DOWN -> "PageDown";
            case GLFW.GLFW_KEY_HOME -> "Home";
            case GLFW.GLFW_KEY_END -> "End";
            case GLFW.GLFW_KEY_CAPS_LOCK -> "CapsLock";
            case GLFW.GLFW_KEY_SCROLL_LOCK -> "ScrollLock";
            case GLFW.GLFW_KEY_NUM_LOCK -> "NumLock";
            case GLFW.GLFW_KEY_PRINT_SCREEN -> "PrintScreen";
            case GLFW.GLFW_KEY_PAUSE -> "Pause";
            case GLFW.GLFW_KEY_LEFT_SHIFT -> "左Shift";
            case GLFW.GLFW_KEY_LEFT_CONTROL -> "左Ctrl";
            case GLFW.GLFW_KEY_LEFT_ALT -> "左Alt";
            case GLFW.GLFW_KEY_LEFT_SUPER -> "左Super";
            case GLFW.GLFW_KEY_RIGHT_SHIFT -> "右Shift";
            case GLFW.GLFW_KEY_RIGHT_CONTROL -> "右Ctrl";
            case GLFW.GLFW_KEY_RIGHT_ALT -> "右Alt";
            case GLFW.GLFW_KEY_RIGHT_SUPER -> "右Super";
            // A key this table has no name for is still a working binding, so it is drawn as its
            // number rather than as nothing at all.
            default -> "键 " + code;
        };
    }

}
