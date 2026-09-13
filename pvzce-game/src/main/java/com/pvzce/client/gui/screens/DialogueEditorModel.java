package com.pvzce.client.gui.screens;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * The opening-dialogue half of a level's JSON, as the 对话 page edits it.
 *
 * <p>Split out of {@code EditorScreen} the way the wave and music models are: the page
 * is a list of lines plus a form for the selected one, and the model is what turns that
 * into the {@code dialogue} block. A line is written out with all five fields even when
 * they are empty, so the file says what the editor shows instead of leaving a reader to
 * guess whether a missing {@code side} means "left" or "forgotten".
 */
public final class DialogueEditorModel {
    /** One line of the conversation. Mutable: the detail form edits it in place. */
    public static final class LineModel {
        public String character = "";
        public String portrait = "";
        public String text = "";
        public String voice = "";
        public boolean left = true;

        /**
         * The one-line row the list shows.
         *
         * <p>The speaker is passed in rather than read here: a character's display name
         * lives in the registry, and this model deliberately knows nothing about
         * registries - it is also what the JSON round trip is tested against.
         */
        public String summary(int index, String speaker) {
            String who = speaker == null || speaker.isBlank() ? "（未选角色）" : speaker;
            String body = text == null || text.isBlank() ? "（无台词）" : text;
            return (index + 1) + ". " + (left ? "左" : "右") + "　" + who + "　" + body;
        }
    }

    public static final class Config {
        public final List<LineModel> lines = new ArrayList<>();

        public void replaceWith(Config other) {
            lines.clear();
            if (other != null) {
                lines.addAll(other.lines);
            }
        }

        /** Reads {@code dialogue.lines}; a level with no block simply has no lines. */
        public static Config fromJson(JsonObject root) {
            Config config = new Config();
            if (root == null || !root.has("dialogue") || !root.get("dialogue").isJsonObject()) {
                return config;
            }
            JsonObject dialogue = root.getAsJsonObject("dialogue");
            if (!dialogue.has("lines") || !dialogue.get("lines").isJsonArray()) {
                return config;
            }
            for (JsonElement element : dialogue.getAsJsonArray("lines")) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject line = element.getAsJsonObject();
                LineModel model = new LineModel();
                model.character = string(line, "character");
                model.portrait = string(line, "portrait");
                model.text = string(line, "text");
                model.voice = string(line, "voice");
                // Anything that is not 'right' is the left side, which is also what the
                // reader falls back to; the page shows the value so a typo is visible.
                model.left = !"right".equalsIgnoreCase(string(line, "side"));
                config.lines.add(model);
            }
            return config;
        }

        private static String string(JsonObject object, String key) {
            return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : "";
        }

        /** The {@code lines} array as written into the level file. */
        public JsonArray toJson() {
            JsonArray array = new JsonArray();
            for (LineModel line : lines) {
                JsonObject json = new JsonObject();
                json.addProperty("character", line.character);
                json.addProperty("portrait", line.portrait);
                json.addProperty("text", line.text);
                json.addProperty("voice", line.voice);
                json.addProperty("side", line.left ? "left" : "right");
                array.add(json);
            }
            return array;
        }
    }

    private DialogueEditorModel() {
    }
}
