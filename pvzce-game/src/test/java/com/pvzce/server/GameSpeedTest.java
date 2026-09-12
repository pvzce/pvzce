package com.pvzce.server;

import com.pvzce.common.network.Connection;
import com.pvzce.common.network.PvzcePacket;
import com.pvzce.common.network.PvzcePackets;
import com.pvzce.common.network.packet.GameSpeedS2C;
import com.pvzce.common.network.packet.LevelInitS2C;
import com.pvzce.common.network.packet.RequestLevelC2S;
import com.pvzce.common.network.packet.SetGameSpeedC2S;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Game-speed button protocol and per-level reset behavior. */
class GameSpeedTest {
    @Test
    void speedButtonPacketChangesServerTickRateAndRestartResetsIt() throws Exception {
        PvzcePackets.register();
        Path gameDir = Files.createTempDirectory("pvzce-game-speed");
        Connection.Pair pair = Connection.createMemoryPair();
        PvzceServer server = new PvzceServer(pair.server(), gameDir, Thread.currentThread().getContextClassLoader());
        server.start();

        List<PvzcePacket> clientPackets = new ArrayList<>();
        pair.client().setListener(clientPackets::add);

        pair.client().send(new RequestLevelC2S("pvzce:yard/adventure/1_1", "speedtest", true));
        waitForCondition(5_000, () -> pair.client().tick(),
                () -> clientPackets.stream().anyMatch(p -> p instanceof LevelInitS2C));

        pair.client().send(new SetGameSpeedC2S(3));
        waitForCondition(5_000, () -> pair.client().tick(),
                () -> clientPackets.stream()
                        .filter(GameSpeedS2C.class::isInstance)
                        .map(GameSpeedS2C.class::cast)
                        .anyMatch(speed -> Math.abs(speed.tickRate() - 180F) < 0.001F));
        assertEquals(180F, server.tickRateManager().tickRate(), 0.001F);

        clientPackets.clear();
        pair.client().send(new RequestLevelC2S("pvzce:yard/adventure/1_1", "speedtest", true));
        waitForCondition(5_000, () -> pair.client().tick(),
                () -> clientPackets.stream()
                        .filter(GameSpeedS2C.class::isInstance)
                        .map(GameSpeedS2C.class::cast)
                        .anyMatch(speed -> Math.abs(speed.tickRate() - 60F) < 0.001F));
        assertEquals(60F, server.tickRateManager().tickRate(), 0.001F);

        server.stop();
        server.thread().join(3_000);
    }

    private static void waitForCondition(long timeoutMs, Runnable tick, java.util.function.BooleanSupplier condition)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < deadline) {
            tick.run();
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        assertTrue(false, "Timed out waiting for condition");
    }
}
