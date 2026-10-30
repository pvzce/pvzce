package com.pvzce.common.network;

import com.pvzce.common.PvzceConstants;
import java.net.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LanDiscoveryTest {
    private static byte[] announcement(int port, int players, int capacity, boolean versus, boolean playing) {
        return LanDiscovery.encode("00000000-0000-0000-0000-000000000001", new LanDiscovery.Announcement("房间", port, players, capacity, versus, playing));
    }
    @Test void sourceAddressIsUsedAndUnjoinableRoomsHaveExplicitStates() throws Exception {
        var source = InetAddress.getByName("192.0.2.9");
        var room = LanDiscovery.decode(announcement(24860, 1, 4, false, true), source);
        assertEquals("192.0.2.9:24860", room.address());
        assertTrue(room.joinable(), "Cooperative games allow joining in progress");
        assertFalse(LanDiscovery.decode(announcement(24860, 2, 2, true, false), source).joinable());
        assertFalse(LanDiscovery.decode(announcement(24860, 1, 2, true, true), source).joinable());
        String incompatible = new String(announcement(24860, 1, 2, true, false), StandardCharsets.UTF_8)
                .replace("\n" + PacketRegistry.fingerprint() + "\n", "\n99999\n");
        assertFalse(LanDiscovery.decode(incompatible.getBytes(StandardCharsets.UTF_8), source).joinable());
        assertNull(LanDiscovery.decode(announcement(0, 1, 4, false, false), source));
        assertNull(LanDiscovery.decode(announcement(24860, 9, 4, false, false), source));
        assertNull(LanDiscovery.decode(new byte[PvzceConstants.LAN_MAX_BYTES + 1], source));
    }
    @Test void refreshesDeduplicatesExpiresAndStopsAcceptingAfterClose() throws Exception {
        var source = InetAddress.getByName("192.0.2.9");
        try (var browser = new LanDiscovery.Browser(0)) {
            long now = System.nanoTime();
            browser.accept(announcement(24860, 1, 4, false, false), source, now);
            browser.accept(announcement(24860, 2, 4, false, true), source, now);
            browser.accept(announcement(24860, 2, 4, false, true), InetAddress.getByName("192.0.2.10"), now);
            assertEquals(1, browser.rooms().size(), "Multiple adapters must not duplicate a room");
            assertEquals("192.0.2.9:24860", browser.rooms().getFirst().address());
            assertEquals(2, browser.rooms().getFirst().players());
            browser.accept(announcement(24860, 1, 4, false, false), source,
                    now - (PvzceConstants.LAN_EXPIRY_MILLIS + 1) * 1_000_000L);
            assertTrue(browser.rooms().isEmpty());
            browser.close();
            browser.accept(announcement(24860, 1, 4, false, false), source, System.nanoTime());
            assertTrue(browser.rooms().isEmpty());
        }
    }
    @Test void receivesAnActualUdpAnnouncement() throws Exception {
        try (var browser = new LanDiscovery.Browser(0); var sender = new DatagramSocket()) {
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (browser.boundPort() <= 0 && System.nanoTime() < deadline) Thread.sleep(2);
            assertTrue(browser.boundPort() > 0);
            byte[] bytes = announcement(24860, 1, 4, false, false);
            sender.send(new DatagramPacket(bytes, bytes.length, InetAddress.getLoopbackAddress(), browser.boundPort()));
            while (browser.rooms().isEmpty() && System.nanoTime() < deadline) Thread.sleep(2);
            assertEquals(1, browser.rooms().size());
            assertEquals(1, browser.rooms().getFirst().players());
        }
    }
}
