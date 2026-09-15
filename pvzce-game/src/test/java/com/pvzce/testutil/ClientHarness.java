package com.pvzce.testutil;

import com.pvzce.client.PvzceClient;
import com.pvzce.common.network.Connection;
import com.pvzce.common.network.PvzcePacket;
import com.pvzce.common.network.PvzcePackets;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A windowless {@link PvzceClient} on the memory transport, plus a recorder for what it sends.
 *
 * <p>Building one of these is three lines that five client-side test classes had each written
 * out again ({@code LevelEntryFlowTest} and {@code LevelRestartClientTest} as a {@code Fixture}
 * record, {@code ScreenNavigationTest} / {@code ModalDialogInputTest} /
 * {@code LevelSelectLandingPageTest} as a bare factory method). The recorder end is what makes
 * "which packet did the client send" observable: the server end of the pair is ticked by
 * {@link #sentPackets()}.
 */
public final class ClientHarness implements AutoCloseable {
    private final PvzceClient client;
    private final Connection.Pair pair;
    private final Path gameDir;
    private final List<PvzcePacket> sent;

    /** @param tempPrefix prefix for this client's throwaway game directory */
    public static ClientHarness create(String tempPrefix) throws Exception {
        PvzcePackets.register();
        Path gameDir = Files.createTempDirectory(tempPrefix);
        Connection.Pair pair = Connection.createMemoryPair();
        List<PvzcePacket> sent = new ArrayList<>();
        pair.server().setListener(sent::add);
        PvzceClient client = new PvzceClient(pair.client(), gameDir,
                Thread.currentThread().getContextClassLoader());
        return new ClientHarness(client, pair, gameDir, sent);
    }

    private ClientHarness(PvzceClient client, Connection.Pair pair, Path gameDir, List<PvzcePacket> sent) {
        this.client = client;
        this.pair = pair;
        this.gameDir = gameDir;
        this.sent = sent;
    }

    public PvzceClient client() {
        return client;
    }

    public Connection.Pair pair() {
        return pair;
    }

    public Path gameDir() {
        return gameDir;
    }

    /** The server end, so a test can push packets at the client as a real server would. */
    public Connection serverEnd() {
        return pair.server();
    }

    /** Drains the server end and returns the packets the client sent so far. */
    public List<PvzcePacket> sentPackets() {
        pair.server().tick();
        return List.copyOf(sent);
    }

    @Override
    public void close() {
        try {
            pair.client().close();
        } catch (Exception ignored) {
            // Nothing is asserted about the transport after a test has finished.
        }
        try {
            pair.server().close();
        } catch (Exception ignored) {
        }
    }
}
