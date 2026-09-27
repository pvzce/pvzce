package com.pvzce.client.gui.screens;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * The music timeline half of a level's JSON.
 *
 * <p>Split out of the config dialog when the timeline moved into the level editor's 音乐
 * page. {@link #STOP_LABEL} is the "no event / stop this track" choice the event picker
 * shows; it is a UI string because it is what the author reads, and it is only ever
 * stored as an empty {@code event} plus {@code stop}.
 */
public final class MusicEditorModel {
    public static final String STOP_LABEL = "（无 / 停止）";

    public static final class Config {
        public final List<CueModel> cues = new ArrayList<>();

        public static Config fromJson(JsonObject root) {
            Config config = new Config();
            JsonObject music = root.has("music") && root.get("music").isJsonObject()
                    ? root.getAsJsonObject("music") : null;
            if (music == null) {
                // No field => LevelDef's implicit default grasswalk.
                CueModel cue = new CueModel();
                cue.event = "pvzce:music/grasswalk";
                config.cues.add(cue);
                return config;
            }
            if (!music.has("cues") || !music.get("cues").isJsonArray()) {
                return config;
            }
            for (JsonElement element : music.getAsJsonArray("cues")) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject cueJson = element.getAsJsonObject();
                CueModel cue = new CueModel();
                cue.trigger = cueJson.has("trigger") ? cueJson.get("trigger").getAsString()
                        : "level_start";
                cue.atTick = cueJson.has("at_tick") ? cueJson.get("at_tick").getAsInt() : 0;
                cue.track = cueJson.has("track") ? cueJson.get("track").getAsString() : "background";
                if (cueJson.has("event") && !cueJson.get("event").isJsonNull()) {
                    cue.event = cueJson.get("event").getAsString();
                }
                cue.loop = !cueJson.has("loop") || cueJson.get("loop").getAsBoolean();
                cue.stop = cueJson.has("stop") && cueJson.get("stop").getAsBoolean();
                cue.volume = cueJson.has("volume") ? cueJson.get("volume").getAsFloat() : 1F;
                cue.fadeSeconds = cueJson.has("fade_seconds") ? cueJson.get("fade_seconds").getAsFloat() : 1F;
                config.cues.add(cue);
            }
            return config;
        }

        public JsonObject toJson() {
            JsonObject music = new JsonObject();
            JsonArray cuesJson = new JsonArray();
            for (CueModel cue : cues) {
                JsonObject cueJson = new JsonObject();
                // Written for every cue, not only for the non-default ones: a music page that
                // dropped a field it does not show would rewrite a rhythm level's song onto the
                // level's first tick the moment an author opened the page and pressed save.
                cueJson.addProperty("trigger", cue.trigger);
                cueJson.addProperty("at_tick", Math.max(0, cue.atTick));
                cueJson.addProperty("track", cue.track);
                if (cue.event != null && !cue.event.isEmpty()) {
                    cueJson.addProperty("event", cue.event);
                }
                cueJson.addProperty("loop", cue.loop);
                cueJson.addProperty("stop", cue.stop);
                cueJson.addProperty("volume", cue.volume);
                cueJson.addProperty("fade_seconds", cue.fadeSeconds);
                cuesJson.add(cueJson);
            }
            music.add("cues", cuesJson);
            return music;
        }

        public void replaceWith(Config other) {
            cues.clear();
            for (CueModel otherCue : other.cues) {
                CueModel copy = new CueModel();
                copy.trigger = otherCue.trigger;
                copy.atTick = otherCue.atTick;
                copy.track = otherCue.track;
                copy.event = otherCue.event;
                copy.loop = otherCue.loop;
                copy.stop = otherCue.stop;
                copy.volume = otherCue.volume;
                copy.fadeSeconds = otherCue.fadeSeconds;
                cues.add(copy);
            }
        }
    }

    public static final class CueModel {
        /**
         * What {@code atTick} counts from, as level data spells it: {@code level_start} or
         * {@code waves_start}.
         *
         * <p>A string rather than the enum, because this model is a JSON half and the enum lives in
         * {@code api.content.LevelDef}: the page round-trips the file's own word, so a value a
         * later version adds survives a save by an older editor.
         */
        public String trigger = "level_start";
        public int atTick;
        public String track = "background";
        public String event = "";
        public boolean loop = true;
        public boolean stop;
        public float volume = 1F;
        public float fadeSeconds = 1F;
    }
}
