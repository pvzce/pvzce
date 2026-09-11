package com.pvzce.client.gui.config;

/** Builder methods mirroring Cloth's ConfigEntryBuilder (necessary subset). */
public final class ConfigEntryBuilder {
    private ConfigEntryBuilder() {
    }

    public static FloatConfigEntry createFloat(String id, String label, float value, float min, float max, Runnable saveConsumer) {
        return new FloatConfigEntry(id, label, value, min, max, saveConsumer);
    }
}
