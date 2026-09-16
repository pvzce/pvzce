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
 * into the {@code dialogue} block. A line is written out with all five text fields even when
 * they are empty, so the file says what the editor shows instead of leaving a reader to
 * guess whether a missing {@code side} means "left" or "forgotten".
 *
 * <p>The one exception is {@code animation}, which is written <em>only</em> when a line has
 * one: "no animation" is the absence of the field, and writing {@code {"type":"none"}} on
 * every line of every conversation would be noise an author has to read past. The block's own
 * {@code enter}/{@code exit} are not on the page at all - the defaults are what a conversation
 * wants - so {@code LevelFileWriter.dialogue} merges the lines into the existing block and a
 * hand-written pair of effects survives an edit.
 */
public final class DialogueEditorModel {
    /** One line of the conversation. Mutable: the detail form edits it in place. */
    public static final class LineModel {
        public String character = "";
        public String portrait = "";
        public String text = "";
        public String voice = "";
        /**
         * Where the speaker stands: {@code left}, {@code center} or {@code right}.
         *
         * <p>A string rather than a boolean since the centre was added, and spelled the way
         * the level file spells it - so a value the reader does not recognise survives a
         * save instead of being silently normalised onto a side.
         */
        public String side = "left";
        /**
         * The line's own animation kind, as written: {@code none} / {@code shake} / {@code scale}.
         *
         * <p>Kept as a string rather than an enum for the same reason {@link #side} is: a value
         * the reader does not recognise is written back the way it came in, so opening the page
         * cannot quietly drop an animation this version does not draw yet.
         */
        public String animation = "none";
        /** {@code shake}'s amplitude or {@code scale}'s multiplier; null when the file had none. */
        public Float animationAmount;
        public Float animationScale;
        /**
         * The block exactly as the file wrote it, for a kind this page cannot edit.
         *
         * <p>A future {@code "type": "sparkle"} may carry fields this version has never heard
         * of, and the page is not entitled to drop them: the whole object is written back
         * verbatim until the author picks one of the kinds the page does draw.
         */
        public JsonObject rawAnimation;

        /** The value the box edits for this line's kind: the multiplier, or the amplitude. */
        public String animationValueText() {
            Float value = "scale".equalsIgnoreCase(animation) ? animationScale : animationAmount;
            if (value == null) {
                value = "scale".equalsIgnoreCase(animation)
                        ? com.pvzce.api.content.DialogueAnimation.DEFAULT_SCALE
                        : com.pvzce.api.content.DialogueAnimation.DEFAULT_AMOUNT;
            }
            return com.pvzce.client.gui.GuiText.formatFloat(value);
        }

        /** Writes the box's text back onto whichever number this line's kind uses. */
        public void setAnimationValue(float value) {
            if ("scale".equalsIgnoreCase(animation)) {
                animationScale = value;
            } else {
                animationAmount = value;
            }
        }

        /**
         * Cycles 无 → 抖动 → 缩放, the order the page's button reads in.
         *
         * <p>A kind the page does not know cycles into the three it does, and the raw block it
         * came with is dropped at that point: the author has just chosen what this line does.
         */
        public void cycleAnimation() {
            animation = switch (animation == null ? "none" : animation.toLowerCase(java.util.Locale.ROOT)) {
                case "shake" -> "scale";
                case "scale" -> "none";
                default -> "shake";
            };
            rawAnimation = null;
        }

        public String animationLabel() {
            return switch (animation == null ? "none" : animation.toLowerCase(java.util.Locale.ROOT)) {
                case "shake" -> "抖动";
                case "scale" -> "缩放";
                default -> "无";
            };
        }

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
            String extra = "none".equalsIgnoreCase(animation) ? "" : "　[" + animationLabel() + "]";
            return (index + 1) + ". " + sideLabel() + "　" + who + "　" + body + extra;
        }

        /** The side as the page's own button spells it. */
        public String sideLabel() {
            return switch (normalizedSide()) {
                case "center" -> "中";
                case "right" -> "右";
                default -> "左";
            };
        }

        /** Cycles left → center → right, the order they read in across the screen. */
        public void cycleSide() {
            side = switch (normalizedSide()) {
                case "left" -> "center";
                case "center" -> "right";
                default -> "left";
            };
        }

        /** The stored value folded onto the three the overlay draws. */
        private String normalizedSide() {
            return switch (side == null ? "" : side.toLowerCase(java.util.Locale.ROOT)) {
                case "center", "centre", "middle" -> "center";
                case "right" -> "right";
                default -> "left";
            };
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
                // Kept as written: the page spells the three the overlay draws, and the
                // reader in DialogueLine is what falls back for anything else, so a typo
                // stays visible here instead of being rewritten behind the author's back.
                String side = string(line, "side");
                model.side = side.isBlank() ? "left" : side;
                if (line.has("animation") && line.get("animation").isJsonObject()) {
                    JsonObject animation = line.getAsJsonObject("animation");
                    model.animation = string(animation, "type");
                    if (model.animation.isBlank()) {
                        model.animation = "none";
                    }
                    model.animationAmount = number(animation, "amount");
                    model.animationScale = number(animation, "scale");
                    model.rawAnimation = animation.deepCopy();
                }
                config.lines.add(model);
            }
            return config;
        }

        private static String string(JsonObject object, String key) {
            return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : "";
        }

        /** A number the file wrote, or null when it did not: "unwritten" and "0" differ here. */
        private static Float number(JsonObject object, String key) {
            return object.has(key) && object.get(key).isJsonPrimitive()
                    ? object.get(key).getAsFloat() : null;
        }

        /**
         * A line's {@code animation} block, or null when it has none.
         *
         * <p>Only the number the kind uses is written: a shake has an amplitude and no
         * multiplier, and writing both invites a reader to wonder which one {@code scale}
         * means. A kind the page does not know keeps every number it came with, because the
         * page is not entitled to throw away a field it cannot draw.
         */
        private static JsonObject animationJson(LineModel line) {
            String kind = line.animation == null ? "none" : line.animation;
            if ("none".equalsIgnoreCase(kind)) {
                return null;
            }
            JsonObject animation = new JsonObject();
            animation.addProperty("type", kind);
            boolean known = "shake".equalsIgnoreCase(kind) || "scale".equalsIgnoreCase(kind);
            if (!known) {
                // Verbatim: the page draws none of it, so it may not rewrite any of it.
                return line.rawAnimation == null ? animation : line.rawAnimation.deepCopy();
            }
            if ("scale".equalsIgnoreCase(kind)) {
                animation.addProperty("scale", line.animationScale == null
                        ? com.pvzce.api.content.DialogueAnimation.DEFAULT_SCALE : line.animationScale);
                return animation;
            }
            animation.addProperty("amount", line.animationAmount == null
                    ? com.pvzce.api.content.DialogueAnimation.DEFAULT_AMOUNT : line.animationAmount);
            return animation;
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
                json.addProperty("side", line.side == null || line.side.isBlank() ? "left" : line.side);
                JsonObject animation = animationJson(line);
                if (animation != null) {
                    json.add("animation", animation);
                }
                array.add(json);
            }
            return array;
        }
    }

    private DialogueEditorModel() {
    }
}
