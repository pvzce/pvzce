package com.pvzce.client.gui.screens;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pvzce.api.content.LevelRewards;
import com.pvzce.api.content.LevelUnlock;
import com.pvzce.api.util.Identifier;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the editor writes back to disk.
 *
 * <p>Two failures are worth pinning down here, because both were real: the editor
 * used to derive the level id from the name field, and it used to rebuild the JSON
 * from only the fields it modelled - so editing a level authored by someone else
 * silently dropped everything the editor had no widget for.
 */
class EditorLevelJsonTest {
    private static Identifier id(String raw) {
        // Test helper, not production code: several cases here use a second namespace
        // to prove the editor does not force everything into "pvzce".
        return Identifier.parse(raw);
    }

    private static WaveEditorModel.Config waves(int count) {
        WaveEditorModel.Config config = new WaveEditorModel.Config();
        for (int i = 0; i < count; i++) {
            WaveEditorModel.WaveModel wave = new WaveEditorModel.WaveModel();
            wave.type = i == count - 1 ? "final" : "small";
            wave.delay = 600 + i;
            wave.entries.add(new WaveEditorModel.EntryModel("pvzce:basic_zombie", i + 1));
            config.waves.add(wave);
        }
        return config;
    }

    // ------------------------------------------------------------------
    // Unlock conditions
    // ------------------------------------------------------------------

    private static JsonObject unlockJson(String requiresJson, Integer cost, boolean hidden) {
        return JsonParser.parseString("{ \"requires\": [" + requiresJson + "]"
                + (cost == null ? "" : ", \"cost\": " + cost)
                + (hidden ? ", \"hidden\": true" : "") + " }").getAsJsonObject();
    }

    private static JsonObject withUnlock(String requiresJson, Integer cost, boolean hidden) {
        return EditorScreen.buildLevelJson(new JsonObject(), id("gated"), "关卡", "", 9, 5,
                Map.of(), List.of(), new JsonObject(), waves(1),
                List.of("pvzce:pea_shooter"), 6, 150, new JsonObject(), LevelRewards.DEFAULT,
                unlockJson(requiresJson, cost, hidden));
    }

    /** The block the 解锁 page writes has to be exactly what the codec reads. */
    @Test
    void theUnlockBlockRoundTripsThroughItsCodec() {
        JsonObject written = withUnlock(
                "{\"type\":\"level\",\"id\":\"pvzce:yard/adventure/1_1\"},"
                        + "{\"type\":\"card\",\"id\":\"pvzce:sunflower\"}",
                500, true);

        LevelUnlock parsed = LevelUnlock.CODEC.parse(
                com.mojang.serialization.JsonOps.INSTANCE, written.get("unlock")).getOrThrow();
        assertEquals(2, parsed.requires().size());
        assertTrue(parsed.requires().get(0).isLevel());
        assertEquals(500, parsed.cost().orElseThrow());
        assertTrue(parsed.hidden());
    }

    /** An empty page means "no gate", and the file must not carry an empty block. */
    @Test
    void anEmptyUnlockPageWritesNoBlock() {
        JsonObject written = withUnlock("", null, false);
        assertFalse(written.has("unlock"),
                "an empty unlock block and no unlock block would both exist in the data");
    }

    /**
     * A hand-written condition the page cannot edit has to survive.
     *
     * <p>{@code coins} is the case: the page edits prerequisite levels, required cards,
     * the price and the hidden flag, and would otherwise quietly delete a coin threshold
     * the moment someone opened the level and pressed save.
     */
    @Test
    void aConditionThePageCannotEditIsKeptVerbatim() throws Exception {
        JsonObject authored = JsonParser.parseString("""
                {
                  "id": "pvzce:authored",
                  "unlock": {
                    "requires": [
                      { "type": "coins", "amount": 750 },
                      { "type": "level", "id": "pvzce:yard/adventure/1_1" }
                    ]
                  }
                }
                """).getAsJsonObject();

        // The page reads the level into its fields, then writes them back unchanged.
        JsonObject written = EditorScreen.buildLevelJson(authored, id("authored"), "关卡", "", 9, 5,
                Map.of(), List.of(), new JsonObject(), waves(1),
                List.of("pvzce:pea_shooter"), 6, 150, new JsonObject(), LevelRewards.DEFAULT,
                unlockJson("{ \"type\": \"coins\", \"amount\": 750 },"
                        + "{ \"type\": \"level\", \"id\": \"pvzce:yard/adventure/1_1\" }",
                        null, false));

        LevelUnlock parsed = LevelUnlock.CODEC.parse(
                com.mojang.serialization.JsonOps.INSTANCE, written.get("unlock")).getOrThrow();
        assertEquals(2, parsed.requires().size(), "neither condition may be dropped");
        assertTrue(parsed.requires().get(0).isCoins());
        assertEquals(750, parsed.requires().get(0).amount());
    }

    /** A null block means the editor never touched it; a hand-written one stays. */
    @Test
    void notTouchingThePageLeavesTheBlockAlone() throws Exception {
        JsonObject authored = JsonParser.parseString(
                "{\"id\":\"pvzce:x\",\"unlock\":{\"cost\":50}}").getAsJsonObject();
        JsonObject written = EditorScreen.buildLevelJson(authored, id("x"), "关卡", "", 9, 5,
                Map.of(), List.of(), new JsonObject(), waves(1),
                List.of("pvzce:pea_shooter"), 6, 150, new JsonObject());
        assertEquals(50, written.getAsJsonObject("unlock").get("cost").getAsInt());
    }

    @Test
    void theIdComesFromTheIdFieldNotTheName() {
        JsonObject written = EditorScreen.buildLevelJson(new JsonObject(), id("my_arena"),
                "我的竞技场", "", 9, 5, Map.of(), List.of(), new JsonObject(), waves(1),
                List.of("pvzce:pea_shooter"), 6, 150, new JsonObject());

        // The old editor wrote "pvzce:" + sanitizedName, so a Chinese level name became
        // "pvzce:______" and the file it wrote no longer matched the id it opened.
        assertEquals("pvzce:my_arena", written.get("id").getAsString(),
                "the id must not be derived from the display name");
        assertEquals("我的竞技场", written.get("name").getAsString());
    }

    @Test
    void fieldsTheEditorDoesNotModelSurviveASave() {
        JsonObject authored = JsonParser.parseString("""
                {
                  "id": "otherns:arena",
                  "teams": [{"id": "otherns:red", "name": "红队"}],
                  "win_team": "otherns:red",
                  "env_vars": {"otherns:timer": {"type": "pvzce:int", "value": 30}},
                  "future_field": {"nested": [1, 2, 3]},
                  "unlock_resources": {"pvzce:sun": true, "otherns:gold": false}
                }
                """).getAsJsonObject();

        JsonObject written = EditorScreen.buildLevelJson(authored, id("otherns:arena"), "竞技场", "说明",
                9, 5, Map.of("pvzce:grass", List.of("0,0")), List.of(), new JsonObject(), waves(0),
                List.of(), 6, 200, new JsonObject());

        assertEquals("红队", written.getAsJsonArray("teams").get(0).getAsJsonObject()
                .get("name").getAsString(), "teams must not be replaced");
        assertEquals("otherns:red", written.get("win_team").getAsString());
        assertTrue(written.has("env_vars"), "unmodelled environment variables must survive");
        assertTrue(written.has("future_field"), "a field from a newer version must survive");
        assertEquals(3, written.getAsJsonObject("future_field").getAsJsonArray("nested").size());
        assertFalse(written.getAsJsonObject("unlock_resources").get("otherns:gold").getAsBoolean(),
                "an existing unlock map must not be overwritten by the default");
    }

    @Test
    void aNewLevelGetsTheDefaultsItNeedsToBePlayable() {
        JsonObject written = EditorScreen.buildLevelJson(new JsonObject(), id("fresh"), "新关卡", "",
                9, 5, Map.of("pvzce:grass", List.of("0,0", "1,0")), List.of(), new JsonObject(), waves(2),
                List.of("pvzce:pea_shooter", "pvzce:sun"), 6, 150, new JsonObject());

        assertEquals(2, written.getAsJsonArray("teams").size());
        assertEquals("pvzce:plant_team", written.get("win_team").getAsString());
        assertTrue(written.getAsJsonObject("unlock_resources").get("pvzce:sun").getAsBoolean());
        assertEquals(6, written.get("max_seed_slots").getAsInt());
        assertEquals(150, written.get("initial_sun").getAsInt());
        assertEquals(2, written.getAsJsonArray("slots").size());
        assertEquals(2, written.getAsJsonArray("waves").size());
        assertEquals(2, written.getAsJsonObject("scene").getAsJsonArray("pvzce:grass").size());
    }

    @Test
    void scenePositionsAreDeduplicated() {
        Map<String, List<String>> scene = new LinkedHashMap<>();
        scene.put("pvzce:grass", List.of("0,0", "0,0", "1,0"));
        JsonObject written = EditorScreen.buildLevelJson(new JsonObject(), id("dedup"), "去重", "",
                9, 5, scene, List.of(), new JsonObject(), waves(0), List.of(), 6, 150, new JsonObject());
        assertEquals(2, written.getAsJsonObject("scene").getAsJsonArray("pvzce:grass").size());
    }

    @Test
    void anEmptySceneElementIsNotWrittenAsAnEmptyArray() {
        // An element with no cells contributes nothing; writing it as [] would make the
        // file claim the element exists somewhere.
        Map<String, List<String>> scene = new LinkedHashMap<>();
        scene.put("pvzce:grass", List.of("0,0"));
        scene.put("pvzce:water", new ArrayList<>());
        JsonObject written = EditorScreen.buildLevelJson(new JsonObject(), id("empty"), "空", "",
                9, 5, scene, List.of(), new JsonObject(), waves(0), List.of(), 6, 150, new JsonObject());
        assertFalse(written.getAsJsonObject("scene").has("pvzce:water"));
    }

    @Test
    void presetEntitiesAreCarriedThrough() {
        JsonObject plant = new JsonObject();
        plant.addProperty("kind", "plant");
        plant.addProperty("id", "pvzce:wall_nut");
        plant.addProperty("x", 3);
        plant.addProperty("y", 2);
        JsonObject written = EditorScreen.buildLevelJson(new JsonObject(), id("entities"), "实体", "",
                9, 5, Map.of(), List.of(plant), new JsonObject(), waves(0), List.of(), 6, 150,
                new JsonObject());
        JsonArray entities = written.getAsJsonArray("initial_entities");
        assertEquals(1, entities.size());
        assertEquals(3, entities.get(0).getAsJsonObject().get("x").getAsInt());
        assertEquals("pvzce:wall_nut", entities.get(0).getAsJsonObject().get("id").getAsString());
    }

    @Test
    void rulesAndWavesAreWrittenFromTheEditorState() {
        JsonObject rules = new JsonObject();
        rules.addProperty("pvzce:sun_spawn_chance", 0.5D);
        WaveEditorModel.Config waveConfig = waves(3);
        waveConfig.intervalEndMultiplier = 0.75F;

        JsonObject written = EditorScreen.buildLevelJson(new JsonObject(), id("tuned"), "调参", "",
                9, 5, Map.of(), List.of(), rules, waveConfig, List.of(), 6, 150, new JsonObject());

        assertEquals(0.5D, written.getAsJsonObject("rules").get("pvzce:sun_spawn_chance").getAsDouble());
        assertEquals(0.75F, written.get("wave_interval_end_multiplier").getAsFloat());
        assertEquals(3, written.getAsJsonArray("waves").size());
        assertEquals("final", written.getAsJsonArray("waves").get(2).getAsJsonObject()
                .get("type").getAsString());
    }

    @Test
    void theRewardsBlockIsWrittenWholeSoTheLevelKeepsItsIdentity() {
        // 1-1's shape: unlock sunflower on a first clear, pay 100 coins on a replay.
        LevelRewards rewards = new LevelRewards(
                List.of(LevelRewards.Reward.unlock(id("pvzce:sunflower"))),
                List.of(LevelRewards.Reward.coins(100)), 0.25F, id("pvzce:coin_silver"), 1);

        JsonObject written = EditorScreen.buildLevelJson(new JsonObject(), id("first"), "第一关", "",
                9, 1, Map.of(), List.of(), new JsonObject(), waves(0), List.of(), 2, 50,
                new JsonObject(), rewards);

        JsonObject writtenRewards = written.getAsJsonObject("rewards");
        JsonObject unlock = writtenRewards.getAsJsonArray("first_clear").get(0).getAsJsonObject();
        assertEquals("unlock", unlock.get("type").getAsString());
        assertEquals("pvzce:sunflower", unlock.get("id").getAsString());
        assertEquals("coins", writtenRewards.getAsJsonArray("repeat").get(0).getAsJsonObject()
                .get("type").getAsString());
        assertEquals(100, writtenRewards.getAsJsonArray("repeat").get(0).getAsJsonObject()
                .get("amount").getAsInt());
        assertEquals(0.25F, writtenRewards.get("coin_drop_chance").getAsFloat());
        assertEquals(1, writtenRewards.get("coin_drop_amount").getAsInt());
    }

    @Test
    void aLevelWithNoRewardsIsWrittenWithTheDefaultsRatherThanNothing() {
        JsonObject written = EditorScreen.buildLevelJson(new JsonObject(), id("plain"), "普通", "",
                9, 5, Map.of(), List.of(), new JsonObject(), waves(0), List.of(), 6, 150,
                new JsonObject());

        JsonObject rewards = written.getAsJsonObject("rewards");
        // Explicit, not absent: the file then says what the level pays instead of
        // leaving the reader to know the codec's defaults.
        assertTrue(rewards.getAsJsonArray("first_clear").isEmpty());
        assertEquals(LevelRewards.DEFAULT_REPEAT_COINS,
                rewards.getAsJsonArray("repeat").get(0).getAsJsonObject().get("amount").getAsInt());
        assertEquals(LevelRewards.DEFAULT_COIN_DROP_CHANCE, rewards.get("coin_drop_chance").getAsFloat());
        assertEquals(LevelRewards.DEFAULT_COIN_DROP.toString(), rewards.get("coin_drop").getAsString());
    }

    @Test
    void aRewardsBlockWrittenByTheEditorParsesBackThroughTheCodec() {
        // The editor and the loader must agree, or a saved level silently loses the
        // reward the moment it is reloaded.
        LevelRewards original = new LevelRewards(
                List.of(LevelRewards.Reward.unlock(id("pvzce:wall_nut"))),
                List.of(LevelRewards.Reward.coins(70)), 0.5F, id("pvzce:coin_gold"), 4);

        JsonObject written = EditorScreen.buildLevelJson(new JsonObject(), id("roundtrip"), "往返", "",
                9, 5, Map.of(), List.of(), new JsonObject(), waves(0), List.of(), 6, 150,
                new JsonObject(), original);
        LevelRewards parsed = LevelRewards.CODEC
                .parse(com.mojang.serialization.JsonOps.INSTANCE, written.get("rewards"))
                .getOrThrow();

        assertEquals(original.firstClear(), parsed.firstClear());
        assertEquals(original.repeat(), parsed.repeat());
        assertEquals(original.coinDropChance(), parsed.coinDropChance(), 0.0001F);
        assertEquals(original.coinDrop(), parsed.coinDrop());
        assertEquals(original.coinDropAmount(), parsed.coinDropAmount());
    }
}
