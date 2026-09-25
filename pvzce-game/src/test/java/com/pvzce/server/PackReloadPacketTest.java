package com.pvzce.server;

import com.pvzce.api.util.Identifier;
import com.pvzce.common.network.packet.ReloadPacksC2S;
import com.pvzce.common.network.packet.ServerMessageS2C;
import com.pvzce.common.resource.PackSelection;
import com.pvzce.testutil.ServerHarness;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The server half of switching a pack off: the request rebuilds the stack, and the answer is a
 * message.
 *
 * <p>The client reloads nothing itself - it writes the list, sends {@link ReloadPacksC2S} and
 * rebuilds its own caches when this message arrives, because the registries it parses resources
 * against come from the server. That ordering is a contract between two files with nothing else
 * pinning it, and the message is the half the client waits on.
 */
class PackReloadPacketTest {

    /** A fresh directory per test; JUnit deletes it, and prints it when a test fails. */
    @TempDir
    Path gameDir;

    @Test
    void aReloadRequestRebuildsTheStackAndIsAnsweredWithAMessage() throws Exception {
        Path pack = gameDir.resolve("datapacks/testpack");
        Files.createDirectories(pack.resolve("data/test/plants"));
        Files.writeString(pack.resolve("data/test/plants/peashooter.json"), """
                {"id":"test:peashooter","cost":{"resources":{"pvzce:sun":125},"cooldown":300},
                 "health":400,"attack_interval":45,"shots":[]}
                """);

        try (ServerHarness server = ServerHarness.create(gameDir)) {
            Identifier plantFile = Identifier.of("test", "plants/peashooter.json");
            assertTrue(server.server().resourceManager().getData(plantFile).isPresent(),
                    "the pack is loaded before the player switches it off");

            // What the packs page does: write the list, then ask.
            PackSelection selection = PackSelection.load(gameDir);
            selection.setEnabled("testpack", false);
            assertTrue(selection.save());
            server.send(new ReloadPacksC2S());

            ServerMessageS2C answer = server.awaitPacket(ServerMessageS2C.class, 5_000);
            assertNotNull(answer);
            // The reload is what the request is for; the message itself is the client's cue, so
            // both facts are asserted on the same round trip.
            assertTrue(server.server().resourceManager().getData(plantFile).isEmpty(),
                    "the server read the same list file the client wrote");
        }
    }
}
