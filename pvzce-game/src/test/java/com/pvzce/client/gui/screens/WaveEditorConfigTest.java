package com.pvzce.client.gui.screens;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Editor wave model <-> JSON round-trip (used by the level editor's save button). */
class WaveEditorConfigTest {
    @Test
    void configRoundTripsThroughLevelJson() {
        WaveEditorDialog.Config config = new WaveEditorDialog.Config();
        config.intervalEndMultiplier = 0.75F;

        WaveEditorDialog.WaveModel wave = new WaveEditorDialog.WaveModel();
        wave.type = "huge";
        wave.delay = 1200;
        wave.warningTicks = 300;
        wave.entries.add(new WaveEditorDialog.EntryModel("pvzce:basic_zombie", 2));
        wave.entries.add(new WaveEditorDialog.EntryModel("pvzce:buckethead_zombie", 1));
        config.waves.add(wave);

        JsonObject json = config.toJson();
        WaveEditorDialog.Config parsed = WaveEditorDialog.Config.fromJson(json);

        assertEquals(0.75F, parsed.intervalEndMultiplier, 0.0001F);
        assertEquals(1, parsed.waves.size());
        assertEquals("huge", parsed.waves.get(0).type);
        assertEquals(1200, parsed.waves.get(0).delay);
        assertEquals(300, parsed.waves.get(0).warningTicks);
        assertEquals(2, parsed.waves.get(0).entries.size());
        assertEquals("pvzce:basic_zombie", parsed.waves.get(0).entries.get(0).id);
        assertEquals(2, parsed.waves.get(0).entries.get(0).count);
    }
}
