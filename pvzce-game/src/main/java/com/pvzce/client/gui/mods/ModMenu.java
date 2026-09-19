package com.pvzce.client.gui.mods;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mod-menu registry, mirroring ModMenu's MODS/configScreenFactories maps.
 * Built-in config factory for the {@code pvzce} mod is registered by the
 * client before the mods screen is opened.
 */
public final class ModMenu {
    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger("PVZCE/ModMenu");
    private static final List<ModInfo> MODS = new ArrayList<>();
    private static final Map<String, ConfigScreenFactory<?>> CONFIG_FACTORIES = new ConcurrentHashMap<>();
    private static final Map<String, ModContainer> CONTAINERS = new ConcurrentHashMap<>();
    private static volatile boolean initialized;
    private static ConfigScreenFactory<?> builtinFactory;

    private ModMenu() {
    }

    public static void initialize() {
        if (initialized) {
            return;
        }
        initialized = true;
        CONFIG_FACTORIES.clear();
        MODS.clear();
        CONTAINERS.clear();
        for (EntrypointContainer<ModMenuApi> entrypoint : FabricLoader.getInstance()
                .getEntrypointContainers("pvzce.modmenu", ModMenuApi.class)) {
            try {
                ModMenuApi api = entrypoint.getEntrypoint();
                String modId = entrypoint.getProvider().getMetadata().getId();
                ConfigScreenFactory<?> own = api.getModConfigScreenFactory();
                if (own != null) {
                    CONFIG_FACTORIES.put(modId, own);
                }
                api.getProvidedConfigScreenFactories().forEach(CONFIG_FACTORIES::putIfAbsent);
            } catch (Throwable t) {
                LOGGER.warn("Broken pvzce.modmenu entrypoint "
                        + entrypoint.getProvider().getMetadata().getId(), t);
            }
        }
        if (builtinFactory != null) {
            CONFIG_FACTORIES.put("pvzce", builtinFactory);
        }
        FabricLoader.getInstance().getAllMods().stream()
                .peek(container -> CONTAINERS.put(container.getMetadata().getId(), container))
                .map(ModInfo::from)
                .sorted(Comparator.comparing(info -> info.name().toLowerCase()))
                .forEach(MODS::add);
    }

    public static void setBuiltinConfigFactory(ConfigScreenFactory<?> factory) {
        builtinFactory = factory;
        if (initialized) {
            CONFIG_FACTORIES.put("pvzce", factory);
        }
    }

    public static List<ModInfo> mods() {
        if (!initialized) {
            initialize();
        }
        return List.copyOf(MODS);
    }

    public static ConfigScreenFactory<?> getConfigFactory(String modId) {
        if (!initialized) {
            initialize();
        }
        return CONFIG_FACTORIES.get(modId);
    }

    public static boolean hasConfigScreen(String modId) {
        return getConfigFactory(modId) != null;
    }

    public static java.util.List<ModInfo> childrenOf(String parentId) {
        if (!initialized) {
            initialize();
        }
        return MODS.stream().filter(mod -> parentId.equals(mod.parentId())).toList();
    }

    /** Reads a mod icon from its jar/folder; null when absent. */
    public static byte[] iconBytes(ModInfo mod) {
        if (!initialized) {
            initialize();
        }
        ModContainer container = CONTAINERS.get(mod.id());
        if (container != null) {
            try {
                var path = container.findPath(mod.iconPath());
                if (path.isPresent()) {
                    return java.nio.file.Files.readAllBytes(path.get());
                }
            } catch (Exception e) {
                // Broken icon falls back to the classpath / placeholder.
            }
        }
        // Builtin/synthetic mods (java, pvzce) ship their icons on the game classpath.
        try (var in = ModMenu.class.getClassLoader().getResourceAsStream(mod.iconPath())) {
            if (in != null) {
                return in.readAllBytes();
            }
        } catch (Exception e) {
            // Broken icon falls back to a placeholder block.
        }
        return null;
    }
}
