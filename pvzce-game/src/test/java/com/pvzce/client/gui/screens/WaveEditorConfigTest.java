package com.pvzce.client.gui.screens;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Editor wave model <-> JSON round-trip (used by the level editor's save button). */
class WaveEditorConfigTest {
    @Test
    void changingAnEntryCountPreservesItsInstanceAttributesAndArrivalMetadata() {
        JsonObject root = com.google.gson.JsonParser.parseString("""
                {"waves":[{"delay":10,"entries":[{"id":"pvzce:basic_zombie","count":2,
                "rows":[2,3],"surface":"example:bridge","health_scale":1.5,
                "attributes":{"pvzce:render_scale":0.5,"pvzce:max_health":{"modifiers":[
                {"id":"example:small","amount":-0.5,"operation":"add_multiplied_total"}]}}}]}]}
                """).getAsJsonObject();
        JsonObject original = root.getAsJsonArray("waves").get(0).getAsJsonObject()
                .getAsJsonArray("entries").get(0).getAsJsonObject();
        WaveEditorModel.Config config = WaveEditorModel.Config.fromJson(root);
        config.waves.get(0).entries.get(0).count = 3;
        JsonObject written = config.toJson().getAsJsonArray("waves").get(0).getAsJsonObject()
                .getAsJsonArray("entries").get(0).getAsJsonObject();
        assertEquals(3, written.get("count").getAsInt());
        assertEquals(2, original.get("count").getAsInt(), "the draft is not aliased");
        for (String field : java.util.List.of("attributes", "rows", "surface", "health_scale"))
            assertEquals(original.get(field), written.get(field));
    }

    @Test
    void configRoundTripsThroughLevelJson() {
        WaveEditorModel.Config config = new WaveEditorModel.Config();
        config.intervalEndMultiplier = 0.75F;

        WaveEditorModel.WaveModel wave = new WaveEditorModel.WaveModel();
        wave.type = "huge";
        wave.delay = 1200;
        wave.warningTicks = 300;
        wave.entries.add(new WaveEditorModel.EntryModel("pvzce:basic_zombie", 2));
        wave.entries.add(new WaveEditorModel.EntryModel("pvzce:buckethead_zombie", 1));
        config.waves.add(wave);

        JsonObject json = config.toJson();
        WaveEditorModel.Config parsed = WaveEditorModel.Config.fromJson(json);

        assertEquals(0.75F, parsed.intervalEndMultiplier, 0.0001F);
        assertEquals(1, parsed.waves.size());
        assertEquals("huge", parsed.waves.get(0).type);
        assertEquals(1200, parsed.waves.get(0).delay);
        assertEquals(300, parsed.waves.get(0).warningTicks);
        assertEquals(2, parsed.waves.get(0).entries.size());
        assertEquals("pvzce:basic_zombie", parsed.waves.get(0).entries.get(0).id);
        assertEquals(2, parsed.waves.get(0).entries.get(0).count);
    }

    /**
     * A field the editor cannot edit survives the round trip.
     *
     * <p>The wave table has no control for the opening death gate, and it rebuilds the whole
     * wave object on save - so without carrying it, opening a hand-written level in the
     * editor would quietly turn the gate back on (or off) for that wave.
     */
    @Test
    void uneditedWaveFieldsSurviveTheRoundTrip() {
        JsonObject root = com.google.gson.JsonParser.parseString("""
                {
                  "waves": [
                    { "delay": 10, "entries": [ { "id": "pvzce:basic_zombie" } ],
                      "hold_until_dead": 0 },
                    { "delay": 10, "entries": [ { "id": "pvzce:basic_zombie" } ] }
                  ]
                }
                """).getAsJsonObject();

        WaveEditorModel.Config parsed = WaveEditorModel.Config.fromJson(root);
        assertEquals(Integer.valueOf(0), parsed.waves.get(0).holdUntilDeadTicks);
        assertEquals(null, parsed.waves.get(1).holdUntilDeadTicks, "unwritten stays unwritten");

        JsonObject written = parsed.toJson();
        assertEquals(0, written.getAsJsonArray("waves").get(0).getAsJsonObject()
                .get("hold_until_dead").getAsInt());
        assertEquals(false, written.getAsJsonArray("waves").get(1).getAsJsonObject()
                .has("hold_until_dead"), "and is not frozen into the file by a save");
    }
}
