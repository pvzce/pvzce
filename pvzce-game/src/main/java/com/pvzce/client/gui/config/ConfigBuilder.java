package com.pvzce.client.gui.config;

import com.pvzce.client.PvzceClient;

import java.util.LinkedHashMap;
import java.util.Map;

/** Manual config builder (Cloth ConfigBuilder shaped subset). */
public final class ConfigBuilder {
    private final PvzceClient client;
    private final Map<String, ConfigCategory> categories = new LinkedHashMap<>();
    private String title = "配置";
    private Runnable savingRunnable;

    private ConfigBuilder(PvzceClient client) {
        this.client = client;
    }

    public static ConfigBuilder create(PvzceClient client) {
        return new ConfigBuilder(client);
    }

    public ConfigBuilder setTitle(String title) {
        this.title = title;
        return this;
    }

    public ConfigCategory getOrCreateCategory(String id) {
        return categories.computeIfAbsent(id, ConfigCategory::new);
    }

    public ConfigBuilder setSavingRunnable(Runnable savingRunnable) {
        this.savingRunnable = savingRunnable;
        return this;
    }

    public ConfigScreen build() {
        return new ConfigScreen(client, title, categories.values().stream().toList(), savingRunnable);
    }
}
