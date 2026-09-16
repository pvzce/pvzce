package com.pvzce.client.gui.screens;

import com.pvzce.client.gui.GuiLang;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * The wave half of a level's JSON: the schedule and its serialization.
 *
 * <p>Split out of the full-screen wave editor when that editor moved into the level
 * editor's 波次 page. What is left is the data and its JSON shape, which is what both the
 * page and the tests actually use; the widgets that used to drive it live in
 * {@code EditorScreen} now.
 */
public final class WaveEditorModel {
    /** Serialized wave section of a level JSON; the editor owns this shape. */
    public static final class Config {
        public float intervalEndMultiplier = 1F;
        public final List<WaveModel> waves = new ArrayList<>();

        /** Parses the wave-related fields of a level JSON root. */
        public static Config fromJson(JsonObject root) {
            Config config = new Config();
            if (root.has("wave_interval_end_multiplier")) {
                config.intervalEndMultiplier = root.get("wave_interval_end_multiplier").getAsFloat();
            }
            if (!root.has("waves")) {
                return config;
            }
            for (JsonElement element : root.getAsJsonArray("waves")) {
                JsonObject waveJson = element.getAsJsonObject();
                WaveModel wave = new WaveModel();
                wave.type = waveJson.has("type") ? waveJson.get("type").getAsString() : "small";
                wave.delay = waveJson.has("delay") ? waveJson.get("delay").getAsInt() : 600;
                // Absent means "engine default", which stays absent on save: an editor
                // round trip must not freeze today's default into every level it touches.
                wave.spawnInterval = waveJson.has("spawn_interval")
                        ? Math.max(0, waveJson.get("spawn_interval").getAsInt()) : 0;
                wave.warningTicks = waveJson.has("warning_ticks")
                        ? waveJson.get("warning_ticks").getAsInt() : 600;
                // Same rule as spawn_interval, and for the same reason: the editor has no
                // field for the opening death gate, so it must carry one it did not write
                // through untouched instead of dropping it on the next save.
                wave.holdUntilDeadTicks = waveJson.has("hold_until_dead")
                        ? waveJson.get("hold_until_dead").getAsInt() : null;
                if (waveJson.has("entries")) {
                    for (JsonElement entryElement : waveJson.getAsJsonArray("entries")) {
                        JsonObject entryJson = entryElement.getAsJsonObject();
                        if (!entryJson.has("id")) {
                            continue;
                        }
                        wave.entries.add(new EntryModel(
                                entryJson.get("id").getAsString(),
                                entryJson.has("count") ? Math.max(1, entryJson.get("count").getAsInt()) : 1));
                    }
                }
                config.waves.add(wave);
            }
            return config;
        }

        /** Serializes this config as the {@code waves} / multiplier part of a level JSON. */
        public JsonObject toJson() {
            JsonObject root = new JsonObject();
            root.addProperty("wave_interval_end_multiplier", intervalEndMultiplier);
            JsonArray wavesJson = new JsonArray();
            for (WaveModel wave : waves) {
                JsonObject waveJson = new JsonObject();
                waveJson.addProperty("type", wave.type);
                waveJson.addProperty("delay", wave.delay);
                waveJson.addProperty("warning_ticks", wave.warningTicks);
                if (wave.spawnInterval > 0) {
                    waveJson.addProperty("spawn_interval", wave.spawnInterval);
                }
                if (wave.holdUntilDeadTicks != null) {
                    waveJson.addProperty("hold_until_dead", wave.holdUntilDeadTicks);
                }
                JsonArray entries = new JsonArray();
                for (EntryModel entry : wave.entries) {
                    JsonObject entryJson = new JsonObject();
                    entryJson.addProperty("id", entry.id);
                    entryJson.addProperty("count", entry.count);
                    entries.add(entryJson);
                }
                waveJson.add("entries", entries);
                wavesJson.add(waveJson);
            }
            root.add("waves", wavesJson);
            return root;
        }

        public int totalZombies(int waveIndex) {
            if (waveIndex < 0 || waveIndex >= waves.size()) {
                return 0;
            }
            int total = 0;
            for (EntryModel entry : waves.get(waveIndex).entries) {
                total += entry.count;
            }
            return total;
        }

        public void replaceWith(Config other) {
            intervalEndMultiplier = other.intervalEndMultiplier;
            waves.clear();
            waves.addAll(other.waves);
        }
    }

    public static final class WaveModel {
        public String type = "small";
        public int delay = 600;
        public int warningTicks = 600;
        /**
         * Ticks between this wave's zombies, or 0 to leave the field out.
         *
         * <p>Zero means "the engine's default" rather than "instant": leaving it out keeps
         * the file readable and lets the default move later.
         */
        public int spawnInterval;
        /**
         * The opening death gate this wave wrote, or null to leave the field out.
         *
         * <p>Not editable from the wave table yet, so it is only ever carried: an author who
         * wrote {@code hold_until_dead} by hand must not lose it by opening the editor.
         */
        public Integer holdUntilDeadTicks;
        public final List<EntryModel> entries = new ArrayList<>();

        /** Short "2× 普通僵尸, 1× 铁桶僵尸" style summary for the table row. */
        public String summary() {
            if (entries.isEmpty()) {
                return "(空)";
            }
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < entries.size(); i++) {
                if (i > 0) {
                    text.append(", ");
                }
                EntryModel entry = entries.get(i);
                text.append(entry.count).append("× ").append(GuiLang.name(entry.id));
            }
            return text.toString();
        }

        public int total() {
            int total = 0;
            for (EntryModel entry : entries) {
                total += entry.count;
            }
            return total;
        }
    }

    public static final class EntryModel {
        public String id;
        public int count;

        public EntryModel(String id, int count) {
            this.id = id;
            this.count = count;
        }
    }

    /** Column x offsets within the table, as fractions of the table width. */
    /** Wave type label, shared with the editor's inline wave table. */
    public static String typeName(String type) {
        return switch (type == null ? "" : type) {
            case "huge" -> "大波";
            case "final" -> "终波";
            default -> "小波";
        };
    }

    /** Wave type colour, shared with the editor's inline wave table. */
    public static float[] typeColour(String type) {
        return switch (type == null ? "" : type) {
            case "huge" -> new float[]{1F, 0.78F, 0.35F};
            case "final" -> new float[]{1F, 0.45F, 0.45F};
            default -> new float[]{0.85F, 0.9F, 0.9F};
        };
    }
}
