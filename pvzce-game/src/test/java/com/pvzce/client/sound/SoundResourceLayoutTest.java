package com.pvzce.client.sound;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards the categorized sound layout and sounds.json -> ogg mapping after the resource split. */
class SoundResourceLayoutTest {
    @Test
    void everySoundEventResolvesToItsCategorizedOggFile() throws Exception {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        JsonObject root;
        try (InputStream in = loader.getResourceAsStream("assets/pvzce/sounds.json")) {
            assertNotNull(in, "sounds.json must stay at assets/<ns>/sounds.json");
            root = JsonParser.parseReader(new java.io.InputStreamReader(in)).getAsJsonObject();
        }
        for (Map.Entry<String, JsonElement> event : root.entrySet()) {
            assertNotNull(event.getValue().getAsJsonObject().get("sounds"), event.getKey());
            for (JsonElement sound : event.getValue().getAsJsonObject().getAsJsonArray("sounds")) {
                String name = sound.getAsJsonObject().get("name").getAsString();
                assertTrue(name.startsWith("pvzce:sounds/"), name);
                String path = "assets/" + name.replace(':', '/') + ".ogg";
                assertNotNull(loader.getResource(path), "missing sound file " + path + " for " + event.getKey());
            }
        }
        assertTrue(root.has("sfx/plant/plant"));
        assertTrue(root.has("sfx/ui/collect"));
        assertTrue(root.has("music/grasswalk"));
        assertTrue(((JsonObject) root.get("music/grasswalk")).getAsJsonArray("sounds")
                .get(0).getAsJsonObject().get("name").getAsString().startsWith("pvzce:sounds/music/"));
    }
}
