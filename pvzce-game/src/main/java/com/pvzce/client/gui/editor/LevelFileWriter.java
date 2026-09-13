package com.pvzce.client.gui.editor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.pvzce.api.content.LevelRewards;
import com.pvzce.api.util.Identifier;
import com.pvzce.client.gui.screens.CardPoolEditorDialog;
import com.pvzce.client.gui.screens.DialogueEditorModel;
import com.pvzce.client.gui.screens.MusicEditorModel;
import com.pvzce.client.gui.screens.WaveEditorModel;
import com.pvzce.common.core.JsonDraft;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Writes the level file, one block at a time.
 *
 * <p>Static and screen-free so the merge rules can be tested without a client: this is the
 * code most likely to silently destroy someone's level file, and it used to be a single
 * seventeen-parameter method with five overloads, which is exactly the shape that makes the
 * "oops, the rewards were not written" bug invisible.
 *
 * <p>Each method owns one block of the file and nothing else. That is the whole rule: a page
 * calls its own method, and a field no page models is never touched because no method names
 * it.
 */
public final class LevelFileWriter {

    /** Size, tiles and the entities the author placed. */
    public static void canvas(JsonDraft draft, int width, int height, Map<String, List<String>> scene,
                              List<JsonObject> initialEntities) {
        draft.setInt("width", width);
        draft.setInt("height", height);
        JsonObject sceneJson = new JsonObject();
        for (Map.Entry<String, List<String>> entry : scene.entrySet()) {
            JsonArray positions = new JsonArray();
            for (String pos : new LinkedHashSet<>(entry.getValue())) {
                positions.add(pos);
            }
            if (!positions.isEmpty()) {
                sceneJson.add(entry.getKey(), positions);
            }
        }
        draft.set("scene", sceneJson);
        JsonArray entities = new JsonArray();
        for (JsonObject entity : initialEntities) {
            entities.add(entity);
        }
        draft.set("initial_entities", entities);
    }

    public static void waves(JsonDraft draft, WaveEditorModel.Config waveConfig) {
        JsonObject waveJson = waveConfig.toJson();
        draft.setFloat("wave_interval_end_multiplier", waveConfig.intervalEndMultiplier);
        draft.set("waves", waveJson.getAsJsonArray("waves"));
    }

    /**
     * The card bar.
     *
     * <p>The card list is the whole contract: these cards are the level's and are pinned, and
     * {@code max_seed_slots} says how many slots there are. {@code seed_selection} is a field an
     * older editor wrote and nothing reads; it is removed rather than carried, so the file has
     * one answer to "what is in the bar".
     */
    public static void cards(JsonDraft draft, List<String> pool, int maxSeedSlots) {
        draft.set("slots", JsonDraft.idArray(pool));
        draft.remove("seed_selection");
        draft.setInt("max_seed_slots", maxSeedSlots);
    }

    public static void music(JsonDraft draft, MusicEditorModel.Config musicConfig) {
        draft.set("music", musicConfig.toJson());
    }

    /**
     * The opening conversation.
     *
     * <p>The lines are written <em>into</em> the existing block rather than replacing it:
     * {@code dialogue} is a wrapper, and a field a later version adds beside {@code lines} has
     * to survive an edit. An empty conversation removes the block, because "no conversation"
     * and "a conversation with no lines" must not both exist in the data.
     */
    public static void dialogue(JsonDraft draft, DialogueEditorModel.Config dialogueConfig) {
        JsonElement linesElement = dialogueConfig.toJson();
        JsonArray lines = linesElement != null && linesElement.isJsonArray()
                ? linesElement.getAsJsonArray() : new JsonArray();
        if (lines.isEmpty()) {
            draft.remove("dialogue");
            return;
        }
        JsonObject dialogue = existingObject(draft, "dialogue");
        dialogue.add("lines", lines);
        draft.set("dialogue", dialogue);
    }

    /**
     * The unlock conditions.
     *
     * <p>The page covers every field the format has, so the block is written whole - except
     * for requirements it cannot express ({@code coins}, or a type a later version adds), which
     * the page keeps verbatim and merges back in before calling this. An empty block is
     * removed: "no conditions" and "a block that says nothing" must not both exist.
     */
    public static void unlock(JsonDraft draft, JsonObject unlockJson) {
        boolean empty = !unlockJson.has("cost") && !unlockJson.has("hidden")
                && (!unlockJson.has("requires") || unlockJson.getAsJsonArray("requires").isEmpty());
        if (empty) {
            draft.remove("unlock");
        } else {
            draft.set("unlock", unlockJson);
        }
    }

    /**
     * The level's identity and economy: what it is called, what it starts with, what it pays.
     *
     * <p>{@code teams} and {@code unlock_resources} are written only when the file has none:
     * both are blocks with sane defaults, and a level that spells them out means it.
     */
    public static void info(JsonDraft draft, Identifier levelId, String name, String description,
                            int initialSun, LevelRewards rewards) {
        draft.setString("id", levelId.toString());
        draft.setString("name", name);
        draft.setString("description", description);
        draft.setInt("initial_sun", initialSun);
        draft.set("rewards", rewardsJson(rewards));
        if (!draft.has("unlock_resources")) {
            JsonObject resources = new JsonObject();
            resources.addProperty("pvzce:sun", true);
            draft.set("unlock_resources", resources);
        }
        if (!draft.has("teams")) {
            draft.set("teams", defaultTeams());
            draft.setString("win_team", "pvzce:plant_team");
        }
    }

    /** The rewards block in the shape {@link LevelRewards#CODEC} reads back. */
    public static JsonObject rewardsJson(LevelRewards rewards) {
        JsonObject out = new JsonObject();
        JsonArray firstClear = new JsonArray();
        for (LevelRewards.Reward reward : rewards.firstClear()) {
            firstClear.add(rewardJson(reward));
        }
        JsonArray repeat = new JsonArray();
        for (LevelRewards.Reward reward : rewards.repeat()) {
            repeat.add(rewardJson(reward));
        }
        out.add("first_clear", firstClear);
        out.add("repeat", repeat);
        out.addProperty("coin_drop", rewards.coinDrop().toString());
        out.addProperty("coin_drop_chance", rewards.coinDropChance());
        out.addProperty("coin_drop_amount", rewards.coinDropAmount());
        return out;
    }

    private static JsonObject rewardJson(LevelRewards.Reward reward) {
        JsonObject out = new JsonObject();
        out.addProperty("type", reward.type());
        reward.id().ifPresent(id -> out.addProperty("id", id.toString()));
        if (reward.amount() > 0) {
            out.addProperty("amount", reward.amount());
        }
        return out;
    }

    /** One unlock requirement: {@code {"type": "level", "id": "..."}}. */
    public static JsonObject requirementJson(String type, String id) {
        JsonObject entry = new JsonObject();
        entry.addProperty("type", type);
        entry.addProperty("id", id);
        return entry;
    }

    /** The teams a new level starts with; a level file with its own keeps them. */
    public static JsonArray defaultTeams() {
        JsonArray teams = new JsonArray();
        JsonObject plantTeam = new JsonObject();
        plantTeam.addProperty("id", "pvzce:plant_team");
        plantTeam.addProperty("name", "植物方");
        plantTeam.addProperty("win_condition", "survive_waves");
        JsonObject zombieTeam = new JsonObject();
        zombieTeam.addProperty("id", "pvzce:zombie_team");
        zombieTeam.addProperty("name", "僵尸方");
        zombieTeam.addProperty("win_condition", "plant_side_lost");
        teams.add(plantTeam);
        teams.add(zombieTeam);
        return teams;
    }

    /** The object at {@code path}, or a new one when the file has something else there. */
    private static JsonObject existingObject(JsonDraft draft, String path) {
        return draft.get(path)
                .filter(JsonElement::isJsonObject)
                .map(JsonElement::getAsJsonObject)
                .map(JsonObject::deepCopy)
                .orElseGet(JsonObject::new);
    }

    /** Cards a new level starts with; also what the card page's "restore defaults" uses. */
    public static List<String> defaultLevelCards() {
        return List.of("pvzce:pea_shooter", "pvzce:sunflower", "pvzce:wall_nut",
                "pvzce:kernel_pult", "pvzce:sun", "pvzce:shovel");
    }

    /** The card page's own limit, re-exported so the writer and the dialog cannot disagree. */
    public static int maxSeedSlotsLimit() {
        return CardPoolEditorDialog.MAX_SEED_SLOTS_LIMIT;
    }

    private LevelFileWriter() {
    }
}
