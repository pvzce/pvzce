package com.pvzce.launcher;

import com.pvzce.client.PvzceClient;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.network.Connection;
import com.pvzce.server.PvzceServer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import java.nio.file.Path;
import java.util.Collection;

/** Game entrypoint invoked by Knot through {@link PvzceGameProvider}. */
public final class PvzceGame {
    private PvzceGame() {
    }

    public static void main(String[] args) {
        for (String arg : args) {
            if (arg.startsWith("--versionType=")) {
                System.setProperty("pvzce.versionType", arg.substring("--versionType=".length()));
            }
        }
        Path gameDir = FabricLoader.getInstance().getGameDir();
        System.out.println("[PVZCE] " + PvzceVersions.GAME_NAME + " " + PvzceVersions.GAME_VERSION + " starting in " + gameDir);

        BuiltInRegistries.bootstrap();

        Collection<ModContainer> mods = FabricLoader.getInstance().getAllMods();
        System.out.println("[PVZCE] Loaded mods: " + mods.size());
        for (ModContainer mod : mods) {
            System.out.println("[PVZCE]   - " + mod.getMetadata().getId() + " " + mod.getMetadata().getVersion().getFriendlyString());
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
            t.printStackTrace();
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
