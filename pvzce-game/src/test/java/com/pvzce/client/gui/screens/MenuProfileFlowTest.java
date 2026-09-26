package com.pvzce.client.gui.screens;

import com.pvzce.client.PvzceClient;
import com.pvzce.common.network.Connection;
import com.pvzce.common.network.PvzcePacket;
import com.pvzce.common.network.PvzcePackets;
import com.pvzce.common.network.packet.ProfileS2C;
import com.pvzce.common.network.packet.RequestProfileC2S;
import com.pvzce.server.PlayerProfile;
import com.pvzce.server.PvzceServer;
import com.pvzce.server.WorldStore;
import com.pvzce.testutil.ClientHarness;
import com.pvzce.common.tag.TestContent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The wallet the shop draws is asked for, not waited for.
 *
 * <p>The bug this pins: opening the shop on a fresh session showed <b>0 金币</b>, and it only
 * showed the real balance after starting a level and coming back. The profile is reset by
 * {@code PvzceClient.setCurrentWorld} on every world change (so one world's coins are never drawn
 * while another world's list loads) and was otherwise filled in <em>only</em> by the level list -
 * which the shop is reachable without, because the title screen's corner tray goes straight there.
 *
 * <p>Both halves are tested against real counterparts rather than mocks: the client half opens the
 * real screen on a windowless client and reads what it sent, the server half runs a real
 * {@code PvzceServer} on a memory pair and reads the profile packet it answers with.
 */
class MenuProfileFlowTest {
    private final List<ClientHarness> harnesses = new ArrayList<>();

    @BeforeAll
    static void load() throws Exception {
        TestContent.loadBuiltInContentAndTags();
    }

    @AfterEach
    void closeHarnesses() {
        harnesses.forEach(ClientHarness::close);
    }

    /**
     * The request is asked once per world, and asked again for the next world.
     *
     * <p>Called on the client rather than through a click: the click path is {@code TitleScreen}'s
     * own geometry, which needs a window, and the smoke run drives that end to end
     * ({@code pvzce.smokePages=…:shop}). What this half pins is the part with the bug in it - a
     * fresh session asks, an answered session does not, and a different world's wallet is a new
     * question.
     */
    @Test
    void theRequestIsNotRepeatedOnceTheProfileHasArrived() throws Exception {
        ClientHarness harness = ClientHarness.create("pvzce-shop-profile-once");
        harnesses.add(harness);
        PvzceClient client = harness.client();
        client.setCurrentWorld("aaa");

        client.requestProfile();
        client.requestProfile();
        assertEquals(1, countRequests(harness),
                "two asks before any answer are still one request");

        // The server's snapshot arrives; the same world needs nothing more.
        client.setProfile(120, List.of(), false);
        client.requestProfile();
        assertEquals(1, countRequests(harness), "a loaded profile is not asked for again");

        // A different world has its own wallet, so the question is live again.
        client.setCurrentWorld("bbb");
        client.requestProfile();
        assertEquals(2, countRequests(harness), "a new world has to be asked about");
    }

    private static int countRequests(ClientHarness harness) {
        int count = 0;
        for (PvzcePacket packet : harness.sentPackets()) {
            if (packet instanceof RequestProfileC2S) {
                count++;
            }
        }
        return count;
    }

    /**
     * The server answers that request with the world's real wallet.
     *
     * <p>The world is one the server has never seen before this test, with a saved profile: that is
     * the shape of the bug report - a player whose coins are on disk, and a session that has not
     * loaded them yet.
     */
    @Test
    void theServerAnswersWithTheWorldsWallet(@TempDir Path gameDir) throws Exception {
        PvzcePackets.register();
        WorldStore store = new WorldStore(gameDir);
        PlayerProfile rich = PlayerProfile.starter();
        rich.setCoins(1234);
        store.saveProfile("aaa", rich);

        Connection.Pair pair = Connection.createMemoryPair();
        PvzceServer server = new PvzceServer(pair.server(), gameDir,
                Thread.currentThread().getContextClassLoader());
        server.start();
        List<PvzcePacket> fromServer = new ArrayList<>();
        pair.client().setListener(fromServer::add);
        try {
            pair.client().send(new RequestProfileC2S("aaa"));
            // The answer is what the client's own listener turns into visible coins, so this
            // waits for the packet rather than for a timer.
            waitFor(5_000, () -> pair.client().tick(),
                    () -> fromServer.stream().anyMatch(ProfileS2C.class::isInstance));

            ProfileS2C profile = fromServer.stream()
                    .filter(ProfileS2C.class::isInstance)
                    .map(ProfileS2C.class::cast)
                    .findFirst()
                    .orElseThrow();
            assertEquals(1234, profile.coins(), "the wallet is the one saved for that world");
        } finally {
            server.stop();
            server.thread().join(3_000);
        }
    }

    private static void waitFor(long timeoutMs, Runnable tick, java.util.function.BooleanSupplier done)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < deadline) {
            tick.run();
            if (done.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        assertTrue(false, "timed out waiting for the server's answer");
    }
}
