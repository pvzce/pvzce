package com.pvzce.client.gui.mods;

import com.pvzce.client.gui.Screen;

import java.util.Map;

/**
 * Mod entrypoint ({@code pvzce.modmenu}) API, mirroring ModMenu's
 * {@code ModMenuApi} shape under the PVZCE package.
 */
public interface ModMenuApi {
    /**
     * @return factory for this mod's config screen, or null when it has none.
     */
    default ConfigScreenFactory<?> getModConfigScreenFactory() {
        return null;
    }

    /**
     * Factories this mod provides for OTHER mods.
     */
    default Map<String, ConfigScreenFactory<?>> getProvidedConfigScreenFactories() {
        return Map.of();
    }
}
