package com.pvzce.client.gui.config;

import java.util.ArrayList;
import java.util.List;

/** One collapsible category of config entries. */
public final class ConfigCategory {
    private final String id;
    private final List<ConfigEntry<?>> entries = new ArrayList<>();

    public ConfigCategory(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public ConfigCategory addEntry(ConfigEntry<?> entry) {
        entries.add(entry);
        return this;
    }

    public List<ConfigEntry<?>> entries() {
        return List.copyOf(entries);
    }
}
