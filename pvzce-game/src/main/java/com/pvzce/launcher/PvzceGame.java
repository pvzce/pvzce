package com.pvzce.launcher;

import com.pvzce.client.PvzceClient;
import com.pvzce.common.PvzceLicense;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.network.Connection;
import com.pvzce.server.PvzceServer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.nio.file.Path;
import java.util.Collection;

/** Game entrypoint invoked by Knot through {@link PvzceGameProvider}. */
public final class PvzceGame {
    private static final Logger LOGGER = LoggerFactory.getLogger("PVZCE/Launcher");

    private PvzceGame() {
    }

    public static void main(String[] args) {
        for (String arg : args) {
            if (arg.startsWith("--versionType=")) {
                System.setProperty("pvzce.versionType", arg.substring("--versionType=".length()));
            }
        }
        Path gameDir = FabricLoader.getInstance().getGameDir();
        LOGGER.info("{} {} starting in {}", PvzceVersions.GAME_NAME, PvzceVersions.GAME_VERSION, gameDir);
        // GPL-3.0 §5(d) 的 "Appropriate Legal Notices"：版权 + 无担保 + 许可去哪取。
        // 展开的全文在设置里的"关于"页（AboutScreen），两处共用 PvzceLicense 那一份常量。
        LOGGER.info(PvzceLicense.STARTUP_NOTICE);

        BuiltInRegistries.bootstrap();

        Collection<ModContainer> mods = FabricLoader.getInstance().getAllMods();
        LOGGER.info("Loaded {} mods", mods.size());
        for (ModContainer mod : mods) {
            LOGGER.info("  - {} {}", mod.getMetadata().getId(), mod.getMetadata().getVersion().getFriendlyString());
        }

        try {
            FabricLoader.getInstance().invokeEntrypoints("main", ModInitializer.class, ModInitializer::onInitialize);
        } catch (Throwable t) {
            throw new RuntimeException("A main entrypoint failed", t);
        }

        try {
            FabricLoader.getInstance().invokeEntrypoints("client", ClientModInitializer.class, ClientModInitializer::onInitializeClient);
        } catch (Throwable t) {
            throw new RuntimeException("A client entrypoint failed", t);
        }

        Connection.Pair pair = Connection.createMemoryPair();
        PvzceServer server = new PvzceServer(pair.server(), gameDir, PvzceGame.class.getClassLoader());
        PvzceClient client = new PvzceClient(pair.client(), gameDir, PvzceGame.class.getClassLoader());

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop();
            try {
                Thread.sleep(500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }, "PvzceShutdownHook"));

        server.start();
        try {
            client.run();
        } catch (Throwable t) {
            LOGGER.error("The client loop failed", t);
        } finally {
            server.stop();
            try {
                Thread.sleep(600);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
