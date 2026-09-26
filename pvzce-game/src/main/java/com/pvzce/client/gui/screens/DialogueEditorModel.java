package com.pvzce.client.gui.screens;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * The opening-dialogue half of a level's JSON, as the 对话 page edits it.
 *
 * <p>Split out of {@code EditorScreen} the way the wave and music models are: the page is a list of
 * lines plus a form for the selected one, and the model is what turns that into the {@code dialogue}
 * block. A line is written out with all five text fields even when they are empty, so the file says
 * what the editor shows instead of leaving a reader to guess whether a missing {@code side} means
 * "left" or "forgotten".
 *
 * <p>What the page has no widgets for is kept <em>verbatim</em> rather than dropped: {@code animation}
 * kinds it cannot draw, a {@code slots} stage, the {@code choices} a line offers. The first is held
 * as the raw block, the other two as their raw arrays, and the 同台与选项 dialog edits those arrays
 * through {@link LineModel#applyStage} and {@link LineModel#applyChoices}. A save may therefore only
 * remove something the page can put back. The block's own {@code enter}/{@code exit} are not on the
 * page either, so {@code LevelFileWriter.dialogue} merges the lines into the existing block and a
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
         * The name over the bubble for a speaker who is not a registered character.
         *
         * <p>The player, in practice: the line they answer with is written with this and no
         * {@code character}, and it takes the {@code ${user_name}} placeholder like a line's text.
         */
        public String speakerName = "";
        /**
         * Where the speaker stands: {@code left}, {@code center} or {@code right}.
         *
         * <p>A string rather than a boolean since the centre was added, and spelled the way the level
         * file spells it - so a value the reader does not recognise survives a save instead of being
         * silently normalised onto a side.
         */
        public String side = "left";
        /**
         * The line's own animation kind, as written: {@code none} / {@code shake} / {@code scale}.
         *
         * <p>Kept as a string rather than an enum for the same reason {@link #side} is: a value the
         * reader does not recognise is written back the way it came in, so opening the page cannot
         * quietly drop an animation this version does not draw yet.
         */
        public String animation = "none";
        /** {@code shake}'s amplitude or {@code scale}'s multiplier; null when the file had none. */
        public Float animationAmount;
        public Float animationScale;
        /**
         * The block exactly as the file wrote it, for a kind this page cannot edit.
         *
         * <p>A future {@code "type": "sparkle"} may carry fields this version has never heard of, and
         * the page is not entitled to drop them: the whole object is written back verbatim until the
         * author picks one of the kinds the page does draw.
         */
        public JsonObject rawAnimation;
        /**
         * The line's {@code slots} and {@code choices}, as the file wrote them.
         *
         * <p>Held as JSON rather than as content objects because the 同台与选项 dialog is the only
         * reader that turns them into content: keeping the author's own entries is what makes a save
         * that never opened that dialog a no-op for them.
         */
        public JsonArray rawSlots;
        public JsonArray rawChoices;

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
         * <p>A kind the page does not know cycles into the three it does, and the raw block it came
         * with is dropped at that point: the author has just chosen what this line does.
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
         * <p>The speaker is passed in rather than read here: a character's display name lives in the
         * registry, and this model deliberately knows nothing about registries - it is also what the
         * JSON round trip is tested against.
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

        /**
         * This line as the game reads it, for the 同台与选项 dialog.
         *
         * <p>Only the fields that dialog shows are filled in; the rest exist so the round trip
         * through {@code DialogueLine} does not have to invent a second shape. The caller passes the
         * result straight back to {@link #applyStage} or {@link #applyChoices}, so a name or a look
         * the page spells differently cannot leak into the file through this door.
         */
        public com.pvzce.api.content.DialogueLine toLine() {
            return new com.pvzce.api.content.DialogueLine(
                    com.pvzce.api.util.Identifier.tryParse(character),
                    portrait, text, voice,
                    com.pvzce.api.content.DialogueLine.Side.parse(side),
                    com.pvzce.api.content.DialogueAnimation.NONE,
                    slotsOf(rawSlots), choicesOf(rawChoices),
                    speakerName == null ? "" : speakerName);
        }

        /** The one stage entry per half of the window, as the 同台与选项 dialog writes it. */
        public void applyStage(List<com.pvzce.api.content.DialogueLine.DialogueSlotEntry> stage) {
            JsonArray array = new JsonArray();
            for (com.pvzce.api.content.DialogueLine.DialogueSlotEntry entry : stage) {
                JsonObject json = new JsonObject();
                json.addProperty("slot", entry.slot().id());
                json.addProperty("character", entry.character().toString());
                array.add(json);
            }
            rawSlots = array.isEmpty() ? null : array;
        }

        /** The answers the question offers, in the order they are shown. */
        public void applyChoices(List<String> texts) {
            JsonArray array = new JsonArray();
            for (String text : texts) {
                if (text == null || text.isBlank()) {
                    continue;
                }
                JsonObject json = new JsonObject();
                json.addProperty("text", text);
                array.add(json);
            }
            rawChoices = array.isEmpty() ? null : array;
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
                model.speakerName = string(line, "speaker_name");
                // Kept as written: the page spells the three the overlay draws, and the reader in
                // DialogueLine is what falls back for anything else, so a typo stays visible here
                // instead of being rewritten behind the author's back.
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
                model.rawSlots = array(line, "slots");
                model.rawChoices = array(line, "choices");
                config.lines.add(model);
            }
            return config;
        }

        /**
         * A line's {@code animation} block, or null when it has none.
         *
         * <p>Only the number the kind uses is written: a shake has an amplitude and no multiplier,
         * and writing both invites a reader to wonder which one {@code scale} means. A kind the page
         * does not know keeps every number it came with, because the page is not entitled to throw
         * away a field it cannot draw.
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
                if (line.speakerName != null && !line.speakerName.isBlank()) {
                    json.addProperty("speaker_name", line.speakerName);
                }
                JsonObject animation = animationJson(line);
                if (animation != null) {
                    json.add("animation", animation);
                }
                // Written from the model's own arrays: the 同台与选项 dialog edits them, and a line
                // whose dialog was never opened still holds whatever the file had, verbatim.
                if (line.rawSlots != null && !line.rawSlots.isEmpty()) {
                    json.add("slots", line.rawSlots.deepCopy());
                }
                if (line.rawChoices != null && !line.rawChoices.isEmpty()) {
                    json.add("choices", line.rawChoices.deepCopy());
                }
                array.add(json);
            }
            return array;
        }
    }

    // ------------------------------------------------------------------
    // The raw stage and answers, read as content
    // ------------------------------------------------------------------

    /**
     * The stage as a line holds it, parsed for the dialog that shows it.
     *
     * <p>Read leniently on purpose: a hand-written entry the parser cannot read is simply not shown,
     * and it survives the edit because the raw array is what gets written back.
     */
    private static List<com.pvzce.api.content.DialogueLine.DialogueSlotEntry> slotsOf(JsonArray rawSlots) {
        List<com.pvzce.api.content.DialogueLine.DialogueSlotEntry> entries = new ArrayList<>();
        if (rawSlots == null) {
            return entries;
        }
        for (JsonElement element : rawSlots) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject entry = element.getAsJsonObject();
            com.pvzce.api.util.Identifier id =
                    com.pvzce.api.util.Identifier.tryParse(string(entry, "character"));
            if (id == null) {
                continue;
            }
            entries.add(new com.pvzce.api.content.DialogueLine.DialogueSlotEntry(id,
                    com.pvzce.api.content.DialogueSlot.parse(string(entry, "slot"))));
        }
        return entries;
    }

    /** The answers a line offers, parsed for the dialog; an entry with no text is kept as blank. */
    private static List<com.pvzce.api.content.DialogueChoice> choicesOf(JsonArray rawChoices) {
        List<com.pvzce.api.content.DialogueChoice> choices = new ArrayList<>();
        if (rawChoices == null) {
            return choices;
        }
        for (JsonElement element : rawChoices) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject choice = element.getAsJsonObject();
            choices.add(new com.pvzce.api.content.DialogueChoice(string(choice, "text"),
                    string(choice, "voice")));
        }
        return choices;
    }

    private static String string(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : "";
    }

    /** An array the file wrote, kept verbatim; null when it wrote none. */
    private static JsonArray array(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonArray()
                ? object.getAsJsonArray(key).deepCopy() : null;
    }

    /** A number the file wrote, or null when it did not: "unwritten" and "0" differ here. */
    private static Float number(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonPrimitive()
                ? object.get(key).getAsFloat() : null;
    }

    /** Static helpers only: the model is read from and written to the level's JSON. */
    private DialogueEditorModel() {
    }
}
