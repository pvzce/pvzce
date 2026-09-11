package com.pvzce.client.gui.config;

/** One editable config entry (float/int/bool), Cloth-entry shaped. */
public interface ConfigEntry<T> {
    String id();

    String label();

    T value();

    void setValue(T value);
}
