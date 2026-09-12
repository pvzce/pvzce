package com.pvzce.client.gui.screens;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pvzce.client.gui.screens.MusicEditorModel.Config;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Music timeline JSON semantics used by {@link EditorScreen}. */
class MusicEditorConfigTest {
    @Test
    void missingMusicFieldOpensWithImplicitGrasswalkCue() {
        JsonObject root = JsonParser.parseString("{\"id\":\"test:level\"}").getAsJsonObject();
        Config config = Config.fromJson(root);
        assertEquals(1, config.cues.size());
        assertEquals("pvzce:music/grasswalk", config.cues.get(0).event);
        assertTrue(config.cues.get(0).loop);
    }

    @Test
    void explicitEmptyCuesStayEmpty() {
        JsonObject root = JsonParser.parseString("{\"music\":{\"cues\":[]}}").getAsJsonObject();
        Config config = Config.fromJson(root);
        assertTrue(config.cues.isEmpty(), "explicit empty timeline means no music");
    }

    @Test
    void cueFieldsRoundTripAtSixtyTicksPerSecond() {
        JsonObject root = JsonParser.parseString("""
                {"music":{"cues":[
                  {"at_tick":180,"track":"battle","event":"pvzce:music/ultimate_battle",
                   "loop":true,"stop":false,"volume":0.9,"fade_seconds":0.6}
                ]}}
                """).getAsJsonObject();
        Config config = Config.fromJson(root);
        assertEquals(1, config.cues.size());
        assertEquals(180, config.cues.get(0).atTick);
        assertEquals("battle", config.cues.get(0).track);
        assertEquals(0.9F, config.cues.get(0).volume, 0.001F);
        assertEquals(0.6F, config.cues.get(0).fadeSeconds, 0.001F);

        JsonObject serialized = config.toJson();
        Config second = Config.fromJson(JsonParser.parseString("{\"music\":" + serialized + "}").getAsJsonObject());
        assertEquals(180, second.cues.get(0).atTick);
        assertEquals("pvzce:music/ultimate_battle", second.cues.get(0).event);
        assertTrue(second.cues.get(0).loop);
    }

    @Test
    void stopCueHasNoEvent() {
        JsonObject root = JsonParser.parseString(
                "{\"music\":{\"cues\":[{\"at_tick\":60,\"track\":\"background\",\"stop\":true,\"fade_seconds\":0.0}]}}")
                .getAsJsonObject();
        Config config = Config.fromJson(root);
        assertTrue(config.cues.get(0).stop);
        assertTrue(config.cues.get(0).event.isEmpty());
    }

    @Test
    void cardPoolConfigKeepsExplicitEmptyPoolAndLimit() {
        JsonObject root = JsonParser.parseString(
                "{\"slots\":[],\"max_seed_slots\":2}").getAsJsonObject();
        CardPoolEditorDialog.Config config = CardPoolEditorDialog.Config.fromJson(root);
        assertEquals(2, config.maxSeedSlots);
        assertTrue(config.pool.isEmpty(), "an explicit empty pool is a valid authoring choice");
    }
}
