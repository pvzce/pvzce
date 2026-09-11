package com.pvzce.client.gui.mods;

import com.pvzce.client.gui.Screen;

/** Creates a config screen for a mod, shown from the mods screen. */
public interface ConfigScreenFactory<T extends Screen> {
    T create(Screen parent);
}
