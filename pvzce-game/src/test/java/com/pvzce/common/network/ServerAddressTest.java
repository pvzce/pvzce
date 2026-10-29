package com.pvzce.common.network;

import com.pvzce.common.PvzceConstants;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ServerAddressTest {
    @Test void parsesWithoutResolvingHostnamesAndSupportsIpv6() {
        assertEquals(new ServerAddress("example.invalid", PvzceConstants.MULTIPLAYER_PORT), ServerAddress.parse(" example.invalid "));
        assertEquals(new ServerAddress("127.0.0.1", 25565), ServerAddress.parse("127.0.0.1:25565"));
        assertEquals("[::1]:24862", ServerAddress.parse("[::1]:24862").toString());
        assertEquals(new ServerAddress("::1", PvzceConstants.MULTIPLAYER_PORT), ServerAddress.parse("::1"));
    }
    @Test void rejectsInvalidExternalAddressesAndPorts() {
        for (String input : java.util.List.of("", "http://host", "host/path", "my host", ":123", "host:0", "host:65536", "host:abc", "host:", "[::1", "[::1]extra"))
            assertThrows(IllegalArgumentException.class, () -> ServerAddress.parse(input), input);
    }
}
